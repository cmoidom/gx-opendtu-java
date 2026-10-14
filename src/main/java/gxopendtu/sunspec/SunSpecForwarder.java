package gxopendtu.sunspec;

import gxopendtu.allocator.CascadeAllocator;
import gxopendtu.battery.BatterySoc;
import gxopendtu.battery.BatterySocUnavailableException;
import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Write path opt-in via {@code sunspec_proxy.forward_to_opendtu}: translates Venus OS's
 * WMaxLimPct/WMaxLim_Ena/Conn into real OpenDTU commands, curtailing the
 * controllable inverters one at a time in {@code AppConfig.curtailOrder()}
 * via {@link CascadeAllocator} (2026-10-14 -- replaced the water-fill +
 * CapacityEstimator distribution, which moved every inverter's limit on
 * every change of Victron's cap: lots of RF traffic and limits shuffling
 * between inverters, see CascadeAllocator). {@code Main} suppresses
 * {@code ControlLoop}'s own OpenDTU writes (treating it as an effective
 * dry-run) for exactly as long as this forwarder is active, since both must
 * never command the same inverters at once.
 *
 * One decision loop on its own daemon thread ({@code control.decision_interval_s})
 * that reads WMaxLimPct/Ena and sends real limits.
 */
public final class SunSpecForwarder {

    private static final Logger LOG = Logger.getLogger(SunSpecForwarder.class.getName());
    private static final int FAILSAFE_THRESHOLD = 3;
    private static final double BATTERY_FULL_THRESHOLD_PCT = 99.0;

    private final OpenDTUApi client;
    private final SunSpecRegisterMap registerMap;
    private final List<String> allSerials;
    private final List<String> controllableSerials;
    private final double totalNominalPowerW;
    private final Map<String, Double> nominalPowerW;
    private final CascadeAllocator cascade;
    private final double minChangeW;
    private final BatterySoc batteryReader;
    private final boolean verboseTraces;
    private final long decisionIntervalMs;
    private volatile boolean running;
    private int consecutiveFailures;
    private boolean released;
    private boolean waitingLogged;
    private final Map<String, Double> lastSentW = new HashMap<>();

    public SunSpecForwarder(
            OpenDTUApi client,
            SunSpecRegisterMap registerMap,
            List<String> allSerials,
            List<String> controllableSerials,
            List<String> curtailOrder,
            Map<String, Double> nominalPowerW,
            double minInverterPct,
            double decisionIntervalS,
            double minChangeW,
            BatterySoc batteryReader,
            boolean verboseTraces) {
        this.client = client;
        this.registerMap = registerMap;
        this.allSerials = allSerials;
        this.controllableSerials = controllableSerials;
        this.totalNominalPowerW = nominalPowerW.values().stream().mapToDouble(Double::doubleValue).sum();
        this.nominalPowerW = Map.copyOf(nominalPowerW);
        this.cascade = new CascadeAllocator(curtailOrder, nominalPowerW, minInverterPct);
        this.decisionIntervalMs = Math.round(decisionIntervalS * 1000);
        this.minChangeW = minChangeW;
        this.batteryReader = batteryReader;
        this.verboseTraces = verboseTraces;
    }

    public void start() {
        running = true;
        Thread decisionThread = new Thread(this::decisionLoop, "sunspec-forwarder-decision");
        decisionThread.setDaemon(true);
        decisionThread.start();
        LOG.warning("[SunSpec] forwarding vers OpenDTU ACTIF -- ce proxy pilote reellement les onduleurs "
                + "d'apres la limite ecrite par Victron; la regulation zero-export habituelle n'envoie plus rien");
    }

    public void stop() {
        running = false;
    }

    private void decisionLoop() {
        while (running) {
            decisionTick();
            sleep(decisionIntervalMs);
        }
    }

    /** Package-private: the actual decision logic, callable directly in tests without waiting on real threads. */
    void decisionTick() {
        try {
            Map<String, Double> livePowerW = client.getLivePowerW(allSerials);
            Map<String, Double> dataAgeS = client.getDataAgeS(controllableSerials);
            consecutiveFailures = 0;

            if (!registerMap.everWritten()) {
                holdUntilVictronConnects();
                return;
            }
            boolean limitEnabled = registerMap.wMaxLimEnabled() && registerMap.connected();
            if (!limitEnabled) {
                releaseIfNeeded();
                return;
            }
            released = false;

            // WMaxLimPct is a percentage of the WHOLE installation's nameplate
            // (Model 120's WRtg = every configured inverter, matching what
            // Victron was told the device's total rating is) -- but only the
            // controllable subset is actually being water-filled here, so
            // whatever the non-controllable inverters are already producing
            // (uncapped, always) must be subtracted first. Otherwise real
            // total output could exceed what Victron asked for by up to the
            // non-controllable inverters' own production.
            double nonControllableActualW = allSerials.stream()
                    .filter(s -> !controllableSerials.contains(s))
                    .mapToDouble(s -> livePowerW.getOrDefault(s, 0.0))
                    .sum();
            VictronCap cap = effectiveWMaxLimPctPercent();
            double targetW = Math.max(0.0, cap.pct() / 100.0 * totalNominalPowerW - nonControllableActualW);
            // Ignored means the battery has headroom -- nothing is actually
            // restricting production, so every controllable inverter goes
            // straight to its own nameplate power, and the cascade starts
            // over from its first inverter the next time Victron's cap applies.
            Map<String, Double> allocation;
            if (cap.ignored()) {
                cascade.reset();
                allocation = controllableSerials.stream().collect(Collectors.toMap(
                        s -> s, s -> nominalPowerW.getOrDefault(s, 0.0)));
            } else {
                allocation = cascade.allocate(targetW, livePowerW, dataAgeS, System.currentTimeMillis() / 1000.0);
            }

            if (verboseTraces) {
                LOG.info("[SunSpec] targetW=" + targetW
                        + " nonControllableActualW=" + nonControllableActualW
                        + " ignored=" + cap.ignored()
                        + " livePowerW=" + livePowerW
                        + " allocation=" + allocation);
            }

            // Only resends an inverter whose allocation moved by more than
            // min_change_w since the last real send -- otherwise every
            // decision tick (control.decision_interval_s, independent of
            // whether Victron's own WMaxLimPct actually changed meaningfully)
            // would reissue a command, resetting OpenDTU's RF acknowledgement
            // to Pending before the previous one ever had a chance to land
            // (confirmed live: every inverter stuck at "Pending" indefinitely
            // before this fix).
            allocation.forEach((serial, watts) -> {
                Double previous = lastSentW.get(serial);
                if (previous != null && Math.abs(watts - previous) < minChangeW) {
                    return;
                }
                try {
                    client.setAbsoluteLimitW(serial, watts);
                    lastSentW.put(serial, watts);
                } catch (OpenDTUException e) {
                    LOG.severe("[SunSpec] envoi limite " + serial + " echoue: " + e.getMessage());
                }
            });
        } catch (OpenDTUException e) {
            consecutiveFailures++;
            LOG.log(
                    Level.WARNING,
                    "[SunSpec] lecture OpenDTU echouee (" + consecutiveFailures + " fois de suite)",
                    e);
            if (consecutiveFailures == FAILSAFE_THRESHOLD) {
                holdOnOpenDtuLoss();
            }
        }
    }

