package gxopendtu.control;

/**
 * Latches injection control (curtailment) ON only once the battery SOC
 * reaches activateAtPct, and OFF only once it drops below
 * deactivateBelowPct -- a dead zone between the two thresholds prevents
 * flapping on/off as SOC drifts around either boundary during the day.
 *
 * Also activates early -- without waiting for socPct to reach activateAtPct
 * exactly -- if real grid export is observed CONTINUOUSLY for at least
 * exportConfirmsFullDurationS while SOC is already at or above
 * deactivateBelowPct: that's empirical proof the battery can no longer
 * absorb the AC-coupled PV surplus, regardless of what the SOC estimate says
 * (SOC reporting can lag reality, especially near full on a flat-voltage-
 * curve chemistry like LFP; it also handles a latch that reset to inactive
 * on a service restart while the battery was already full). Requiring the
 * export to hold for a stretch (not just one instantaneous reading) avoids
 * a brief spike (e.g. a load turning off for a moment) triggering this
 * early. Disabled by exportConfirmsFullW <= 0. {@code now} is any
 * monotonically increasing clock in seconds (loop.ControlLoop.run passes
 * its own System.nanoTime()-based tick counter) -- only ever used to
 * measure elapsed seconds between calls, never as a real timestamp.
 *
 * Port of src/controller.py's BatteryFullHysteresis.
 */
public final class BatteryFullHysteresis {

    private final double activateAtPct;
    private final double deactivateBelowPct;
    private final double exportConfirmsFullW;
    private final double exportConfirmsFullDurationS;
    private boolean active;
    private Double exportAboveThresholdSince;

    public BatteryFullHysteresis(
            double activateAtPct,
            double deactivateBelowPct,
            boolean active,
            double exportConfirmsFullW,
            double exportConfirmsFullDurationS) {
        this.activateAtPct = activateAtPct;
        this.deactivateBelowPct = deactivateBelowPct;
        this.active = active;
        this.exportConfirmsFullW = exportConfirmsFullW;
        this.exportConfirmsFullDurationS = exportConfirmsFullDurationS;
    }

    public BatteryFullHysteresis(double activateAtPct, double deactivateBelowPct, boolean active, double exportConfirmsFullW) {
        this(activateAtPct, deactivateBelowPct, active, exportConfirmsFullW, 60.0);
    }

    public BatteryFullHysteresis(double activateAtPct, double deactivateBelowPct, boolean active) {
        this(activateAtPct, deactivateBelowPct, active, 50.0, 60.0);
    }

    public BatteryFullHysteresis() {
        this(100.0, 98.0, false, 50.0, 60.0);
    }

    public boolean isActive() {
        return active;
    }

    /** Used to force ON/OFF from the dashboard's sticky AUTO/ON/OFF override, bypassing update(). */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Seconds elapsed in the current sustained-export streak, or null if no
     * streak is running -- debug/introspection only (see
     * webui/InternalStatusJsonHandler), never read by update() itself.
     */
    public Double exportStreakElapsedS(double now) {
        return exportAboveThresholdSince != null ? now - exportAboveThresholdSince : null;
    }

    public boolean update(double socPct, Double gridPowerW, double now) {
        if (active) {
            exportAboveThresholdSince = null;
            if (socPct < deactivateBelowPct) {
                active = false;
            }
        } else if (socPct >= activateAtPct) {
            active = true;
            exportAboveThresholdSince = null;
        } else if (exportConfirmsFullW > 0 && gridPowerW != null && gridPowerW <= -exportConfirmsFullW
                && socPct >= deactivateBelowPct) {
            if (exportAboveThresholdSince == null) {
                exportAboveThresholdSince = now;
            }
            if (now - exportAboveThresholdSince >= exportConfirmsFullDurationS) {
                active = true;
                exportAboveThresholdSince = null;
            }
        } else {
            exportAboveThresholdSince = null;
        }
        return active;
    }

    public boolean update(double socPct) {
        return update(socPct, null, 0.0);
    }
}
