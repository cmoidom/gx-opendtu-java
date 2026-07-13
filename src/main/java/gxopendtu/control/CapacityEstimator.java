package gxopendtu.control;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks a per-inverter capacity ceiling used by the water-filling allocator.
 *
 * Starts at each inverter's nominal power. If an inverter is unable to reach
 * its allocated share, its ceiling is lowered to its actual measured output
 * so the water-filling allocator redirects the shortfall to other inverters
 * with headroom. A slow periodic probe nudges the ceiling back up so a
 * passing cloud doesn't permanently cap the inverter.
 *
 * Only treats underperformance as evidence of a genuine capacity limit once
 * it's both significant ({@code actualW} below {@link #SHORTFALL_RATIO} of
 * {@code allocatedW}) and sustained across {@link #PERSISTENCE_CYCLES}
 * genuinely-evaluated observations (2026-07-13, replacing an earlier
 * "allocation must already be near the ceiling" guard) -- a single
 * instantaneous shortfall proves nothing (measurement noise, a cloud
 * passing, the inverter still ramping toward a just-changed limit), but
 * requiring proximity to the ceiling missed a real case: an inverter asked
 * for only e.g. 50% of its nominal power that genuinely can't reach even
 * that (shading, panel orientation) never got detected because 50% is far
 * from "near the ceiling". Requiring persistence instead of ceiling
 * proximity catches that case without reintroducing the noise problem the
 * old guard existed to prevent.
 *
 * Also ignores (neither advances nor resets the streak) any observation
 * whose {@code dataAgeS} exceeds {@link #STALE_DATA_AGE_S}: OpenDTU polls
 * inverters one at a time over a single RF module, so with several
 * inverters configured, a given one's cached telemetry can still be the
 * same stale sample several decision cycles in a row -- counting the same
 * underlying RF reading multiple times toward the persistence requirement
 * would defeat the point of requiring independent observations.
 *
 * Port of src/controller.py's CapacityEstimator (persistence/staleness
 * handling has no Python equivalent -- added directly in this port).
 */
public final class CapacityEstimator {

    private static final double SHORTFALL_RATIO = 0.7;
    private static final int PERSISTENCE_CYCLES = 3;
    private static final double STALE_DATA_AGE_S = 60.0;

    private final Map<String, Double> nominalPowerW;
    private final double probeStepW;
    private final Map<String, Double> ceilingsW;
    private final Map<String, Integer> shortfallStreak = new HashMap<>();

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

    /** Convenience for callers/tests with no data-age signal -- always treated as fresh (dataAgeS=0). */
    public void observe(String serial, double allocatedW, double actualW, boolean limitAcknowledged) {
        observe(serial, allocatedW, actualW, limitAcknowledged, 0.0);
    }

    public void observe(String serial, double allocatedW, double actualW, boolean limitAcknowledged, double dataAgeS) {
        double nominal = nominalPowerW.getOrDefault(serial, actualW);
        double currentCeiling = ceilingsW.getOrDefault(serial, nominal);

        if (!limitAcknowledged || dataAgeS > STALE_DATA_AGE_S) {
            // No reliable signal this cycle (limit not yet applied, or the
            // telemetry predates it) -- hold the ceiling steady and don't
            // touch the streak either way; this is "no information", not
            // "proof the inverter is fine".
            ceilingsW.put(serial, Math.min(nominal, currentCeiling));
            return;
        }

        boolean underperforming = actualW < SHORTFALL_RATIO * allocatedW - 1e-6;
        if (!underperforming) {
            shortfallStreak.put(serial, 0);
            ceilingsW.put(serial, Math.min(nominal, currentCeiling));
            return;
        }

        int streak = shortfallStreak.merge(serial, 1, Integer::sum);
        if (streak >= PERSISTENCE_CYCLES) {
            ceilingsW.put(serial, Math.max(0.0, actualW));
            shortfallStreak.put(serial, 0);
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
