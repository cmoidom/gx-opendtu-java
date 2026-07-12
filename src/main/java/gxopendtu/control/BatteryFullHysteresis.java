package gxopendtu.control;

/**
 * Latches injection control (curtailment) ON only once the battery SOC
 * reaches activateAtPct, and OFF only once it drops below
 * deactivateBelowPct -- a dead zone between the two thresholds prevents
 * flapping on/off as SOC drifts around either boundary during the day.
 *
 * Also activates early -- without waiting for socPct to reach activateAtPct
 * exactly -- if real grid export is observed while SOC is already at or
 * above deactivateBelowPct: that's empirical proof the battery can no longer
 * absorb the AC-coupled PV surplus, regardless of what the SOC estimate says
 * (SOC reporting can lag reality, especially near full on a flat-voltage-
 * curve chemistry like LFP; it also handles a latch that reset to inactive
 * on a service restart while the battery was already full). Disabled by
 * exportConfirmsFullW <= 0.
 *
 * Port of src/controller.py's BatteryFullHysteresis.
 */
public final class BatteryFullHysteresis {

    private final double activateAtPct;
    private final double deactivateBelowPct;
    private final double exportConfirmsFullW;
    private boolean active;

    public BatteryFullHysteresis(double activateAtPct, double deactivateBelowPct, boolean active, double exportConfirmsFullW) {
        this.activateAtPct = activateAtPct;
        this.deactivateBelowPct = deactivateBelowPct;
        this.active = active;
        this.exportConfirmsFullW = exportConfirmsFullW;
    }

    public BatteryFullHysteresis(double activateAtPct, double deactivateBelowPct, boolean active) {
        this(activateAtPct, deactivateBelowPct, active, 50.0);
    }

    public BatteryFullHysteresis() {
        this(100.0, 98.0, false, 50.0);
    }

    public boolean isActive() {
        return active;
    }

    /** Used to force ON/OFF from the dashboard's sticky AUTO/ON/OFF override, bypassing update(). */
    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean update(double socPct, Double gridPowerW) {
        if (active) {
            if (socPct < deactivateBelowPct) {
                active = false;
            }
        } else if (socPct >= activateAtPct) {
            active = true;
        } else if (exportConfirmsFullW > 0 && gridPowerW != null && gridPowerW <= -exportConfirmsFullW
                && socPct >= deactivateBelowPct) {
            active = true;
        }
        return active;
    }

    public boolean update(double socPct) {
        return update(socPct, null);
    }
}
