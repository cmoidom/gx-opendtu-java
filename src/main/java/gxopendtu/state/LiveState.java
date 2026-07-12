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
 * Thread-safe in-memory ring buffer of recent control-loop samples.
 *
 * Consumed by the live dashboard to draw near-real-time charts (grid power,
 * SOC, per-inverter power) without a database and without touching the
 * control loop's own scheduling. Written by the control loop every fast-loop
 * tick; read by the webui HTTP server's request threads.
 *
 * Lost on every service restart -- this is a live view, not a historian.
 * Port of src/live_state.py's LiveState.
 */
public final class LiveState {

    public static final int DEFAULT_MAX_SAMPLES = 900; // ~30 min of history at the default grid.read_interval_s=2s

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();
    private final int maxSamples;

    private Double socPct;
    private Double batteryPowerW;
    private Double batteryVoltageV;
    private Double batteryCurrentA;
    private String injectionControl;
    private Double consigneW;
    private List<Map<String, Object>> inverters = List.of();
    private boolean minInverterFloorWarning;
    private Double recommendedMinInverterPct;

    public LiveState() {
        this(DEFAULT_MAX_SAMPLES);
    }

    public LiveState(int maxSamples) {
        this.maxSamples = maxSamples;
    }

    /** Called once per decision cycle -- carried forward into every grid sample recorded until the next one. */
    public void updateDecision(
            Double socPct,
            String injectionControl,
            Double consigneW,
            List<Map<String, Object>> inverters,
            Double batteryPowerW,
            Double batteryVoltageV,
            Double batteryCurrentA,
            boolean minInverterFloorWarning,
            Double recommendedMinInverterPct) {
        lock.lock();
        try {
            this.socPct = socPct;
            this.batteryPowerW = batteryPowerW;
            this.batteryVoltageV = batteryVoltageV;
            this.batteryCurrentA = batteryCurrentA;
            this.injectionControl = injectionControl;
            this.consigneW = consigneW;
            this.inverters = inverters == null ? List.of() : List.copyOf(inverters);
            this.minInverterFloorWarning = minInverterFloorWarning;
            this.recommendedMinInverterPct = recommendedMinInverterPct;
        } finally {
            lock.unlock();
        }
    }

    public void updateDecision(Double socPct, String injectionControl, Double consigneW, List<Map<String, Object>> inverters) {
        updateDecision(socPct, injectionControl, consigneW, inverters, null, null, null, false, null);
    }

    /**
     * Repopulates the history buffer from persisted long-term samples (see
     * {@code stats.StatsStore#loadRecentSamples}), called once at startup
     * before the control loop's first tick -- otherwise the dashboard shows
     * completely empty charts for a while after every restart, since this
     * buffer is normally rebuilt live from scratch. {@code samples} must
     * already be in chronological order (oldest first).
     */
    @SuppressWarnings("unchecked")
    public void seedHistory(List<Map<String, Object>> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            history.clear();
            for (Map<String, Object> sample : samples) {
                if (history.size() >= maxSamples) {
                    history.removeFirst();
                }
                history.addLast(Collections.unmodifiableMap(sample));
            }
            Map<String, Object> last = samples.get(samples.size() - 1);
            this.socPct = (Double) last.get("soc_pct");
            this.batteryPowerW = (Double) last.get("battery_power_w");
            this.batteryVoltageV = (Double) last.get("battery_voltage_v");
            this.batteryCurrentA = (Double) last.get("battery_current_a");
            this.injectionControl = (String) last.get("injection_control");
            this.consigneW = (Double) last.get("consigne_w");
            Object lastInverters = last.get("inverters");
            this.inverters = lastInverters instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
            this.minInverterFloorWarning = Boolean.TRUE.equals(last.get("min_inverter_floor_warning"));
            this.recommendedMinInverterPct = (Double) last.get("recommended_min_inverter_pct");
        } finally {
            lock.unlock();
        }
    }

    /** Called once per fast-loop tick -- this sets the sampling rate of the history buffer. */
    public void recordGrid(double gridRawW, double gridEmaW) {
        lock.lock();
        try {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("t", System.currentTimeMillis() / 1000.0);
            sample.put("grid_raw_w", gridRawW);
            sample.put("grid_ema_w", gridEmaW);
            sample.put("soc_pct", socPct);
            sample.put("battery_power_w", batteryPowerW);
            sample.put("battery_voltage_v", batteryVoltageV);
            sample.put("battery_current_a", batteryCurrentA);
            sample.put("injection_control", injectionControl);
            sample.put("consigne_w", consigneW);
            sample.put("inverters", inverters);
            sample.put("min_inverter_floor_warning", minInverterFloorWarning);
            sample.put("recommended_min_inverter_pct", recommendedMinInverterPct);

            if (history.size() >= maxSamples) {
                history.removeFirst();
            }
            history.addLast(Collections.unmodifiableMap(sample));
        } finally {
            lock.unlock();
        }
    }

    /**
     * History strictly newer than {@code since} (epoch seconds), plus the
     * latest sample regardless -- lets a client poll incrementally after an
     * initial full fetch (since=0).
     */
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
            Map<String, Object> latest = history.isEmpty() ? null : history.peekLast();
            return new Snapshot(latest, filtered);
        } finally {
            lock.unlock();
        }
    }

    public record Snapshot(Map<String, Object> latest, List<Map<String, Object>> history) {}
}
