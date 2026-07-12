package gxopendtu.control;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatteryHysteresisTest {

    private static BatteryFullHysteresis make() {
        return make(false);
    }

    private static BatteryFullHysteresis make(boolean active) {
        return new BatteryFullHysteresis(100.0, 98.0, active);
    }

    @Test
    void startsInactiveAndStaysInactiveBelowActivationThreshold() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(50)).isFalse();
        assertThat(h.update(99)).isFalse();
        assertThat(h.update(99.9)).isFalse();
    }

    @Test
    void activatesOnlyAtTheActivationThreshold() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(100)).isTrue();
    }

    @Test
    void noYoyoAround100OnceActive() {
        BatteryFullHysteresis h = make();
        h.update(100);
        assertThat(h.isActive()).isTrue();
        assertThat(h.update(99)).isTrue();
        assertThat(h.update(98.5)).isTrue();
        assertThat(h.update(98)).isTrue();
    }

    @Test
    void deactivatesOnlyBelowTheDeactivationThreshold() {
        BatteryFullHysteresis h = make();
        h.update(100);
        assertThat(h.update(97.9)).isFalse();
    }

    @Test
    void doesNotReactivateUntilBackTo100AfterDeactivating() {
        BatteryFullHysteresis h = make();
        h.update(100);
        h.update(97); // deactivates
        assertThat(h.isActive()).isFalse();
        assertThat(h.update(99)).isFalse();
        assertThat(h.update(99.9)).isFalse();
        assertThat(h.update(100)).isTrue();
    }

    @Test
    void initialActiveStateCanBeSeeded() {
        BatteryFullHysteresis h = make(true);
        assertThat(h.update(99)).isTrue();
        assertThat(h.update(97)).isFalse();
    }

    @Test
    void exportWhileSocNearFullActivatesEarly() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, -60.0)).isTrue();
    }

    @Test
    void exportBelowDeactivateThresholdDoesNotActivateEarly() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(97, -60.0)).isFalse();
    }

    @Test
    void smallExportDoesNotActivateEarly() {
        BatteryFullHysteresis h = make(); // default exportConfirmsFullW=50.0
        assertThat(h.update(99, -20.0)).isFalse();
    }

    @Test
    void importDoesNotActivateEarly() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, 30.0)).isFalse();
    }

    @Test
    void noGridPowerReadingFallsBackToSocOnly() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, (Double) null)).isFalse();
        assertThat(h.update(99)).isFalse();
    }

    @Test
    void exportConfirmsFullDisabledWhenThresholdIsZero() {
        BatteryFullHysteresis h = new BatteryFullHysteresis(100.0, 98.0, false, 0.0);
        assertThat(h.update(99, -500.0)).isFalse();
    }
}
