package gxopendtu.control;

/**
 * Turns a grid-power error into a rate-limited, quantized total power target.
 *
 * The effective step is the larger of the absolute and relative step
 * settings: a small install still gets a meaningful watt-sized deadband, and
 * a large install doesn't get flooded with tiny percentage-sized command
 * changes. Port of src/controller.py's SoftTargetController.
 */
public final class SoftTargetController {

    private final double exportSetpointW;
    private final PIController pi;
    private final double stepAbsoluteW;
    private final double stepRelativePct;
    private final double minChangeW;
    private Double lastSentTotalW;

    public SoftTargetController(
            double exportSetpointW,
            double kp,
            double ki,
            double stepAbsoluteW,
            double stepRelativePct,
            double minChangeW,
            Double integralLimit) {
        this.exportSetpointW = exportSetpointW;
        this.pi = new PIController(kp, ki, integralLimit);
        this.stepAbsoluteW = stepAbsoluteW;
        this.stepRelativePct = stepRelativePct;
        this.minChangeW = minChangeW;
    }

    public SoftTargetController(
            double exportSetpointW, double kp, double ki, double stepAbsoluteW, double stepRelativePct, double minChangeW) {
        this(exportSetpointW, kp, ki, stepAbsoluteW, stepRelativePct, minChangeW, null);
    }

    public double effectiveStepW(double totalCapacityW) {
        double relativeStep = stepRelativePct / 100.0 * totalCapacityW;
        return Math.max(stepAbsoluteW, relativeStep);
    }

    public ControlDecision computeTarget(double gridPowerAvgW, double currentTotalActualW, double totalCapacityW) {
        double error = gridPowerAvgW - exportSetpointW;
        double delta = pi.step(error);
        double rawTarget = ControlMath.clamp(currentTotalActualW + delta, 0.0, totalCapacityW);

        double step = effectiveStepW(totalCapacityW);
        double quantized = ControlMath.quantize(rawTarget, step);

        double baseline = lastSentTotalW != null ? lastSentTotalW : quantized;
        double nextTarget = ControlMath.rampLimit(baseline, quantized, step);

        boolean changed = lastSentTotalW == null || Math.abs(nextTarget - lastSentTotalW) >= minChangeW;
        if (changed) {
            lastSentTotalW = nextTarget;
        }

        return new ControlDecision(nextTarget, changed);
    }

    public record ControlDecision(double targetW, boolean changed) {}
}
