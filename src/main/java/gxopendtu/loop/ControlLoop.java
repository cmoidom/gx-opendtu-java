package gxopendtu.loop;

import gxopendtu.allocator.WaterFillAllocator;
import gxopendtu.battery.BatterySoc;
import gxopendtu.battery.BatterySocUnavailableException;
import gxopendtu.battery.ModbusBatterySoc;
import gxopendtu.config.AppConfig;
import gxopendtu.config.AppConfig.InverterConfig;
import gxopendtu.config.AppConfig.ModbusGridConfig;
import gxopendtu.control.BatteryFullHysteresis;
import gxopendtu.control.CapacityEstimator;
import gxopendtu.control.GridPowerSmoother;
import gxopendtu.control.SoftTargetController;
import gxopendtu.control.SoftTargetController.ControlDecision;
import gxopendtu.grid.GridMeter;
import gxopendtu.grid.GridMeterUnavailableException;
import gxopendtu.grid.ModbusGridMeter;
import gxopendtu.opendtu.LimitStatus;
import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUClient;
import gxopendtu.opendtu.OpenDTUException;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.state.StateStore;
import gxopendtu.stats.StatsStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Wires the grid meter, OpenDTU client and control loop together.
 *
 * Two cadences: a fast read/smooth loop for grid power
 * (config.grid.readIntervalS) and a slower, quantized decision loop that
 * talks to OpenDTU (config.control.decisionIntervalS). See ARCHITECTURE.md
 * for the full design. Port of src/main.py.
 */
public final class ControlLoop {

    private static final int FAILSAFE_AFTER_CONSECUTIVE_FAILURES = 3;
    private static final double SECONDS_PER_DAY = 86400.0;
    private static final double PRUNE_INTERVAL_S = SECONDS_PER_DAY;
    // Deliberately a fixed constant, not config.stats.intervalS -- that value
    // already means "downsample bucket width" (see StatsStore's javadoc);
    // reusing it here would silently change the crash-loss window (how much
    // buffered high-res data an unclean shutdown could lose) whenever
    // someone tunes downsampling. 1 minute keeps that window small while
    // still turning ~30 individual per-tick writes into one batched one.
    private static final double SAMPLE_FLUSH_INTERVAL_S = 60.0;
    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");

    private ControlLoop() {}

    private static GridMeter makeGridReader(AppConfig config) {
        ModbusGridConfig modbus = config.grid().modbus();
        return new ModbusGridMeter(modbus.host(), modbus.port());
    }

    private static BatterySoc makeBatteryReader(AppConfig config) {
        if (!config.battery().enabled()) {
            return null;
        }
        ModbusGridConfig modbus = config.grid().modbus();
        return new ModbusBatterySoc(modbus.host(), modbus.port());
    }

    /** {warning, recommendedPct} -- recommendedPct is null unless warning is true. */
    record FloorWarning(boolean warning, Double recommendedPct) {}

