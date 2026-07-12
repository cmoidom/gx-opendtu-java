package gxopendtu.allocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Water-filling distribution of a total power target across several inverters.
 *
 * Equalizes by **percentage of each inverter's own nominal power**, not equal
 * absolute watts -- so reducing the total curtails the inverter currently
 * producing the highest % of its own rating first (and, symmetrically,
 * raising the total favours whichever is producing the lowest % first),
 * converging every inverter toward the same percentage. An inverter unable to
 * reach that common percentage (irradiance-limited) is capped at its actual
 * capacity, and the shortfall is redistributed among the rest by recomputing
 * a new common percentage over them.
 *
 * Explicit user requirement: a bigger inverter shouldn't be left producing
 * more absolute watts than a smaller one just because an equal-watts split
 * doesn't account for their different ratings -- curtailment/relief should
 * track how "maxed out" each inverter already is relative to itself.
 *
 * Port of src/allocator.py (water_fill_allocate). Pure, no I/O.
 */
public final class WaterFillAllocator {

    private static final double INFINITE = Double.POSITIVE_INFINITY;

    private WaterFillAllocator() {}

    public static Map<String, Double> waterFillAllocate(
            double totalTargetW, List<String> serials, Map<String, Double> capacityEstimates, Map<String, Double> nominalPowerW) {
        return waterFillAllocate(totalTargetW, serials, capacityEstimates, nominalPowerW, 0.0);
    }

    public static Map<String, Double> waterFillAllocate(
            double totalTargetW,
            List<String> serials,
            Map<String, Double> capacityEstimates,
            Map<String, Double> nominalPowerW,
            double minInverterPct) {
        List<String> active = new ArrayList<>(serials);
        double remaining = Math.max(0.0, totalTargetW);
        Map<String, Double> allocation = new LinkedHashMap<>();

        while (!active.isEmpty()) {
            double totalNominalActive = active.stream().mapToDouble(s -> nominalPowerW.getOrDefault(s, 0.0)).sum();
            Map<String, Double> shares = new LinkedHashMap<>();
            if (totalNominalActive > 0) {
                double sharePct = remaining / totalNominalActive;
                for (String s : active) {
                    shares.put(s, sharePct * nominalPowerW.getOrDefault(s, 0.0));
                }
            } else {
                // No nominal-power data for any remaining inverter -- fall back
                // to an equal-watts split so this still terminates sensibly
                // rather than dividing by zero.
                double equalShare = remaining / active.size();
                for (String s : active) {
                    shares.put(s, equalShare);
                }
            }

            List<String> saturated =
                    active.stream().filter(s -> capacityEstimates.getOrDefault(s, INFINITE) <= shares.get(s)).toList();
            if (saturated.isEmpty()) {
                allocation.putAll(shares);
                break;
            }
            for (String s : saturated) {
                double cap = Math.max(0.0, capacityEstimates.getOrDefault(s, INFINITE));
                allocation.put(s, cap);
                remaining -= cap;
                active.remove(s);
            }
            remaining = Math.max(0.0, remaining);
        }

        // Global floor (config.control.minInverterPct, % of each inverter's own
        // nominal power): never ask an inverter with real producible capacity to
        // go below this. Applied even when the water-filled share is exactly 0.
        // Clamped to the inverter's own capacity ceiling, so an inverter with no
        // real capacity right now (capacityEstimates == 0) is never floored above
        // 0 -- fail-safe and the battery-charge-priority release don't go
        // through this method at all, so there's no other "genuine zero" to protect.
        if (minInverterPct > 0) {
            for (String s : allocation.keySet()) {
                double cap = capacityEstimates.getOrDefault(s, INFINITE);
                if (cap <= 0) {
                    continue;
                }
                double floorW = minInverterPct / 100.0 * nominalPowerW.getOrDefault(s, 0.0);
                if (floorW > allocation.get(s)) {
                    allocation.put(s, Math.min(cap, floorW));
                }
            }
        }

        return allocation;
    }
}
