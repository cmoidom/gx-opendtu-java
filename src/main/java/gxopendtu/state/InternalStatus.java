package gxopendtu.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe live view of control-loop internals that don't otherwise
 * surface anywhere -- PI error/integral, the battery-discharge floor's
 * before/after values, per-inverter capacity ceilings, the battery-full
 * hysteresis's sustained-export streak, overrides -- purely for the
 * /internal debug page.
 *
 * Never read by the control loop itself, and has no effect on any decision
 * -- it exists only to make bugs like the 2026-07-13 sur-export incident
 * (a silent battery-discharge floor fighting the PI) visible from the
 * dashboard instead of requiring a source read plus manual log
 * reconstruction. Lost on every service restart, like LiveState -- this is
 * a live introspection view, not a historian.
 */
public final class InternalStatus {

    // ~30 min of history at the default control.decision_interval_s=5s --
    // enough to see a PI integral windup develop, not meant for long-term analysis.
    public static final int DEFAULT_MAX_SAMPLES = 360;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();
    private final int maxSamples;
    private final Map<String, Object> latest = new LinkedHashMap<>();

    public InternalStatus() {
        this(DEFAULT_MAX_SAMPLES);
    }

    public InternalStatus(int maxSamples) {
        this.maxSamples = maxSamples;
    }

    /**
     * Called once per decision cycle, only while the PI actually ran (the
     * normal ON branch, no manual override) -- OFF/OVERRIDE cycles never
     * touch the PI, so there's nothing meaningful to report here for those;
     * {@link #updateMode} covers every branch instead. Also appends to the
     * sparkline history, so the frequency of calls here doubles as the
     * history's sampling rate.
     */
    public void updateControl(
            double error,
            double piIntegral,
            double rawTargetBeforeFloor,
            double rawTargetAfterFloor,
            boolean batteryFloorEngaged,
            double batteryDischargeW,
            double stepW,
            double quantizedW,
            boolean changed,
            double targetW,
            Map<String, Double> ceilingsW,
            Map<String, Double> nominalPowerW,
            Map<String, Double> dataAgeS,
            boolean minInverterFloorWarning,
            Double recommendedMinInverterPct) {
        lock.lock();
        try {
            latest.put("error", error);
            latest.put("pi_integral", piIntegral);
            latest.put("raw_target_before_floor", rawTargetBeforeFloor);
            latest.put("raw_target_after_floor", rawTargetAfterFloor);
            latest.put("battery_floor_engaged", batteryFloorEngaged);
            latest.put("battery_discharge_w", batteryDischargeW);
            latest.put("step_w", stepW);
            latest.put("quantized_w", quantizedW);
            latest.put("changed", changed);
            latest.put("target_w", targetW);
            latest.put("ceilings_w", ceilingsW == null ? Map.of() : Map.copyOf(ceilingsW));
            latest.put("nominal_power_w", nominalPowerW == null ? Map.of() : Map.copyOf(nominalPowerW));
            latest.put("data_age_s", dataAgeS == null ? Map.of() : Map.copyOf(dataAgeS));
            latest.put("min_inverter_floor_warning", minInverterFloorWarning);
            latest.put("recommended_min_inverter_pct", recommendedMinInverterPct);

            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("t", System.currentTimeMillis() / 1000.0);
            sample.put("error", error);
            sample.put("pi_integral", piIntegral);
            sample.put("raw_target_before_floor", rawTargetBeforeFloor);
            sample.put("raw_target_after_floor", rawTargetAfterFloor);
            sample.put("battery_discharge_w", batteryDischargeW);

            if (history.size() >= maxSamples) {
                history.removeFirst();
            }
            history.addLast(Collections.unmodifiableMap(sample));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called once per decision cycle regardless of branch (ON/OFF/OVERRIDE)
     * -- unlike {@link #updateControl}, this reflects state that stays
     * meaningful no matter what the loop is currently doing.
     */
    public void updateMode(
            boolean hysteresisActive,
            Double exportStreakElapsedS,
            String injectionModeOverride,
            Map<String, Object> manualOverride,
            int consecutiveGridFailures,
            boolean releasedForCharging) {
        lock.lock();
        try {
            latest.put("hysteresis_active", hysteresisActive);
            latest.put("export_streak_elapsed_s", exportStreakElapsedS);
            latest.put("injection_mode_override", injectionModeOverride);
            latest.put("manual_override", manualOverride);
            latest.put("consecutive_grid_failures", consecutiveGridFailures);
            latest.put("released_for_charging", releasedForCharging);
        } finally {
            lock.unlock();
        }
    }

    /** History strictly newer than {@code since} (epoch seconds), plus the latest full state regardless. */
    public Snapshot snapshotSince(double since) {
        lock.lock();
        try {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> sample : history) {
                double t = (Double) sample.get("t");
                if (t > since) {
                    filtered.add(sample);
                }
            }
            return new Snapshot(new LinkedHashMap<>(latest), filtered);
        } finally {
            lock.unlock();
        }
    }

    public record Snapshot(Map<String, Object> latest, List<Map<String, Object>> history) {}
}
