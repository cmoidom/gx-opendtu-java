package gxopendtu.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hourly-bucketed per-inverter energy yield (Wh) for the dashboard's
 * "Energie par onduleur (par heure)" bar chart -- the per-inverter counterpart of
 * {@link HourlyEnergyHistory} (whole-system grid import/export).
 *
 * Derived from each inverter's own OpenDTU-reported YieldDay counter (Wh,
 * resets to 0 at local midnight on the inverter): each {@code record()} call
 * computes the delta since the last reading, per serial, and adds it to the
 * current wall-clock hour's bucket. A negative delta (midnight reset, or a
 * serial seen for the first time) is skipped rather than recorded as a bogus
 * value -- same reasoning as {@link HourlyEnergyHistory#record}.
 *
 * Thread-safe, ephemeral (lost on every service restart, reseeded from
 * stats.db at startup) -- same lifecycle as {@link HourlyEnergyHistory}.
 */
public final class InverterEnergyHistory {

    public static final int DEFAULT_RETAIN_HOURS = 48;

    private final ReentrantLock lock = new ReentrantLock();
    private final int maxBuckets; // retainHours + 1, the extra slot for the in-progress hour
    private final Deque<MutableBucket> buckets = new ArrayDeque<>();
    private final Map<String, Double> lastYieldDayWh = new HashMap<>();

    public InverterEnergyHistory() {
        this(DEFAULT_RETAIN_HOURS);
    }

    public InverterEnergyHistory(int retainHours) {
        this.maxBuckets = retainHours + 1;
    }

    public void record(Map<String, Double> yieldDayWh) {
        record(yieldDayWh, System.currentTimeMillis() / 1000.0);
    }

    public void record(Map<String, Double> yieldDayWh, double now) {
        double hour = HourlyEnergyHistory.hourStart(now);
        lock.lock();
        try {
            MutableBucket bucket;
            if (!buckets.isEmpty() && buckets.peekLast().hour == hour) {
                bucket = buckets.peekLast();
            } else {
                bucket = new MutableBucket(hour);
                addBucket(bucket);
            }
            for (Map.Entry<String, Double> entry : yieldDayWh.entrySet()) {
                String serial = entry.getKey();
                double value = entry.getValue();
                Double last = lastYieldDayWh.get(serial);
                if (last != null) {
                    double delta = value - last;
                    if (delta >= 0) {
                        bucket.whBySerial.merge(serial, delta, Double::sum);
                    }
                    // else: counter reset (local midnight, or the inverter/
                    // service restarted) -- skip rather than record a bogus
                    // negative contribution.
                }
                // else: first reading ever for this serial, nothing to diff
                // against yet -- same "first reading" no-bogus-total handling
                // as HourlyEnergyHistory.
                lastYieldDayWh.put(serial, value);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Repopulates the bucket deque from persisted long-term history (see
     * {@code stats.StatsStore#loadInverterHourlyEnergy}), called once at
     * startup -- same reasoning as {@link HourlyEnergyHistory#seedBuckets}.
     * {@code seedBuckets} must already be in chronological order (oldest
     * first). Deliberately leaves {@code lastYieldDayWh} unset: the next real
     * {@link #record} call has no counter baseline to diff against yet
     * regardless of this seed, so it correctly falls into the same
     * "first reading" branch a fresh restart already handles.
     */
    public void seedBuckets(List<Map<String, Object>> seedBuckets) {
        if (seedBuckets == null || seedBuckets.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            this.buckets.clear();
            for (Map<String, Object> bucket : seedBuckets) {
                MutableBucket mutable = new MutableBucket((Double) bucket.get("hour"));
                for (Map.Entry<String, Object> entry : bucket.entrySet()) {
                    if ("hour".equals(entry.getKey())) {
                        continue;
                    }
                    mutable.whBySerial.put(entry.getKey(), (Double) entry.getValue());
                }
                addBucket(mutable);
            }
        } finally {
            lock.unlock();
        }
    }

    private void addBucket(MutableBucket bucket) {
        if (buckets.size() >= maxBuckets) {
            buckets.removeFirst();
        }
        buckets.addLast(bucket);
    }

    public List<Map<String, Object>> snapshot() {
        lock.lock();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (MutableBucket b : buckets) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hour", b.hour);
                m.putAll(b.whBySerial);
                result.add(m);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private static final class MutableBucket {
        final double hour;
        final Map<String, Double> whBySerial = new LinkedHashMap<>();

        MutableBucket(double hour) {
            this.hour = hour;
        }
    }
}
