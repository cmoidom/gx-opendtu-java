package gxopendtu.control;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks a per-inverter capacity ceiling used by the water-filling allocator.
 *
 * Starts at each inverter's nominal power. If an inverter is unable to reach
 * its allocated share, its ceiling is lowered to its actual measured output
 * so the water-filling allocator redirects the shortfall to other inverters
 * with headroom. A periodic probe nudges the ceiling back up so a passing
 * cloud (or dusk fading into dawn) doesn't permanently cap the inverter.
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
 * <p><b>Recovery speed (2026-07-13).</b> The plain per-tick nudge
 * ({@code probeStepW}, default 50W/30s) alone would still take a while to
 * recover a deeply-lowered ceiling -- too slow if the sun genuinely returns (a cloud clears,
 * dawn) and a real consumption spike needs covering right away: the
 * water-filling allocator can never exceed the tracked ceiling, so solar
 * stays artificially capped and the battery/grid covers the gap instead,
 * defeating "solar before battery". When an inverter is saturated (asked
 * for its full ceiling) and still keeping up with that ask (not
 * underperforming), {@link #probeTick()} jumps its ceiling straight to
 * nominal instead of nudging -- optimistic, but safe: if the sun hasn't
 * actually returned, the very next observations will underperform and the
 * persistence check above corrects it back down within
 * {@code PERSISTENCE_CYCLES} decision cycles (a few seconds to under a
 * minute), not tens of minutes. To avoid repeating that optimistic-then-
 * corrected cycle every single probe tick during a stretch that's genuinely
 * capacity-limited for a while (dusk, not a passing cloud) -- which would
 * otherwise re-flare every {@code probeStepW}-tick indefinitely -- a failed
 * jump imposes {@link #OPTIMISTIC_BACKOFF_TICKS} plain-nudge ticks before
 * another jump is attempted on that inverter.
 *
 * Port of src/controller.py's CapacityEstimator (persistence/staleness/
 * optimistic-recovery handling has no Python equivalent -- added directly
 * in this port).
 */
public final class CapacityEstimator {

    private static final double SHORTFALL_RATIO = 0.7;
    private static final int PERSISTENCE_CYCLES = 3;
    private static final double STALE_DATA_AGE_S = 60.0;
    private static final int OPTIMISTIC_BACKOFF_TICKS = 3;

    private final Map<String, Double> nominalPowerW;
    private final double probeStepW;
    private final Map<String, Double> ceilingsW;
    private final Map<String, Integer> shortfallStreak = new HashMap<>();
    /** Last observe() outcome per serial: saturated at its ceiling and not underperforming -- a probeTick candidate for the optimistic jump. */
    private final Map<String, Boolean> saturatedNotUnderperforming = new HashMap<>();
    /** Set by observe() when a persistence-triggered decrease fires; read and cleared by the next probeTick to detect a failed optimistic jump. */
    private final Map<String, Boolean> decreasedSinceLastProbe = new HashMap<>();
    /** Remaining probeTicks to skip the optimistic jump on, after one of its attempts failed. */
    private final Map<String, Integer> optimisticBackoffTicks = new HashMap<>();

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
            saturatedNotUnderperforming.put(serial, allocatedW >= currentCeiling - 1e-6);
            ceilingsW.put(serial, Math.min(nominal, currentCeiling));
            return;
        }

        saturatedNotUnderperforming.put(serial, false);
        int streak = shortfallStreak.merge(serial, 1, Integer::sum);
        if (streak >= PERSISTENCE_CYCLES) {
            ceilingsW.put(serial, Math.max(0.0, actualW));
            shortfallStreak.put(serial, 0);
            decreasedSinceLastProbe.put(serial, true);
        } else {
            ceilingsW.put(serial, Math.min(nominal, currentCeiling));
        }
    }

    public void probeTick() {
        for (Map.Entry<String, Double> entry : nominalPowerW.entrySet()) {
            String serial = entry.getKey();
            double nominal = entry.getValue();
            double current = ceilingsW.getOrDefault(serial, nominal);

            boolean failedSinceLastProbe = decreasedSinceLastProbe.getOrDefault(serial, false);
            decreasedSinceLastProbe.put(serial, false);
            int backoff = optimisticBackoffTicks.getOrDefault(serial, 0);

            if (failedSinceLastProbe) {
                // The last optimistic jump (if any) was wrong -- don't retry
                // immediately, or a still capacity-limited inverter would
                // re-flare every single tick indefinitely.
                optimisticBackoffTicks.put(serial, OPTIMISTIC_BACKOFF_TICKS);
                ceilingsW.put(serial, Math.min(nominal, current + probeStepW));
            } else if (backoff > 0) {
                optimisticBackoffTicks.put(serial, backoff - 1);
                ceilingsW.put(serial, Math.min(nominal, current + probeStepW));
            } else if (Boolean.TRUE.equals(saturatedNotUnderperforming.get(serial))) {
                ceilingsW.put(serial, nominal);
            } else {
                ceilingsW.put(serial, Math.min(nominal, current + probeStepW));
            }
        }
    }
}
