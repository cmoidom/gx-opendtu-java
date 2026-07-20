package gxopendtu.sunspec;

import gxopendtu.allocator.WaterFillAllocator;
import gxopendtu.battery.BatterySoc;
import gxopendtu.battery.BatterySocUnavailableException;
import gxopendtu.control.CapacityEstimator;
import gxopendtu.opendtu.LimitStatus;
import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Write path opt-in via {@code sunspec_proxy.forward_to_opendtu}: translates Venus OS's
 * WMaxLimPct/WMaxLim_Ena/Conn into real OpenDTU commands, distributed
 * across the real inverters via the same {@link WaterFillAllocator} +
 * {@link CapacityEstimator} pattern {@code loop.ControlLoop} uses for its
 * own PI-computed target -- its own separate {@code CapacityEstimator}
 * instance, since this module and {@code ControlLoop} must never both be
 * commanding the same inverters at once. {@code Main} enforces that by
 * suppressing {@code ControlLoop}'s own OpenDTU writes (treating it as an
 * effective dry-run) for exactly as long as this forwarder is active.
 *
 * Two independent-cadence loops on their own daemon threads, mirroring
 * {@code ControlLoop}'s own two-cadence design: a decision loop
 * ({@code control.decision_interval_s}) that reads WMaxLimPct/Ena and sends
 * real limits, and a probe loop ({@code capacity_probe.interval_s}) that
 * nudges each inverter's capacity ceiling back up -- see
 * {@code CapacityEstimator}'s own javadoc for why that recovery exists.
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
    private final CapacityEstimator capacity;
    private final double minInverterPct;
    private final double minChangeW;
    private final BatterySoc batteryReader;
    private final long decisionIntervalMs;
    private final long probeIntervalMs;
    private volatile boolean running;
    private int consecutiveFailures;
    private boolean released;
    private final Map<String, Double> lastSentW = new HashMap<>();

    public SunSpecForwarder(
            OpenDTUApi client,
            SunSpecRegisterMap registerMap,
            List<String> allSerials,
            List<String> controllableSerials,
            Map<String, Double> nominalPowerW,
            double capacityProbeStepW,
            double minInverterPct,
            double decisionIntervalS,
            double capacityProbeIntervalS,
            double minChangeW,
            BatterySoc batteryReader) {
        this.client = client;
        this.registerMap = registerMap;
        this.allSerials = allSerials;
        this.controllableSerials = controllableSerials;
        this.totalNominalPowerW = nominalPowerW.values().stream().mapToDouble(Double::doubleValue).sum();
        this.capacity = new CapacityEstimator(nominalPowerW, capacityProbeStepW);
        this.minInverterPct = minInverterPct;
        this.decisionIntervalMs = Math.round(decisionIntervalS * 1000);
        this.probeIntervalMs = Math.round(capacityProbeIntervalS * 1000);
        this.minChangeW = minChangeW;
        this.batteryReader = batteryReader;
    }

    public void start() {
        running = true;
        Thread decisionThread = new Thread(this::decisionLoop, "sunspec-forwarder-decision");
        decisionThread.setDaemon(true);
        decisionThread.start();
        Thread probeThread = new Thread(this::probeLoop, "sunspec-forwarder-probe");
        probeThread.setDaemon(true);
        probeThread.start();
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

    private void probeLoop() {
        while (running) {
            sleep(probeIntervalMs);
            capacity.probeTick();
        }
    }

    /** Package-private: the actual decision logic, callable directly in tests without waiting on real threads. */
    void decisionTick() {
        try {
            Map<String, Double> livePowerW = client.getLivePowerW(allSerials);
            Map<String, LimitStatus> limitStatus = client.getLimitStatus();
            Map<String, Double> dataAgeS = client.getDataAgeS(allSerials);
            consecutiveFailures = 0;

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
            double wMaxLimPct = effectiveWMaxLimPctPercent();
            double targetW = Math.max(0.0, wMaxLimPct / 100.0 * totalNominalPowerW - nonControllableActualW);
            Map<String, Double> allocation = WaterFillAllocator.waterFillAllocate(
                    targetW, controllableSerials, capacity.ceilingsW(), capacity.nominalPowerW(), minInverterPct);

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

            for (String serial : controllableSerials) {
                LimitStatus status = limitStatus.get(serial);
                capacity.observe(
                        serial,
                        allocation.getOrDefault(serial, 0.0),
                        livePowerW.getOrDefault(serial, 0.0),
                        status != null ? status.acknowledged() : true,
                        dataAgeS.getOrDefault(serial, 0.0));
            }
        } catch (OpenDTUException e) {
            consecutiveFailures++;
            LOG.log(
                    Level.WARNING,
                    "[SunSpec] lecture OpenDTU echouee (" + consecutiveFailures + " fois de suite)",
                    e);
            if (consecutiveFailures >= FAILSAFE_THRESHOLD) {
                failsafe();
            }
        }
    }

    /**
     * Victron's own zero-feed-in WMaxLimPct curtails purely off its grid
     * meter, with no knowledge of our battery's state -- confirmed live
     * (2026-07-20 08:20) requesting as little as 30% while the battery was
     * actively discharging at -1.5kW, i.e. curtailing PV at the exact moment
     * more of it was needed. Below the full threshold there's still room to
     * absorb any excess production, so Victron's cap is ignored (treated as
     * 100%) and the water-fill/capacity-estimator ceiling is left to do the
     * only limiting that still applies. Any BatterySoc read failure, or no
     * battery configured at all, falls back to trusting Victron's cap as-is
     * -- the safe default when battery headroom can't be confirmed.
     */
    private double effectiveWMaxLimPctPercent() {
        double wMaxLimPct = registerMap.wMaxLimPctPercent();
        if (batteryReader == null) {
            return wMaxLimPct;
        }
        try {
            double socPct = batteryReader.readSocPct();
            return socPct < BATTERY_FULL_THRESHOLD_PCT ? 100.0 : wMaxLimPct;
        } catch (BatterySocUnavailableException e) {
            LOG.warning("[SunSpec] lecture SOC batterie echouee, consigne Victron respectee par defaut: "
                    + e.getMessage());
            return wMaxLimPct;
        }
    }

    /** Only sends the release once per OFF-transition, not every tick, matching ControlLoop's own discipline. */
    private void releaseIfNeeded() {
        if (released) {
            return;
        }
        LOG.info("[SunSpec] WMaxLim_Ena=0/Conn=0: liberation des onduleurs pilotables a 100%");
        for (String serial : controllableSerials) {
            try {
                client.setRelativeLimitPct(serial, 100);
            } catch (OpenDTUException e) {
                LOG.severe("[SunSpec] liberation a 100% de " + serial + " echouee: " + e.getMessage());
            }
        }
        released = true;
    }

    private void failsafe() {
        LOG.warning("[SunSpec] fail-safe: mise a 0% de tous les onduleurs pilotables (pertes OpenDTU repetees)");
        for (String serial : controllableSerials) {
            try {
                client.setRelativeLimitPct(serial, 0);
            } catch (OpenDTUException e) {
                LOG.severe("[SunSpec] fail-safe curtail de " + serial + " echoue: " + e.getMessage());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
