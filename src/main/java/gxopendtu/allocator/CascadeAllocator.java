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
 * to go above its nominal power -- or, sooner, once the marginal one has
 * produced clearly less than its own limit for {@link #HANDBACK_TICKS}
 * consecutive calls: its limit then isn't what restricts production (low
 * sun, shade), while the inverters before it are needlessly held at their
 * floor. Without that, a big but weakly-producing marginal inverter (e.g.
 * 40W against a 1000W nominal) kept a much more productive one at its floor
 * for minutes, waiting for Victron's slow ramp to reach that nominal power
 * (confirmed live 2026-10-14 17:49). Several calls rather than one, because
 * right after a limit increase the live power read from OpenDTU still lags
 * behind (RF round trip, OpenDTU polling the inverters one at a time).
 *
 * Not thread-safe: owned by one decision loop.
 */
public final class CascadeAllocator {

    private final List<String> order;
    private final Map<String, Double> nominalPowerW;
    private final double minInverterPct;
    private int marginalIndex;
    private Double lastMarginalLimitW;
    private int unusedTicks;

    /** Consecutive calls the marginal inverter must underuse its limit before handing back (~20s at 5s/call). */
    static final int HANDBACK_TICKS = 4;
    /** "Clearly less than its limit": at least this far below it (and 5% of its nominal power for big ones). */
    static final double HANDBACK_MARGIN_W = 50.0;

    /** {@code order}: the first inverter is curtailed first, the last one only once all others are at their floor. */
    public CascadeAllocator(List<String> order, Map<String, Double> nominalPowerW, double minInverterPct) {
        this.order = List.copyOf(order);
        this.nominalPowerW = Map.copyOf(nominalPowerW);
        this.minInverterPct = minInverterPct;
    }

    /** Back to "nothing curtailed yet" -- called whenever every inverter was sent to 100% by something else. */
    public void reset() {
        marginalIndex = 0;
        lastMarginalLimitW = null;
        unusedTicks = 0;
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
        if (marginalUnderusesItsLimit(livePowerW)) {
            marginalIndex--;
        } else {
            while (marginalIndex < order.size() - 1
                    && remainingForMarginal(targetW, livePowerW) < floorW(marginalIndex)) {
                marginalIndex++;
            }
        }
        if (marginalIndex <= startIndex) {
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
        if (marginalIndex != startIndex) {
            unusedTicks = 0; // a new marginal inverter starts its own count
        }
        lastMarginalLimitW = allocation.get(order.get(marginalIndex));
        return allocation;
    }

    /** Counts consecutive underuse of the last limit given to the marginal inverter; true once it has lasted long enough. */
    private boolean marginalUnderusesItsLimit(Map<String, Double> livePowerW) {
        if (marginalIndex == 0 || lastMarginalLimitW == null) {
            unusedTicks = 0;
            return false;
        }
        double margin = Math.max(HANDBACK_MARGIN_W, 0.05 * nominalW(marginalIndex));
        double live = livePowerW.getOrDefault(order.get(marginalIndex), 0.0);
        unusedTicks = live < lastMarginalLimitW - margin ? unusedTicks + 1 : 0;
        if (unusedTicks >= HANDBACK_TICKS) {
            unusedTicks = 0;
            return true;
        }
        return false;
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
