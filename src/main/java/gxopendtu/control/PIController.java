package gxopendtu.control;

/** Port of src/controller.py's PIController: kp*error + clamped integral. */
public final class PIController {

    private final double kp;
    private final double ki;
    private final Double integralLimit;
    private double integral = 0.0;

    public PIController(double kp, double ki, Double integralLimit) {
        this.kp = kp;
        this.ki = ki;
        this.integralLimit = integralLimit;
    }

    public PIController(double kp, double ki) {
        this(kp, ki, null);
    }

    public double step(double error) {
        integral += error * ki;
        if (integralLimit != null) {
            integral = ControlMath.clamp(integral, -integralLimit, integralLimit);
        }
        return kp * error + integral;
    }
}
