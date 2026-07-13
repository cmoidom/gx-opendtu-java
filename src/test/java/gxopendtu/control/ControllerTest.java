package gxopendtu.control;

import gxopendtu.control.SoftTargetController.ControlDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControllerTest {

    @Test
    void gridPowerSmootherFirstSampleIsPassedThrough() {
        GridPowerSmoother smoother = new GridPowerSmoother(0.5);
        assertThat(smoother.add(100)).isEqualTo(100.0);
    }

    @Test
    void gridPowerSmootherAppliesExponentialMovingAverage() {
        GridPowerSmoother smoother = new GridPowerSmoother(0.5);
        smoother.add(100);
        assertThat(smoother.add(200)).isEqualTo(150.0); // 100 + 0.5*(200-100)
        assertThat(smoother.add(0)).isEqualTo(75.0); // 150 + 0.5*(0-150)
    }

    @Test
    void gridPowerSmootherAlphaOneTracksRawValueInstantly() {
        GridPowerSmoother smoother = new GridPowerSmoother(1.0);
        smoother.add(100);
        assertThat(smoother.add(500)).isEqualTo(500.0);
    }

    @Test
    void gridPowerSmootherRejectsInvalidAlpha() {
        for (double badAlpha : new double[] {0, -0.1, 1.1}) {
            assertThatThrownBy(() -> new GridPowerSmoother(badAlpha)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void quantizeRoundsToNearestStep() {
        assertThat(ControlMath.quantize(149, 100)).isEqualTo(100.0);
        assertThat(ControlMath.quantize(151, 100)).isEqualTo(200.0);
        assertThat(ControlMath.quantize(0, 100)).isEqualTo(0.0);
    }

    @Test
    void quantizeWithZeroStepIsNoop() {
        assertThat(ControlMath.quantize(123.4, 0)).isEqualTo(123.4);
    }

    @Test
    void rampLimitCapsMovementPerCycle() {
        assertThat(ControlMath.rampLimit(100, 1000, 100)).isEqualTo(200.0);
        assertThat(ControlMath.rampLimit(100, 50, 100)).isEqualTo(50.0);
        assertThat(ControlMath.rampLimit(100, 100, 100)).isEqualTo(100.0);
    }

    @Test
    void softTargetControllerIsQuantizedAndRateLimited() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.0, 100, 0, 5);

        // First call always "changes" (no previous baseline) and establishes the target.
        ControlDecision first = controller.computeTarget(30, 200, 1000);
        assertThat(first.changed()).isTrue();
        assertThat(first.targetW()).isEqualTo(200.0);

        // Small error shouldn't move the target by more than a quantized step, and
        // shouldn't fire a change if it stays within min_change_w of last sent value.
        ControlDecision second = controller.computeTarget(32, 200, 1000);
        assertThat(second.changed()).isFalse();
        assertThat(second.targetW()).isEqualTo(200.0);
    }

    @Test
    void controlDecisionExposesErrorAndPiIntegralForDebugging() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.5, 100, 0, 5);
        ControlDecision decision = controller.computeTarget(50, 200, 1000);
        assertThat(decision.error()).isEqualTo(20.0); // 50 - setpoint(30)
        assertThat(decision.piIntegral()).isEqualTo(10.0); // error(20) * ki(0.5)
        assertThat(decision.stepW()).isEqualTo(100.0);
        assertThat(decision.quantizedW()).isEqualTo(200.0); // rawTarget(230) quantized to nearest 100
    }

    @Test
    void softTargetControllerRampsLargeJumpsOverMultipleCycles() {
        SoftTargetController controller = new SoftTargetController(0, 2.0, 0.0, 100, 0, 5);

        ControlDecision first = controller.computeTarget(0, 0, 2000);
        assertThat(first.targetW()).isEqualTo(0.0);

        ControlDecision second = controller.computeTarget(1000, 0, 2000);
        assertThat(second.targetW()).isEqualTo(100.0); // capped to one step this cycle

        ControlDecision third = controller.computeTarget(1000, 0, 2000);
        assertThat(third.targetW()).isEqualTo(200.0); // ramps up by another step
    }

    @Test
    void batteryDischargeBoostsTargetWhenHeadroomAvailable() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.0, 100, 0, 5);
        // Grid already sits at the setpoint (error=0) -- the PI alone would keep
        // the target at current production (200W), but the battery is
        // discharging 100W while there's plenty of capacity headroom (1000W):
        // must boost the target to replace that discharge with production.
        ControlDecision decision = controller.computeTarget(30, 200, 1000, -100.0);
        assertThat(decision.targetW()).isEqualTo(300.0); // 200 + 100 discharge, already a clean step multiple
        assertThat(decision.batteryFloorEngaged()).isTrue();
        assertThat(decision.batteryDischargeW()).isEqualTo(100.0);
        assertThat(decision.rawTargetBeforeFloor()).isEqualTo(200.0); // error=0 -- PI alone wants current production
        assertThat(decision.rawTargetAfterFloor()).isEqualTo(300.0);
    }

    @Test
    void batteryDischargeBoostClampedToTotalCapacity() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.0, 100, 0, 5);
        // Discharge (300W) would push the boosted target past capacity (900+300
        // > 1000) -- clamped to totalCapacityW, i.e. "every inverter maxed
        // out, no more sun available", exactly the case where battery discharge
        // is meant to be accepted.
        ControlDecision decision = controller.computeTarget(30, 900, 1000, -300.0);
        assertThat(decision.targetW()).isEqualTo(1000.0);
    }

    @Test
    void batteryChargingDoesNotBoostTarget() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.0, 100, 0, 5);
        ControlDecision decision = controller.computeTarget(30, 200, 1000, 50.0);
        assertThat(decision.targetW()).isEqualTo(200.0);
    }

    @Test
    void batteryPowerOmittedBehavesLikeNoBattery() {
        SoftTargetController controller = new SoftTargetController(30, 1.0, 0.0, 100, 0, 5);
        ControlDecision decision = controller.computeTarget(30, 200, 1000);
        assertThat(decision.targetW()).isEqualTo(200.0);
    }

    @Test
    void batteryDischargeBoostDoesNotPollutePiIntegral() {
        SoftTargetController controller = new SoftTargetController(0, 0.0, 1.0, 50, 0, 5);
        // Grid sits exactly at setpoint every cycle (error=0) -- ki alone
        // contributes nothing regardless of the battery, so the integral must
        // stay at 0 even while a large, persistent battery-discharge boost
        // repeatedly saturates the target at totalCapacityW. A boost that
        // leaked into pi.integral would wind up here and overshoot once the
        // discharge stops (e.g. once the sun returns the next morning).
        for (int i = 0; i < 5; i++) {
            controller.computeTarget(0, 500, 500, -1000.0);
        }
        assertThat(controller.pi().integral()).isEqualTo(0.0);
    }

    @Test
    void smallBatteryDischargeBelowThresholdIsIgnored() {
        // A battery at/near 100% SOC still shows a small self-consumption
        // discharge (observed ~80-100W in production) -- below the
        // configured threshold, it must NOT float the target back up.
        SoftTargetController controller =
                new SoftTargetController(30, 1.0, 0.0, 100, 0, 5, null, 150.0);
        ControlDecision decision = controller.computeTarget(30, 200, 1000, -100.0);
        assertThat(decision.targetW()).isEqualTo(200.0); // no boost, discharge is below the 150W threshold
        assertThat(decision.batteryFloorEngaged()).isFalse();
        assertThat(decision.batteryDischargeW()).isEqualTo(100.0); // still reported, just didn't engage the floor
    }

    @Test
    void batteryDischargeAtExactlyTheThresholdIsIgnored() {
        // Strictly-greater comparison: exactly at the threshold does not engage the floor.
        SoftTargetController controller =
                new SoftTargetController(30, 1.0, 0.0, 100, 0, 5, null, 150.0);
        ControlDecision decision = controller.computeTarget(30, 200, 1000, -150.0);
        assertThat(decision.targetW()).isEqualTo(200.0);
    }

    @Test
    void batteryDischargeAboveThresholdStillBoostsTarget() {
        SoftTargetController controller =
                new SoftTargetController(30, 1.0, 0.0, 100, 0, 5, null, 150.0);
        ControlDecision decision = controller.computeTarget(30, 200, 1000, -300.0);
        assertThat(decision.targetW()).isEqualTo(500.0); // 200 + 300 discharge, above the 150W threshold
        assertThat(decision.batteryFloorEngaged()).isTrue();
        assertThat(decision.batteryDischargeW()).isEqualTo(300.0);
    }

    @Test
    void zeroThresholdPreservesOldBehaviorOfBoostingOnAnyDischarge() {
        SoftTargetController controller =
                new SoftTargetController(30, 1.0, 0.0, 100, 0, 5, null, 0.0);
        ControlDecision decision = controller.computeTarget(30, 200, 1000, -100.0);
        assertThat(decision.targetW()).isEqualTo(300.0);
    }

    @Test
    void effectiveStepUsesLargerOfAbsoluteAndRelative() {
        SoftTargetController controller = new SoftTargetController(0, 1.0, 0.0, 100, 10, 5);
        assertThat(controller.effectiveStepW(3000)).isEqualTo(300.0); // 10% of 3000W > 100W floor
        assertThat(controller.effectiveStepW(500)).isEqualTo(100.0); // 10% of 500W < 100W floor
    }

    @Test
    void capacityEstimatorLowersCeilingWhenInverterCannotKeepUp() {
        CapacityEstimator estimator = new CapacityEstimator(Map.of("a", 600.0), 10);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(600.0);

        // Allocated 550W (near the 600W ceiling -- we were actually testing the
        // limit), but only 250W actually produced, and OpenDTU confirms the
        // limit itself isn't what's holding it back (limit_acknowledged=true at
        // a higher value) -> assume irradiance-limited, cap drops to actual output.
        estimator.observe("a", 550, 250, true);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(250.0);

        estimator.probeTick();
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(260.0);
        estimator.probeTick();
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(270.0);
    }

    @Test
    void capacityEstimatorKeepsCeilingWhenInverterKeepsUp() {
        CapacityEstimator estimator = new CapacityEstimator(Map.of("a", 600.0), 10);
        estimator.observe("a", 400, 400, true);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(600.0);
    }

    @Test
    void capacityEstimatorIgnoresShortfallWhenTargetWasNotNearCeiling() {
        // The zero-export target is often well below any inverter's real max on
        // purpose -- a small shortfall against a modest allocated share (here
        // 400W out of a 600W ceiling, 67%) is ordinary noise, not proof the
        // inverter can't do more. Must NOT collapse the ceiling.
        CapacityEstimator estimator = new CapacityEstimator(Map.of("a", 600.0), 10);
        estimator.observe("a", 400, 380, true);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(600.0);
    }

    @Test
    void capacityEstimatorNearCeilingThresholdIs90Percent() {
        CapacityEstimator estimator = new CapacityEstimator(Map.of("a", 600.0), 10);
        // 89% of the 600W ceiling: just under the threshold -> ignored.
        estimator.observe("a", 534, 500, true);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(600.0);

        // 90% of the current (still 600W) ceiling: at the threshold -> counts.
        estimator.observe("a", 540, 500, true);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(500.0);
    }
}
