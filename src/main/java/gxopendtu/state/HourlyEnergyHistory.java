package gxopendtu.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hourly-bucketed grid energy import/export for the dashboard bar chart.
 *
 * Derived from the grid meter's cumulative from-net/to-net counters: each
 * {@code record()} call computes the delta since the last reading and adds
 * it to the current wall-clock hour's bucket.
 *
 * Thread-safe like {@link LiveState} -- written by the control loop, read by
 * the webui dashboard. Lost on every service restart. Port of
 * src/energy_history.py's HourlyEnergyHistory.
 */
public final class HourlyEnergyHistory {

    public static final int DEFAULT_RETAIN_HOURS = 48;
    private static final double SECONDS_PER_HOUR = 3600.0;

    private final ReentrantLock lock = new ReentrantLock();
    private final int maxBuckets; // retainHours + 1, the extra slot for the in-progress hour
    private final Deque<MutableBucket> buckets = new ArrayDeque<>();
    private Double lastFromKwh;
    private Double lastToKwh;

    public HourlyEnergyHistory() {
        this(DEFAULT_RETAIN_HOURS);
    }

    public HourlyEnergyHistory(int retainHours) {
        this.maxBuckets = retainHours + 1;
    }

    static double hourStart(double t) {
        return t - (t % SECONDS_PER_HOUR);
    }

    public void record(double fromKwh, double toKwh) {
        record(fromKwh, toKwh, System.currentTimeMillis() / 1000.0);
    }

    public void record(double fromKwh, double toKwh, double now) {
        double hour = hourStart(now);
        lock.lock();
        try {
            if (lastFromKwh == null) {
                // First reading (including right after a restart, when this
                // object's state -- but not the meter's own counter -- has
                // been lost): nothing to diff against yet, so start an empty
                // bucket rather than attributing the meter's entire lifetime
                // total to a single hour.
                if (buckets.isEmpty() || buckets.peekLast().hour != hour) {
                    addBucket(new MutableBucket(hour, 0.0, 0.0));
                }
            } else {
                double deltaFrom = fromKwh - lastFromKwh;
                double deltaTo = toKwh - lastToKwh;
                // A cumulative counter only ever increases; a drop means the
                // meter/counter was reset -- skip this delta rather than
                // recording a bogus negative bar.
                if (deltaFrom >= 0 && deltaTo >= 0) {
                    if (!buckets.isEmpty() && buckets.peekLast().hour == hour) {
                        MutableBucket last = buckets.peekLast();
                        last.fromKwh += deltaFrom;
                        last.toKwh += deltaTo;
                    } else {
                        addBucket(new MutableBucket(hour, deltaFrom, deltaTo));
                    }
                }
            }
            lastFromKwh = fromKwh;
            lastToKwh = toKwh;
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
                m.put("from_kwh", b.fromKwh);
                m.put("to_kwh", b.toKwh);
                result.add(m);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private static final class MutableBucket {
        final double hour;
        double fromKwh;
        double toKwh;

        MutableBucket(double hour, double fromKwh, double toKwh) {
            this.hour = hour;
            this.fromKwh = fromKwh;
            this.toKwh = toKwh;
        }
    }
}
