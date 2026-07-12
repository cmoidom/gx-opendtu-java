package gxopendtu.control;

/**
 * Exponential moving average over grid power readings, to damp measurement
 * noise without adding the step-discontinuity a fixed-window moving average
 * has when an old sample drops out of the window.
 *
 * filtered += alpha * (raw - filtered). Higher alpha reacts faster to a
 * genuine load step (at the cost of passing through more noise); lower alpha
 * is smoother but slower. Tune based on read_interval_s: the time constant is
 * roughly read_interval_s / alpha.
 *
 * Port of src/controller.py's GridPowerSmoother.
 */
public final class GridPowerSmoother {

    private final double alpha;
    private Double filtered;

    public GridPowerSmoother(double alpha) {
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException("alpha must be in (0, 1]");
        }
        this.alpha = alpha;
    }

    public double add(double watts) {
        if (filtered == null) {
            filtered = watts;
        } else {
            filtered += alpha * (watts - filtered);
        }
        return filtered;
    }

    public double average() {
        return filtered != null ? filtered : 0.0;
    }
}
