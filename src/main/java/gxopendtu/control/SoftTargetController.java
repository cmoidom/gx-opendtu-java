package gxopendtu.control;

/**
 * Turns a grid-power error into a rate-limited, quantized total power target.
 *
 * The effective step is the larger of the absolute and relative step
 * settings: a small install still gets a meaningful watt-sized deadband, and
 * a large install doesn't get flooded with tiny percentage-sized command
 * changes.
 *
 * Also enforces: never draw from the battery while solar has headroom to
 * cover it instead -- only acceptable once every inverter is already at its
 * capacity ceiling (no more sun available). See computeTarget's
 * batteryPowerW handling.
 *
 * Port of src/controller.py's SoftTargetController.
 */
public final class SoftTargetController {

    private final double exportSetpointW;
    private final PIController pi;
    private final double stepAbsoluteW;
    private final double stepRelativePct;
    private final double minChangeW;
    private final double minBatteryDischargeW;
    private Double lastSentTotalW;

    public SoftTargetController(
            double exportSetpointW,
            double kp,
            double ki,
            double stepAbsoluteW,
            double stepRelativePct,
            double minChangeW,
            Double integralLimit,
            double minBatteryDischargeW) {
        this.exportSetpointW = exportSetpointW;
        this.pi = new PIController(kp, ki, integralLimit);
        this.stepAbsoluteW = stepAbsoluteW;
        this.stepRelativePct = stepRelativePct;
        this.minChangeW = minChangeW;
        this.minBatteryDischargeW = minBatteryDischargeW;
    }

    public SoftTargetController(
            double exportSetpointW,
            double kp,
            double ki,
            double stepAbsoluteW,
            double stepRelativePct,
            double minChangeW,
            Double integralLimit) {
        this(exportSetpointW, kp, ki, stepAbsoluteW, stepRelativePct, minChangeW, integralLimit, 0.0);
    }

    public SoftTargetController(
            double exportSetpointW, double kp, double ki, double stepAbsoluteW, double stepRelativePct, double minChangeW) {
        this(exportSetpointW, kp, ki, stepAbsoluteW, stepRelativePct, minChangeW, null, 0.0);
    }

    /** Exposed for tests verifying the battery-discharge floor never pollutes the PI's own integral. */
    public PIController pi() {
        return pi;
    }

    public double effectiveStepW(double totalCapacityW) {
        double relativeStep = stepRelativePct / 100.0 * totalCapacityW;
        return Math.max(stepAbsoluteW, relativeStep);
    }

    public ControlDecision computeTarget(double gridPowerAvgW, double currentTotalActualW, double totalCapacityW) {
        return computeTarget(gridPowerAvgW, currentTotalActualW, totalCapacityW, null);
    }

    public ControlDecision computeTarget(
            double gridPowerAvgW, double currentTotalActualW, double totalCapacityW, Double batteryPowerW) {
        double error = gridPowerAvgW - exportSetpointW;
        double delta = pi.step(error);
        double rawTarget = ControlMath.clamp(currentTotalActualW + delta, 0.0, totalCapacityW);

        // Never let the battery cover a shortfall solar could cover instead:
        // the grid-power PI above is blind to the battery entirely, so it can
        // be "satisfied" (grid near exportSetpointW) while the battery
        // quietly covers a gap solar has headroom for. If discharging
        // (negative) by more than minBatteryDischargeW, floor the target at
        // whatever would fully replace that discharge with production --
        // clamped to totalCapacityW, so once every inverter is already maxed
        // out (no more sun) the floor simply can't push higher and the
        // remaining discharge is accepted, exactly as it should be. Applied
        // to rawTarget only, never to the PI's own integral, so it can't
        // wind up while saturated at full capacity (e.g. overnight) and
        // overshoot once the sun returns. The threshold exists because a
        // battery at/near 100% SOC still shows a small, harmless discharge
        // (its own float/self-consumption draw, observed ~80-100W on this
        // install) -- without a threshold that noise kept flooring the
        // target back up and fighting the PI's own correct downward
        // correction indefinitely (2026-07-13 production incident).
        if (batteryPowerW != null && batteryPowerW < -minBatteryDischargeW) {
            double batteryDischargeW = -batteryPowerW;
            rawTarget = ControlMath.clamp(
                    Math.max(rawTarget, currentTotalActualW + batteryDischargeW), 0.0, totalCapacityW);
        }

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