    /** {@code pct} is 100 whenever {@code ignored} is true -- kept together so callers can't use one without the other. */
    private record VictronCap(double pct, boolean ignored) {}

    /**
     * Victron's own zero-feed-in WMaxLimPct curtails purely off its grid
     * meter, with no knowledge of our battery's state -- confirmed live
     * (2026-07-20 08:20) requesting as little as 30% while the battery was
     * actively discharging at -1.5kW, i.e. curtailing PV at the exact moment
     * more of it was needed. Below the full threshold there's still room to
     * absorb any excess production, so Victron's cap is ignored entirely --
     * every controllable inverter goes straight to 100% (see the
     * {@code ignored} branch in decisionTick). Any BatterySoc read
     * failure, or no battery configured at all, falls back to trusting
     * Victron's cap as-is -- the safe default when battery headroom can't be
     * confirmed.
     */
    private VictronCap effectiveWMaxLimPctPercent() {
        double wMaxLimPct = registerMap.wMaxLimPctPercent();
        if (batteryReader == null) {
            return new VictronCap(wMaxLimPct, false);
        }
        try {
            double socPct = batteryReader.readSocPct();
            boolean ignored = socPct < BATTERY_FULL_THRESHOLD_PCT;
            if (verboseTraces) {
                LOG.info("[SunSpec] SOC=" + socPct + "% WMaxLimPct(Victron)=" + wMaxLimPct + "% -> "
                        + (ignored ? "ignoree (100%)" : "respectee"));
            }
            return ignored ? new VictronCap(100.0, true) : new VictronCap(wMaxLimPct, false);
        } catch (BatterySocUnavailableException e) {
            LOG.warning("[SunSpec] lecture SOC batterie echouee, consigne Victron respectee par defaut: "
                    + e.getMessage());
            return new VictronCap(wMaxLimPct, false);
        }
    }

    /**
     * Right after a (re)start, before Victron's first Modbus write: the
     * register defaults read as WMaxLim_Ena=0, which used to trigger
     * releaseIfNeeded and send every inverter to 100% for the ~15-20s the
     * Cerbo takes to reconnect -- an export spike of -1000 to -1400W on each
     * restart (confirmed live 2026-10-14 16:47/16:54/16:57). Sends nothing
     * instead, so the inverters keep their current limits, same rule as on a
     * communication loss (user decision 2026-10-14).
     */
    private void holdUntilVictronConnects() {
        if (!waitingLogged) {
            LOG.info("[SunSpec] en attente de la premiere ecriture de Victron: aucune commande envoyee, "
                    + "les onduleurs gardent leur limite courante");
            waitingLogged = true;
        }
    }

    /** Only sends the release once per OFF-transition, not every tick, matching ControlLoop's own discipline. */
    private void releaseIfNeeded() {
        if (released) {
            return;
        }
        LOG.info("[SunSpec] WMaxLim_Ena=0/Conn=0: liberation des onduleurs pilotables a 100%");
        lastSentW.clear(); // the 100% below overwrites the last absolute limits, so they must be resent later
        cascade.reset(); // ...and nothing is curtailed anymore
        for (String serial : controllableSerials) {
            try {
                client.setRelativeLimitPct(serial, 100);
            } catch (OpenDTUException e) {
                LOG.severe("[SunSpec] liberation a 100% de " + serial + " echouee: " + e.getMessage());
            }
        }
        released = true;
    }

    /**
     * Repeated OpenDTU loss: sends nothing, every inverter keeps its current
     * limit -- see ControlLoop.holdLimitsOnCommunicationLoss for why this
     * replaced the former curtail-to-0% fail-safe (user decision 2026-10-10).
     * lastSentW is still forgotten: what actually landed during a flapping
     * link is unknown, so the first successful tick after recovery resends
     * every allocation (a stale lastSentW once left the inverters stuck at
     * 0% for over 2h, confirmed live 2026-10-09 13:23-15:42).
     */
    private void holdOnOpenDtuLoss() {
        LOG.warning("[SunSpec] pertes OpenDTU repetees: aucune commande envoyee, "
                + "les onduleurs gardent leur limite courante");
        lastSentW.clear();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
