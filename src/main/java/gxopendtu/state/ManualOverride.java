package gxopendtu.state;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe manual override: force all inverters to a fixed relative %
 * from the dashboard, for a bounded duration, then automatically resume
 * normal PI control -- no risk of a forgotten override exporting
 * indefinitely.
 *
 * Written by the webui HTTP server threads, read by the control loop once
 * per decision cycle. A lost grid meter (fail-safe) or battery-charge-
 * priority release both take priority over an active override -- this class
 * has no way to prevent that, by construction. Port of
 * src/manual_override.py's ManualOverride.
 */
public final class ManualOverride {

    public static final double DEFAULT_DURATION_S = 300.0; // 5 minutes

    private final ReentrantLock lock = new ReentrantLock();
    private Double pct;
    private Double expiresAt; // monotonic seconds

    public void set(double pct, double durationS) {
        lock.lock();
        try {
            this.pct = pct;
            this.expiresAt = monotonicSeconds() + durationS;
        } finally {
            lock.unlock();
        }
    }

    public void set(double pct) {
        set(pct, DEFAULT_DURATION_S);
    }

    public void clear() {
        lock.lock();
        try {
            this.pct = null;
            this.expiresAt = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The forced percentage if the override is still within its window, else
     * null -- also clears the override once expired, so a single call both
     * checks and lets it lapse.
     */
    public Double activePct() {
        lock.lock();
        try {
            if (pct == null) {
                return null;
            }
            if (monotonicSeconds() >= expiresAt) {
                pct = null;
                expiresAt = null;
                return null;
            }
            return pct;
        } finally {
            lock.unlock();
        }
    }

    /** {"pct": ..., "remaining_s": ...} for the dashboard, or null if inactive. */
    public Map<String, Object> snapshot() {
        lock.lock();
        try {
            if (pct == null) {
                return null;
            }
            double remaining = expiresAt - monotonicSeconds();
            if (remaining <= 0) {
                return null;
            }
            return Map.of("pct", pct, "remaining_s", remaining);
        } finally {
            lock.unlock();
        }
    }

    private static double monotonicSeconds() {
        return System.nanoTime() / 1_000_000_000.0;
    }
}
