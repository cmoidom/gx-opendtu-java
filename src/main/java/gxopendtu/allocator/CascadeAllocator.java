package gxopendtu.allocator;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * produced clearly less than its limit in {@link #HANDBACK_READINGS}
 * consecutive fresh readings: its limit then isn't what restricts production
 * (low sun, shade), while the inverters before it are needlessly held at
 * their floor. Without that, a big but weakly-producing marginal inverter
 * (e.g. 40W against a 1000W nominal) kept a much more productive one at its
 * floor for minutes, waiting for Victron's slow ramp to reach that nominal
 * power (confirmed live 2026-10-14 17:49).
 *
 * "Fresh readings", not decision ticks: OpenDTU polls the inverters one at a
 * time (one every ~3s, so a given inverter's data is refreshed only every
 * ~18s with 6 of them, longer when RF retries), so most ticks re-read the
 * same, possibly pre-limit-change value. Each reading is therefore judged
 * against the limit that was actually in force when it was taken (its
 * timestamp from OpenDTU's data_age, minus {@link #SETTLE_S} for the inverter
 * to ramp to it), and only counted once.
 *
 * Not thread-safe: owned by one decision loop.
 */
public final class CascadeAllocator {

    /** Consecutive distinct readings of the marginal inverter underusing its limit before handing back. */
    static final int HANDBACK_READINGS = 2;
    /** "Clearly less than its limit": at least this far below it (and 5% of its nominal power for big ones). */
    static final double HANDBACK_MARGIN_W = 50.0;
    /** Time an inverter needs after a limit change before a reading reflects it. */
    static final double SETTLE_S = 5.0;
    private static final double LIMIT_HISTORY_S = 120.0;

    private final List<String> order;
    private final Map<String, Double> nominalPowerW;
    private final double minInverterPct;
    private int marginalIndex;
    // {timeS, limitW} of every limit given to the current marginal inverter, oldest first.
    private final Deque<double[]> marginalLimitHistory = new ArrayDeque<>();
    private Double lastJudgedReadingS;
    private int underuseReadings;

    /** {@code order}: the first inverter is curtailed first, the last one only once all others are at their floor. */
    public CascadeAllocator(List<String> order, Map<String, Double> nominalPowerW, double minInverterPct) {
        this.order = List.copyOf(order);
        this.nominalPowerW = Map.copyOf(nominalPowerW);
        this.minInverterPct = minInverterPct;
    }

    /** Back to "nothing curtailed yet" -- called whenever every inverter was sent to 100% by something else. */
    public void reset() {
        marginalIndex = 0;
        forgetMarginalHistory();
    }

    /** Package-private for tests. */
    int marginalIndex() {
        return marginalIndex;
    }

    /**
     * {@code dataAgeS}: OpenDTU's per-inverter age of {@code livePowerW}
     * (missing = unknown, never used to hand back); {@code nowS}: current
     * epoch seconds.
     */
    public Map<String, Double> allocate(
            double targetW, Map<String, Double> livePowerW, Map<String, Double> dataAgeS, double nowS) {
        Map<String, Double> allocation = new LinkedHashMap<>();
        if (order.isEmpty()) {
            return allocation;
        }
        // One direction per call: with a high floor (e.g. 50% of a big
        // inverter) next to a small one, moving forward then backward could
        // otherwise both be "justified" and loop forever.
        int startIndex = marginalIndex;
        if (marginalUnderusesItsLimit(livePowerW, dataAgeS, nowS)) {
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
            forgetMarginalHistory(); // a new marginal inverter starts its own history
        }
        recordMarginalLimit(nowS, allocation.get(order.get(marginalIndex)));
        return allocation;
    }

    private boolean marginalUnderusesItsLimit(Map<String, Double> livePowerW, Map<String, Double> dataAgeS, double nowS) {
        if (marginalIndex == 0) {
            return false;
        }
        String serial = order.get(marginalIndex);
        Double ageS = dataAgeS.get(serial);
        if (ageS == null) {
            return false;
        }
        double readingS = nowS - ageS;
        if (lastJudgedReadingS != null && Math.abs(readingS - lastJudgedReadingS) < 1.0) {
            return false; // same reading as last time, already counted
        }
        Double limitInForceW = limitInForceAt(readingS - SETTLE_S);
        if (limitInForceW == null) {
            return false; // taken before this inverter had settled on any limit of ours
        }
        lastJudgedReadingS = readingS;
        double margin = Math.max(HANDBACK_MARGIN_W, 0.05 * nominalW(marginalIndex));
        double live = livePowerW.getOrDefault(serial, 0.0);
        underuseReadings = live < limitInForceW - margin ? underuseReadings + 1 : 0;
        return underuseReadings >= HANDBACK_READINGS;
    }

    /** The last limit given to the marginal inverter at or before {@code timeS}, null if none yet. */
    private Double limitInForceAt(double timeS) {
        Double limit = null;
        for (double[] entry : marginalLimitHistory) {
            if (entry[0] > timeS) {
                break;
            }
            limit = entry[1];
        }
        return limit;
    }

    private void recordMarginalLimit(double nowS, double limitW) {
        marginalLimitHistory.addLast(new double[] {nowS, limitW});
        // Keep the newest entry older than the window: it is still the limit in force at its start.
        while (marginalLimitHistory.size() > 1) {
            double[] second = marginalLimitHistory.stream().skip(1).findFirst().orElseThrow();
            if (second[0] > nowS - LIMIT_HISTORY_S) {
                break;
            }
            marginalLimitHistory.removeFirst();
        }
    }

    private void forgetMarginalHistory() {
        marginalLimitHistory.clear();
        lastJudgedReadingS = null;
        underuseReadings = 0;
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
