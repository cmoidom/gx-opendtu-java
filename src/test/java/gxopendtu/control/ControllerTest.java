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
    void effectiveStepUsesLargerOfAbsoluteAndRelative() {
        SoftTargetController controller = new SoftTargetController(0, 1.0, 0.0, 100, 10, 5);
        assertThat(controller.effectiveStepW(3000)).isEqualTo(300.0); // 10% of 3000W > 100W floor
        assertThat(controller.effectiveStepW(500)).isEqualTo(100.0); // 10% of 500W < 100W floor
    }

    @Test
    void capacityEstimatorLowersCeilingWhenInverterCannotKeepUp() {
        CapacityEstimator estimator = new CapacityEstimator(Map.of("a", 600.0), 10);
        assertThat(estimator.ceilingsW().get("a")).isEqualTo(600.0);

        estimator.observe("a", 400, 250, true);
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
}