    /**
     * Assembles one inverter's dashboard payload entry -- the 7-key map
     * shape that {@code decisionCycle}, {@code offStateInvertersPayload} and
     * {@code manualOverridePayload} each used to build by hand, independently,
     * once per mode (normal/OFF/manual-override). Each caller still computes
     * its own allocated_w/limitRelativePct/maxPowerW/acknowledged the way it
     * always did (their fallback values and source maps genuinely differ per
     * mode) -- only the "put these into a map with these keys" part, which
     * was pure duplication, is shared here.
     */
    private static Map<String, Object> inverterPayloadEntry(
            String serial,
            String name,
            Object allocatedW,
            double actualW,
            Object limitRelativePct,
            double maxPowerW,
            Object acknowledged) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("serial", serial);
        entry.put("name", name);
        entry.put("allocated_w", allocatedW);
        entry.put("actual_w", actualW);
        entry.put("limit_relative_pct", limitRelativePct);
        entry.put("max_power_w", maxPowerW);
        entry.put("acknowledged", acknowledged);
        return entry;
    }

    /**
     * Detects the min_inverter_pct floor pushing the total allocation above
     * what the controller actually wanted while the grid is exporting -- a
     * sign the configured floor is higher than this install's real demand
     * right now (config.control.minInverterPct is authoritative regardless,
     * see ARCHITECTURE.md -- this is purely informational).
     *
     * recommendedPct is the largest floor that would NOT have exceeded this
     * cycle's target, given the nominal power of inverters that currently
     * have real capacity -- a live, instantaneous suggestion, not a
     * universal fixed value.
     */
    static FloorWarning minInverterFloorWarning(
            double minInverterPct,
            double gridPowerAvgW,
            double targetW,
            Map<String, Double> allocation,
            Map<String, Double> ceilingsW,
            Map<String, Double> nominalPowerW,
            List<String> serials) {
        if (minInverterPct <= 0 || gridPowerAvgW >= 0) {
            return new FloorWarning(false, null);
        }
        double totalAllocatedW = serials.stream().mapToDouble(s -> allocation.getOrDefault(s, 0.0)).sum();
        if (totalAllocatedW <= targetW + 1e-6) {
            return new FloorWarning(false, null);
        }
        double capacityW = serials.stream()
                .filter(s -> ceilingsW.getOrDefault(s, 0.0) > 0)
                .mapToDouble(s -> nominalPowerW.getOrDefault(s, 0.0))
                .sum();
        double recommendedPct =
                capacityW > 0 ? Math.round(Math.max(0.0, targetW) / capacityW * 100.0 * 10.0) / 10.0 : 0.0;
        return new FloorWarning(true, recommendedPct);
    }

    static void decisionCycle(
            OpenDTUApi client,
            SoftTargetController controller,
            CapacityEstimator capacity,
            List<String> serials,
            double gridPowerRawW,
            double gridPowerAvgW,
            LiveState liveState,
            Double socPct,
            Double batteryPowerW,
            Double batteryVoltageV,
            Double batteryCurrentA,
            boolean dryRun,
            boolean verboseTraces,
            double minInverterPct,
            Map<String, String> nameBySerial) {
        Map<String, String> names = nameBySerial != null ? nameBySerial : Map.of();
        Map<String, Double> livePowerW = client.getLivePowerW(serials);
        Map<String, LimitStatus> limitStatus = client.getLimitStatus();

        double currentTotalActualW = serials.stream().mapToDouble(s -> livePowerW.getOrDefault(s, 0.0)).sum();
        double totalCapacityW = serials.stream().mapToDouble(s -> capacity.ceilingsW().getOrDefault(s, 0.0)).sum();

        ControlDecision decision =
                controller.computeTarget(gridPowerAvgW, currentTotalActualW, totalCapacityW, batteryPowerW);
        Map<String, Double> allocation = WaterFillAllocator.waterFillAllocate(
                decision.targetW(), serials, capacity.ceilingsW(), capacity.nominalPowerW(), minInverterPct);
        Map<String, Long> roundedAllocation = new LinkedHashMap<>();
        allocation.forEach((s, w) -> roundedAllocation.put(s, Math.round(w)));

        if (!dryRun && decision.changed()) {
            allocation.forEach(client::setAbsoluteLimitW);
        }

        FloorWarning floorWarning = minInverterFloorWarning(
                minInverterPct,
                gridPowerAvgW,
                decision.targetW(),
                allocation,
                capacity.ceilingsW(),
                capacity.nominalPowerW(),
                serials);
        if (floorWarning.warning()) {
            // Always logged (unlike the verboseTraces-gated line below): the
            // floor is doing exactly what config.control.minInverterPct
            // asked for, but if this fires often the configured value is
            // probably higher than this install's real demand right now.
            LOG.warning(String.format(
                    "min_inverter_pct=%.0f%% causing grid export this cycle (grid_ema=%+.0fW, consigne=%.0fW) -- "
                            + "valeur qui n'aurait pas depasse la consigne ce cycle: %.1f%%",
                    minInverterPct,
                    gridPowerAvgW,
                    decision.targetW(),
                    floorWarning.recommendedPct() != null ? floorWarning.recommendedPct() : 0.0));
        }

        if (liveState != null) {
            List<Map<String, Object>> invertersPayload = new ArrayList<>();
            for (String serial : serials) {
                LimitStatus status = limitStatus.get(serial);
                invertersPayload.add(inverterPayloadEntry(
                        serial,
                        names.get(serial),
                        roundedAllocation.getOrDefault(serial, 0L),
                        livePowerW.getOrDefault(serial, 0.0),
                        status != null ? status.limitRelative() : null,
                        capacity.ceilingsW().getOrDefault(serial, 0.0),
                        status != null ? status.acknowledged() : null));
            }
            liveState.updateDecision(
                    socPct,
                    "ON",
                    decision.targetW(),
                    invertersPayload,
                    batteryPowerW,
                    batteryVoltageV,
                    batteryCurrentA,
                    floorWarning.warning(),
                    floorWarning.recommendedPct());
        }

        // Logs full state every cycle (not just on change) for debug
        // visibility when verboseTraces is on -- this only affects local
        // logging, not OpenDTU traffic (still gated by decision.changed()
        // above), so it doesn't undo the rate-limiting the soft controller
        // is there for. Independent of the /dashboard live view, which
        // always updates.
        if (verboseTraces) {
            String socStr = socPct != null ? String.format(" soc=%.0f%%", socPct) : "";
            LOG.info(String.format(
                    "%sgrid_meter=%+.0fW ema=%+.0fW opendtu_actual=%.0fW%s injection_control=ON consigne=%.0fW "
                            + "allocation=%s changed=%s%s",
                    dryRun ? "[DRY-RUN] " : "",
                    gridPowerRawW,
                    gridPowerAvgW,
                    currentTotalActualW,
                    socStr,
                    decision.targetW(),
                    roundedAllocation,
                    decision.changed(),
                    dryRun ? " (rien envoye)" : ""));
        }

        for (String serial : serials) {
            LimitStatus status = limitStatus.get(serial);
            capacity.observe(
                    serial,
                    allocation.getOrDefault(serial, 0.0),
                    livePowerW.getOrDefault(serial, 0.0),
                    status != null ? status.acknowledged() : true);
        }
    }

    /**
     * Live per-inverter power for the dashboard while injection control is
     * OFF (charge batterie prioritaire): inverters are uncapped, so there is
     * no allocated/limit-status data to report, but the actual measured
     * power is still meaningful and otherwise leaves the dashboard looking
     * empty/broken during the whole charge-priority window.
     */
    static List<Map<String, Object>> offStateInvertersPayload(
            OpenDTUApi client, List<String> serials, Map<String, Double> nominalPowerW, Map<String, String> nameBySerial) {
        Map<String, String> names = nameBySerial != null ? nameBySerial : Map.of();
        Map<String, Double> livePowerW;
        try {
            livePowerW = client.getLivePowerW(serials);
        } catch (OpenDTUException e) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String serial : serials) {
            result.add(inverterPayloadEntry(
                    serial,
                    names.get(serial),
                    null,
                    livePowerW.getOrDefault(serial, 0.0),
                    100,
                    nominalPowerW.getOrDefault(serial, 0.0),
                    null));
        }
        return result;
    }

    /**
     * Forces every inverter to the same fixed relative % (dashboard manual
     * test override) -- bypasses the PI and water-filling entirely, unlike
     * the normal ON path. Only called once per override activation/change,
     * not every decision cycle.
     */
    static void sendManualOverride(OpenDTUApi client, List<String> serials, double pct, boolean dryRun) {
        if (dryRun) {
            LOG.info(String.format("[DRY-RUN] forcage manuel: %.0f%% sur tous les onduleurs (rien envoye)", pct));
            return;
        }
        LOG.info(String.format("forcage manuel: %.0f%% sur tous les onduleurs", pct));
        for (String serial : serials) {
            try {
                client.setRelativeLimitPct(serial, pct);
            } catch (OpenDTUException e) {
                LOG.severe(String.format("forcage manuel: echec %.0f%% sur %s: %s", pct, serial, e.getMessage()));
            }
        }
    }

    static List<Map<String, Object>> manualOverridePayload(
            OpenDTUApi client,
            List<String> serials,
            double pct,
            Map<String, Double> nominalPowerW,
            Map<String, String> nameBySerial) {
        Map<String, String> names = nameBySerial != null ? nameBySerial : Map.of();
        Map<String, Double> livePowerW;
        try {
            livePowerW = client.getLivePowerW(serials);
        } catch (OpenDTUException e) {
            livePowerW = Map.of();
        }
        Map<String, LimitStatus> limitStatus;
        try {
            limitStatus = client.getLimitStatus();
        } catch (OpenDTUException e) {
            limitStatus = Map.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String serial : serials) {
            LimitStatus status = limitStatus.get(serial);
            result.add(inverterPayloadEntry(
                    serial,
                    names.get(serial),
                    Math.round(pct / 100.0 * nominalPowerW.getOrDefault(serial, 0.0)),
                    livePowerW.getOrDefault(serial, 0.0),
                    status != null ? status.limitRelative() : pct,
                    nominalPowerW.getOrDefault(serial, 0.0),
                    status != null ? status.acknowledged() : null));
        }
        return result;
    }

    /** Battery not yet full: release curtailment so PV runs uncapped and the Victron ESS charges from the surplus. */
    static void releaseForCharging(OpenDTUApi client, List<String> serials, boolean dryRun) {
        if (dryRun) {
            LOG.info("[DRY-RUN] charge batterie prioritaire: onduleurs seraient debloques a 100% (rien envoye)");
            return;
        }
        LOG.info("charge batterie prioritaire: deblocage des onduleurs a 100%");
        for (String serial : serials) {
            try {
                client.setRelativeLimitPct(serial, 100);
            } catch (OpenDTUException e) {
                LOG.severe(String.format("release to 100%% of %s failed: %s", serial, e.getMessage()));
            }
        }
    }

    static void applyFailsafe(OpenDTUApi client, List<String> serials, boolean dryRun) {
        if (dryRun) {
            LOG.warning("[DRY-RUN] fail-safe se declencherait: mise a 0% de tous les onduleurs (rien envoye)");
            return;
        }
        LOG.warning("applying fail-safe: curtailing all inverters to 0%");
        for (String serial : serials) {
            try {
                client.setRelativeLimitPct(serial, 0);
            } catch (OpenDTUException e) {
                LOG.severe(String.format("fail-safe curtail of %s failed: %s", serial, e.getMessage()));
            }
        }
    }

    public static void run(
            AppConfig config,
            boolean dryRun,
            LiveState liveState,
            HourlyEnergyHistory energyHistory,
            InverterEnergyHistory inverterEnergyHistory,
            Path configPath,
            ManualOverride manualOverride,
            InjectionModeOverride injectionMode,
            StatsStore statsStore) {
        GridMeter gridReader = makeGridReader(config);
        BatterySoc batteryReader = makeBatteryReader(config);

        // No persisted state (configPath unset, first run, or corrupt/missing
        // state.json) defaults to true (curtailing) rather than false: with a
        // battery reader present, the very next real SOC reading will correct
        // this within one cycle anyway, so defaulting "safe" costs at most one
        // cycle of unnecessary curtailment, whereas defaulting "not full"
        // risked exactly the stuck-OFF-while-actually-full scenario this
        // persistence exists to prevent.
        Boolean persistedActive = configPath != null ? StateStore.loadInjectionActive(configPath) : null;
        BatteryFullHysteresis hysteresis = batteryReader != null
                ? new BatteryFullHysteresis(
                        config.battery().activateAtPct(),
                        config.battery().deactivateBelowPct(),
                        persistedActive != null ? persistedActive : true,
                        config.battery().exportConfirmsFullW(),
                        config.battery().exportConfirmsFullDurationS())
                : null;
        Boolean lastPersistedActive = persistedActive;

        OpenDTUApi client = new OpenDTUClient(config.opendtu().baseUrl(), config.opendtu().username(), config.opendtu().password());
        GridPowerSmoother smoother = new GridPowerSmoother(config.grid().emaAlpha());
        SoftTargetController controller = new SoftTargetController(
                config.grid().exportSetpointW(),
                config.control().kp(),
                config.control().ki(),
                config.control().stepAbsoluteW(),
                config.control().stepRelativePct(),
                config.control().minChangeW(),
                null,
                config.control().minBatteryDischargeW());

        Map<String, Double> nominalPowerW =
                config.inverters().stream().collect(Collectors.toMap(InverterConfig::serial, InverterConfig::nominalPowerW));
        Map<String, String> nameBySerial = config.inverters().stream()
                .filter(inv -> inv.name() != null)
                .collect(Collectors.toMap(InverterConfig::serial, InverterConfig::name));
        CapacityEstimator capacity = new CapacityEstimator(nominalPowerW, config.capacityProbe().stepW());
        List<String> serials = config.inverters().stream().map(InverterConfig::serial).toList();

        double lastDecisionTime = 0.0;
        double lastProbeTime = 0.0;
        double lastStatsWriteTime = 0.0;
        double lastSampleFlushTime = 0.0;
        double lastPruneTime = 0.0;
        int consecutiveGridFailures = 0;
        boolean releasedForCharging = false;
        Double lastOverridePctSent = null;

        while (true) {
            double now = System.nanoTime() / 1_000_000_000.0;

            double gridPowerW;
            try {
                gridPowerW = gridReader.readGridPowerW();
                smoother.add(gridPowerW);
                liveState.recordGrid(gridPowerW, smoother.average());
                // Same cadence as the live view itself -- downsampleOlderThan
                // (below) is what actually keeps stats.db from growing at
                // this rate forever, by thinning anything older than
                // config.stats.highResRetentionDays back down.
                statsStore.recordLatestSample(liveState);
                consecutiveGridFailures = 0;
            } catch (GridMeterUnavailableException e) {
                consecutiveGridFailures++;
                LOG.severe(String.format("grid meter read failed (%d in a row): %s", consecutiveGridFailures, e.getMessage()));
                if (consecutiveGridFailures >= FAILSAFE_AFTER_CONSECUTIVE_FAILURES) {
                    applyFailsafe(client, serials, dryRun);
                }
                sleepSeconds(config.grid().readIntervalS());
                continue;
            }

            if (now - lastDecisionTime >= config.control().decisionIntervalS()) {
                lastDecisionTime = now;

                try {
                    GridMeter.EnergyReading reading = gridReader.readEnergyKwh();
                    energyHistory.record(reading.fromNetKwh(), reading.toNetKwh());
                } catch (GridMeterUnavailableException e) {
                    LOG.severe("grid energy counters read failed (dashboard display only): " + e.getMessage());
                }
                try {
                    // Read regardless of injection_control ON/OFF/OVERRIDE:
                    // inverters keep producing (and OpenDTU keeps counting
                    // YieldDay) even while curtailed for battery-charge
                    // priority or under a manual override. Deliberately NOT
                    // passing `now` here -- that's System.nanoTime()-based
                    // (for measuring loop intervals), not wall-clock epoch
                    // seconds, and record()'s no-arg-time overload correctly
                    // uses System.currentTimeMillis() instead, same as
                    // energyHistory.record(...) just above.
                    inverterEnergyHistory.record(client.getYieldDayWh(serials));
                } catch (OpenDTUException e) {
                    LOG.severe("inverter yield read failed (dashboard display only): " + e.getMessage());
                }

                Double socPct = null;
                Double batteryPowerW = null;
                Double batteryVoltageV = null;
                Double batteryCurrentA = null;
                boolean injectionActive = true;
                if (batteryReader != null) {
                    try {
                        socPct = batteryReader.readSocPct();
                        InjectionModeOverride.Mode mode = injectionMode.getMode();
                        if (mode == InjectionModeOverride.Mode.ON) {
                            hysteresis.setActive(true);
                            injectionActive = true;
                        } else if (mode == InjectionModeOverride.Mode.OFF) {
                            hysteresis.setActive(false);
                            injectionActive = false;
                        } else {
                            injectionActive = hysteresis.update(socPct, smoother.average(), now);
                        }
                    } catch (BatterySocUnavailableException e) {
                        // Safe default: if we can't tell whether the battery
                        // is full, assume it is and keep injection control
                        // active rather than releasing curtailment
                        // unsupervised. Does not touch the latch itself,
                        // only this cycle's action.
                        LOG.severe("battery SOC read failed, defaulting injection control to ACTIVE (safe): " + e.getMessage());
                        injectionActive = true;
                    }
                    try {
                        batteryPowerW = batteryReader.readPowerW();
                    } catch (BatterySocUnavailableException e) {
                        batteryPowerW = null; // dashboard display only, not safety-critical
                    }
                    try {
                        batteryVoltageV = batteryReader.readVoltageV();
                    } catch (BatterySocUnavailableException e) {
                        batteryVoltageV = null; // not configured, or read failed -- dashboard display only
                    }
                    try {
                        batteryCurrentA = batteryReader.readCurrentA();
                    } catch (BatterySocUnavailableException e) {
                        batteryCurrentA = null; // dashboard display only
                    }

                    if (configPath != null
                            && (lastPersistedActive == null || hysteresis.isActive() != lastPersistedActive)) {
                        StateStore.saveInjectionActive(configPath, hysteresis.isActive());
                        lastPersistedActive = hysteresis.isActive();
                    }
                }

                if (!injectionActive) {
                    if (!releasedForCharging) {
                        releaseForCharging(client, serials, dryRun);
                        releasedForCharging = true;
                    }
                    // So a still-active % override gets re-sent (not skipped
                    // as "already applied") if injection resumes later: the
                    // release above just overwrote whatever it had set.
                    lastOverridePctSent = null;
                    liveState.updateDecision(
                            socPct,
                            "OFF",
                            null,
                            offStateInvertersPayload(client, serials, nominalPowerW, nameBySerial),
                            batteryPowerW,
                            batteryVoltageV,
                            batteryCurrentA,
                            false,
                            null);
                    if (config.logging().verboseTraces()) {
                        LOG.info(String.format(
                                "%ssoc=%.0f%% grid_meter=%+.0fW ema=%+.0fW injection_control=OFF (charge batterie "
                                        + "prioritaire)%s",
                                dryRun ? "[DRY-RUN] " : "",
                                socPct != null ? socPct : Double.NaN,
                                gridPowerW,
                                smoother.average(),
                                dryRun ? " (rien envoye)" : ""));
                    }
                } else {
                    releasedForCharging = false;
                    Double overridePct = manualOverride.activePct();
                    if (overridePct != null) {
                        if (!overridePct.equals(lastOverridePctSent)) {
                            sendManualOverride(client, serials, overridePct, dryRun);
                            lastOverridePctSent = overridePct;
                        }
                        liveState.updateDecision(
                                socPct,
                                "OVERRIDE",
                                null,
                                manualOverridePayload(client, serials, overridePct, nominalPowerW, nameBySerial),
                                batteryPowerW,
                                batteryVoltageV,
                                batteryCurrentA,
                                false,
                                null);
                    } else {
                        lastOverridePctSent = null;
                        try {
                            decisionCycle(
                                    client,
                                    controller,
                                    capacity,
                                    serials,
                                    gridPowerW,
                                    smoother.average(),
                                    liveState,
                                    socPct,
                                    batteryPowerW,
                                    batteryVoltageV,
                                    batteryCurrentA,
                                    dryRun,
                                    config.logging().verboseTraces(),
                                    config.control().minInverterPct(),
                                    nameBySerial);
                        } catch (OpenDTUException e) {
                            LOG.severe("OpenDTU communication failed: " + e.getMessage());
                            applyFailsafe(client, serials, dryRun);
                        }
                    }
                }
            }

            if (now - lastProbeTime >= config.capacityProbe().intervalS()) {
                lastProbeTime = now;
                capacity.probeTick();
            }

            // Hourly energy barely changes at the live cadence -- upserted on
            // its own coarser interval, independently of the per-tick sample
            // recording above. See StatsStore's javadoc.
            if (now - lastStatsWriteTime >= config.stats().intervalS()) {
                lastStatsWriteTime = now;
                statsStore.upsertHourlyEnergy(energyHistory.snapshot());
                statsStore.upsertInverterHourlyEnergy(inverterEnergyHistory.snapshot());
            }
            // recordLatestSample (above) only buffers in memory -- this is
            // what actually writes those buffered samples to disk, batched
            // into one transaction per SAMPLE_FLUSH_INTERVAL_S instead of one
            // per tick. See StatsStore's javadoc for the crash-loss tradeoff.
            if (now - lastSampleFlushTime >= SAMPLE_FLUSH_INTERVAL_S) {
                lastSampleFlushTime = now;
                statsStore.flushBufferedSamples();
            }
            if (now - lastPruneTime >= PRUNE_INTERVAL_S) {
                lastPruneTime = now;
                double highResCutoffEpochSeconds = System.currentTimeMillis() / 1000.0
                        - config.stats().highResRetentionDays() * SECONDS_PER_DAY;
                statsStore.downsampleOlderThan(highResCutoffEpochSeconds, config.stats().intervalS());
                double cutoffEpochSeconds =
                        System.currentTimeMillis() / 1000.0 - config.stats().retentionDays() * SECONDS_PER_DAY;
                statsStore.pruneOlderThan(cutoffEpochSeconds);
            }

            sleepSeconds(config.grid().readIntervalS());
        }
    }

    private static void sleepSeconds(double seconds) {
        try {
            Thread.sleep(Math.round(seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("control loop interrupted", e);
        }
    }
}
