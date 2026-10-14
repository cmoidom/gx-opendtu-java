package gxopendtu.allocator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curtails inverters one at a time, in a fixed order, instead of spreading a
 * restricted target across all of them (2026-10-14, replaces water-fill in
 * the SunSpec forwarder): every inverter before the "marginal" one sits at
 * its floor, every inverter after it stays at its nominal power (uncapped),
 * and only the marginal one is adjusted to hit the target. A change in the
 * target therefore moves a single inverter's limit -- one RF command instead
 * of one per inverter, and no shuffling of power between inverters.
 *
 * The inverters after the marginal one are uncapped, so their live power is
 * their real production potential (shade, clouds) -- that, not their
 * nameplate, is what is subtracted from the target.
 *
 * Switching hysteresis comes from the floor/nominal gap itself: the next
 * inverter only takes over once the marginal one would have to go below its
 * floor, and the previous one only resumes once the marginal one would have
 * to go above its nominal power. When the marginal inverter produces less
 * than its limit (low sun), Victron's own zero-feed-in loop keeps raising
 * the target until that happens, so this needs no estimate of real capacity.
 *
 * Not thread-safe: owned by one decision loop.
 */
public final class CascadeAllocator {

    private final List<String> order;
    private final Map<String, Double> nominalPowerW;
    private final double minInverterPct;
    private int marginalIndex;

    /** {@code order}: the first inverter is curtailed first, the last one only once all others are at their floor. */
    public CascadeAllocator(List<String> order, Map<String, Double> nominalPowerW, double minInverterPct) {
        this.order = List.copyOf(order);
        this.nominalPowerW = Map.copyOf(nominalPowerW);
        this.minInverterPct = minInverterPct;
    }

    /** Back to "nothing curtailed yet" -- called whenever every inverter was sent to 100% by something else. */
    public void reset() {
        marginalIndex = 0;
    }

    /** Package-private for tests. */
    int marginalIndex() {
        return marginalIndex;
    }

    public Map<String, Double> allocate(double targetW, Map<String, Double> livePowerW) {
        Map<String, Double> allocation = new LinkedHashMap<>();
        if (order.isEmpty()) {
            return allocation;
        }
        // One direction per call: with a high floor (e.g. 50% of a big
        // inverter) next to a small one, moving forward then backward could
        // otherwise both be "justified" and loop forever.
        int startIndex = marginalIndex;
        while (marginalIndex < order.size() - 1
                && remainingForMarginal(targetW, livePowerW) < floorW(marginalIndex)) {
            marginalIndex++;
        }
        if (marginalIndex == startIndex) {
            while (marginalIndex > 0 && remainingForMarginal(targetW, livePowerW) >= nominalW(marginalIndex)) {
                marginalIndex--;
            }
        }
        double rest = remainingForMarginal(targetW, livePowerW);
        for (int i = 0; i < order.size(); i++) {
            double watts;
            if (i < marginalIndex) {
                watts = floorW(i);
            } else if (i > marginalIndex) {
                watts = nominalW(i);
            } else {
                watts = Math.max(floorW(i), Math.min(nominalW(i), rest));
            }
            allocation.put(order.get(i), watts);
        }
        return allocation;
    }

    private double remainingForMarginal(double targetW, Map<String, Double> livePowerW) {
        double rest = targetW;
        for (int i = 0; i < marginalIndex; i++) {
            rest -= floorW(i);
        }
        for (int i = marginalIndex + 1; i < order.size(); i++) {
            rest -= livePowerW.getOrDefault(order.get(i), 0.0);
        }
        return rest;
    }

    private double nominalW(int index) {
        return nominalPowerW.getOrDefault(order.get(index), 0.0);
    }

    private double floorW(int index) {
        return minInverterPct / 100.0 * nominalW(index);
    }
}
