package gxopendtu.control;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks a per-inverter capacity ceiling used by the water-filling allocator.
 *
 * Starts at each inverter's nominal power. If an inverter is unable to reach
 * its allocated share while OpenDTU reports its limit as acknowledged (not
 * still-limiting), it's assumed to be irradiance-limited, and its ceiling is
 * lowered to its actual measured output. A slow periodic probe nudges the
 * ceiling back up so a passing cloud doesn't permanently cap the inverter.
 *
 * Only treats underperformance as evidence of a genuine capacity limit when
 * the allocated share was already close to the current ceiling (>=
 * {@link #NEAR_CEILING_RATIO} of it) -- the zero-export target is often well
 * below any inverter's max on purpose (just enough to cover load without
 * exporting), so a small shortfall against a much lower allocated share
 * proves nothing about true capacity. Without this guard, ordinary
 * measurement noise ratchets the ceiling down under full sun too (probe_tick
 * recovery is comparatively slow, so a false positive here is costly).
 *
 * Port of src/controller.py's CapacityEstimator.
 */
public final class CapacityEstimator {

    private static final double NEAR_CEILING_RATIO = 0.9;

    private final Map<String, Double> nominalPowerW;
    private final double probeStepW;
    private final Map<String, Double> ceilingsW;

    public CapacityEstimator(Map<String, Double> nominalPowerW, double probeStepW) {
        this.nominalPowerW = new HashMap<>(nominalPowerW);
        this.probeStepW = probeStepW;
        this.ceilingsW = new HashMap<>(nominalPowerW);
    }

    /** Live mutable ceiling map, read by the allocator each decision cycle. */
    public Map<String, Double> ceilingsW() {
        return ceilingsW;
    }

    public Map<String, Double> nominalPowerW() {
        return nominalPowerW;
    }

    public void observe(String serial, double allocatedW, double actualW, boolean limitAcknowledged) {
        double nominal = nominalPowerW.getOrDefault(serial, actualW);
        double currentCeiling = ceilingsW.getOrDefault(serial, nominal);
        boolean wasNearCeiling = allocatedW >= NEAR_CEILING_RATIO * currentCeiling;
        if (limitAcknowledged && wasNearCeiling && actualW < allocatedW - 1e-6) {
            ceilingsW.put(serial, Math.max(0.0, actualW));
        } else {
            ceilingsW.put(serial, Math.min(nominal, currentCeiling));
        }
    }

    public void probeTick() {
        for (Map.Entry<String, Double> entry : nominalPowerW.entrySet()) {
            String serial = entry.getKey();
            double nominal = entry.getValue();
            double current = ceilingsW.getOrDefault(serial, nominal);
            ceilingsW.put(serial, Math.min(nominal, current + probeStepW));
        }
    }
}
