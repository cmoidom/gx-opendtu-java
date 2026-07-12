package gxopendtu.control;

/** Small pure helpers shared by the soft controller. Port of the module-level functions in controller.py. */
public final class ControlMath {

    private ControlMath() {}

    public static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    public static double quantize(double value, double step) {
        if (step <= 0) {
            return value;
        }
        return Math.round(value / step) * step;
    }

    public static double rampLimit(double current, double target, double maxStep) {
        double delta = clamp(target - current, -maxStep, maxStep);
        return current + delta;
    }
}
