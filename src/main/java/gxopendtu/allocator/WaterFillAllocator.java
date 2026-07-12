package gxopendtu.allocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Water-filling distribution of a total power target across several inverters.
 *
 * Splits the total equally, but caps any inverter at its known capacity
 * ceiling (nominal power, or a lower value if it's currently irradiance-
 * limited) and redistributes the remainder equally among the inverters that
 * still have room.
 *
 * Port of src/allocator.py (water_fill_allocate). Pure, no I/O.
 */
public final class WaterFillAllocator {

    private static final double INFINITE = Double.POSITIVE_INFINITY;

    private WaterFillAllocator() {}

    public static Map<String, Double> waterFillAllocate(
            double totalTargetW, List<String> serials, Map<String, Double> capacityEstimates) {
        return waterFillAllocate(totalTargetW, serials, capacityEstimates, 0.0, null);
    }

    public static Map<String, Double> waterFillAllocate(
            double totalTargetW,
            List<String> serials,
            Map<String, Double> capacityEstimates,
            double minInverterPct,
            Map<String, Double> nominalPowerW) {
        List<String> active = new ArrayList<>(serials);
        double remaining = Math.max(0.0, totalTargetW);
        Map<String, Double> allocation = new LinkedHashMap<>();

        while (!active.isEmpty()) {
            double share = remaining / active.size();
            List<String> saturated = active.stream()
                    .filter(s -> capacityEstimates.getOrDefault(s, INFINITE) <= share)
                    .toList();
            if (saturated.isEmpty()) {
                for (String s : active) {
                    allocation.put(s, share);
                }
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

        // Global floor (config.control.min_inverter_pct, % of each inverter's own
        // nominal power): never ask an inverter with real producible capacity to
        // go below this. Applied even when the water-filled share is exactly 0.
        // Clamped to the inverter's own capacity ceiling, so an inverter with no
        // real capacity right now (capacityEstimates == 0) is never floored above
        // 0 -- fail-safe and the battery-charge-priority release don't go
        // through this method at all, so there's no other "genuine zero" to protect.
        if (minInverterPct > 0 && nominalPowerW != null && !nominalPowerW.isEmpty()) {
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
