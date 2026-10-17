package gxopendtu.allocator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 * <p>Switching: the next inverter takes over once the marginal one would have
 * to go below its floor (several steps at once if needed -- curtailing fast
 * is the safe direction); the previous one resumes, one step per call, once
 * the marginal one would have to go above its nominal power, or once it has
 * produced clearly less than its limit in {@link #HANDBACK_READINGS}
 * consecutive fresh readings (its limit then isn't what restricts production
 * -- low sun, shade -- while the inverters before it are needlessly held at
 * their floor; confirmed live 2026-10-14 17:49).
 *
 * <p>Readings: OpenDTU polls the inverters one at a time (~3s each, so a given
 * inverter's data is ~18s old at worst with 6 of them, more on RF retries).
 * Each reading is dated with OpenDTU's data_age and judged against the limit
 * that was actually in force when it was taken ({@link #SETTLE_S} earlier,
 * for the inverter to ramp to it), and only counted once. The production of
 * an uncapped inverter -- subtracted from the target -- is its reading only
 * when that reading shows its real potential (taken while uncapped, or while
 * clearly below its limit); a reading taken while it was still held down
 * (e.g. right after leaving its floor) says nothing about what it will
 * produce now, so its nominal power is assumed instead. Using the stale low
 * reading made the cascade hand far too much to the marginal inverter, then
 * export, then Victron slashing its cap, about once a minute (confirmed live
 * 2026-10-17 16:40-17:30).
 *
 * <p>An inverter whose data is older than {@link #STALE_DATA_S} (RF link lost:
 * Mur Ouest 2026-10-17 16:32-16:40 kept producing 677W while the cascade
 * believed it at its floor, -600W export for 7 min) is taken out of the
 * cascade: its last known production is subtracted like a non-controllable
 * inverter's, and it keeps its last limit until it answers again.
 *
 * <p>Not thread-safe: owned by one decision loop.
 */
public final class CascadeAllocator {

    /** Consecutive distinct readings of the marginal inverter underusing its limit before handing back. */
    static final int HANDBACK_READINGS = 2;
    /** "Clearly less than its limit": at least this far below it (and 5% of its nominal power for big ones). */
    static final double HANDBACK_MARGIN_W = 50.0;
    /** Time an inverter needs after a limit change before a reading reflects it. */
    static final double SETTLE_S = 5.0;
    /** Data older than this means OpenDTU has lost the inverter (normal worst case is ~20s). */
    static final double STALE_DATA_S = 60.0;
    private static final double LIMIT_HISTORY_S = 180.0;

    private final List<String> order;
    private final Map<String, Double> nominalPowerW;
    private final double minInverterPct;
    private String marginalSerial;
    // serial -> {timeS, limitW} of every limit given to it, oldest first.
    private final Map<String, Deque<double[]>> limitHistory = new HashMap<>();
    private final Map<String, Double> lastAllocationW = new HashMap<>();
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
        marginalSerial = null;
        limitHistory.clear();
        lastAllocationW.clear();
        forgetUnderuse();
    }

    /** Package-private for tests: position of the marginal inverter in the full order. */
    int marginalIndex() {
        return marginalSerial == null ? 0 : order.indexOf(marginalSerial);
    }

    /**
     * {@code dataAgeS}: OpenDTU's per-inverter age of {@code livePowerW}
     * (missing = unknown: the reading is taken as current and the inverter as
     * reachable); {@code nowS}: current epoch seconds.
     */
    public Map<String, Double> allocate(
            double targetW, Map<String, Double> livePowerW, Map<String, Double> dataAgeS, double nowS) {
        Map<String, Double> allocation = new LinkedHashMap<>();
        if (order.isEmpty()) {
            return allocation;
        }
        List<String> active = new ArrayList<>();
        double unreachableW = 0.0;
        for (String serial : order) {
            Double ageS = dataAgeS.get(serial);
            if (ageS != null && ageS > STALE_DATA_S) {
                unreachableW += livePowerW.getOrDefault(serial, 0.0);
            } else {
                active.add(serial);
            }
        }
        Map<String, Double> potentialW = new HashMap<>();
        for (String serial : active) {
            potentialW.put(serial, productionPotentialW(serial, livePowerW, dataAgeS, nowS));
        }

        int idx = marginalIndexIn(active);
        String startMarginal = active.isEmpty() ? null : active.get(idx);
        double effectiveTargetW = targetW - unreachableW;
        if (!active.isEmpty()) {
            if (idx > 0 && marginalUnderusesItsLimit(active.get(idx), livePowerW, dataAgeS, nowS)) {
                idx--;
            } else {
                int before = idx;
                while (idx < active.size() - 1
                        && remainingW(effectiveTargetW, active, idx, potentialW) < floorW(active.get(idx))) {
                    idx++;
                }
                // Releasing goes one inverter per call: the released one's
                // real production only becomes known on its next reading.
                if (idx == before && idx > 0
                        && remainingW(effectiveTargetW, active, idx, potentialW) >= nominalW(active.get(idx))) {
                    idx--;
                }
            }
            double rest = remainingW(effectiveTargetW, active, idx, potentialW);
            for (int i = 0; i < active.size(); i++) {
                String serial = active.get(i);
                double watts;
                if (i < idx) {
                    watts = floorW(serial);
                } else if (i > idx) {
                    watts = nominalW(serial);
                } else {
                    watts = Math.max(floorW(serial), Math.min(nominalW(serial), rest));
                }
                allocation.put(serial, watts);
            }
            marginalSerial = active.get(idx);
        }
        for (String serial : order) {
            if (!allocation.containsKey(serial)) {
                // Unreachable: keeps whatever limit it last had.
                allocation.put(serial, lastAllocationW.getOrDefault(serial, nominalW(serial)));
            }
        }
        if (marginalSerial != null && !marginalSerial.equals(startMarginal)) {
            forgetUnderuse(); // a new marginal inverter starts its own count
        }
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (String serial : order) {
            double watts = allocation.get(serial);
            ordered.put(serial, watts);
            recordLimit(serial, nowS, watts);
            lastAllocationW.put(serial, watts);
        }
        return ordered;
    }

    /** Index in {@code active} of the marginal inverter, or of the first active one after it if it was taken out. */
    private int marginalIndexIn(List<String> active) {
        if (active.isEmpty() || marginalSerial == null) {
            return 0;
        }
        int position = order.indexOf(marginalSerial);
        int idx = 0;
        for (String serial : active) {
            if (order.indexOf(serial) >= position) {
                break;
            }
            idx++;
        }
        return Math.min(idx, active.size() - 1);
    }

    /**
     * What an inverter would produce uncapped: its reading when that reading
     * shows it (taken while uncapped, or clearly below the limit then in
     * force), otherwise its nominal power -- the cautious side, since
     * overestimating it only curtails the marginal inverter a bit more.
     */
    private double productionPotentialW(
            String serial, Map<String, Double> livePowerW, Map<String, Double> dataAgeS, double nowS) {
        double live = livePowerW.getOrDefault(serial, 0.0);
        Double ageS = dataAgeS.get(serial);
        Double limitThenW = limitInForceAt(serial, nowS - (ageS != null ? ageS : 0.0) - SETTLE_S);
        if (limitThenW == null || limitThenW >= nominalW(serial) - 1.0 || live < limitThenW - marginW(serial)) {
            return live;
        }
        return nominalW(serial);
    }

    private boolean marginalUnderusesItsLimit(
            String serial, Map<String, Double> livePowerW, Map<String, Double> dataAgeS, double nowS) {
        Double ageS = dataAgeS.get(serial);
        if (ageS == null) {
            return false;
        }
        double readingS = nowS - ageS;
        if (lastJudgedReadingS != null && Math.abs(readingS - lastJudgedReadingS) < 1.0) {
            return false; // same reading as last time, already counted
        }
        Double limitThenW = limitInForceAt(serial, readingS - SETTLE_S);
        if (limitThenW == null || limitThenW >= nominalW(serial) - 1.0) {
            return false; // not under a limit of ours (yet) when that reading was taken
        }
        lastJudgedReadingS = readingS;
        double live = livePowerW.getOrDefault(serial, 0.0);
        underuseReadings = live < limitThenW - marginW(serial) ? underuseReadings + 1 : 0;
        return underuseReadings >= HANDBACK_READINGS;
    }

    /** The last limit given to {@code serial} at or before {@code timeS}, null if none yet. */
    private Double limitInForceAt(String serial, double timeS) {
        Deque<double[]> history = limitHistory.get(serial);
        if (history == null) {
            return null;
        }
        Double limit = null;
        for (double[] entry : history) {
            if (entry[0] > timeS) {
                break;
            }
            limit = entry[1];
        }
        return limit;
    }

    private void recordLimit(String serial, double nowS, double limitW) {
        Deque<double[]> history = limitHistory.computeIfAbsent(serial, s -> new ArrayDeque<>());
        if (!history.isEmpty() && Math.abs(history.peekLast()[1] - limitW) < 0.5) {
            return; // unchanged: the limit in force since that entry still applies
        }
        history.addLast(new double[] {nowS, limitW});
        // Keep the newest entry older than the window: it is still the limit in force at its start.
        while (history.size() > 1) {
            double[] second = history.stream().skip(1).findFirst().orElseThrow();
            if (second[0] > nowS - LIMIT_HISTORY_S) {
                break;
            }
            history.removeFirst();
        }
    }

    private void forgetUnderuse() {
        lastJudgedReadingS = null;
        underuseReadings = 0;
    }

    private double remainingW(double targetW, List<String> active, int idx, Map<String, Double> potentialW) {
        double rest = targetW;
        for (int i = 0; i < idx; i++) {
            rest -= floorW(active.get(i));
        }
        for (int i = idx + 1; i < active.size(); i++) {
            rest -= potentialW.get(active.get(i));
        }
        return rest;
    }

    private double marginW(String serial) {
        return Math.max(HANDBACK_MARGIN_W, 0.05 * nominalW(serial));
    }

    private double nominalW(String serial) {
        return nominalPowerW.getOrDefault(serial, 0.0);
    }

    private double floorW(String serial) {
        return minInverterPct / 100.0 * nominalW(serial);
    }
}
