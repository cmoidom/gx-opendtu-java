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
    void exportWhileSocNearFullActivatesOnlyAfterSustainedDuration() {
        BatteryFullHysteresis h = make(); // default exportConfirmsFullDurationS = 60
        assertThat(h.update(99, -60.0, 0.0)).isFalse(); // streak just started
        assertThat(h.update(99, -60.0, 30.0)).isFalse(); // only 30s so far
        assertThat(h.update(99, -60.0, 59.9)).isFalse(); // just under the threshold
        assertThat(h.update(99, -60.0, 60.0)).isTrue(); // sustained for a full 60s
    }

    @Test
    void briefExportSpikeResetsTheStreakAndDoesNotActivate() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, -60.0, 0.0)).isFalse();
        assertThat(h.update(99, -60.0, 50.0)).isFalse(); // 50s into the streak, not yet 60
        assertThat(h.update(99, 10.0, 55.0)).isFalse(); // export stops for one cycle -- streak resets
        assertThat(h.update(99, -60.0, 56.0)).isFalse(); // streak restarts here
        assertThat(h.update(99, -60.0, 115.9)).isFalse(); // 59.9s since the restart
        assertThat(h.update(99, -60.0, 116.0)).isTrue(); // 60s since the restart (116 - 56)
    }

    @Test
    void customDurationIsConfigurable() {
        BatteryFullHysteresis h = new BatteryFullHysteresis(100.0, 98.0, false, 50.0, 10.0);
        assertThat(h.update(99, -60.0, 0.0)).isFalse();
        assertThat(h.update(99, -60.0, 9.9)).isFalse();
        assertThat(h.update(99, -60.0, 10.0)).isTrue();
    }

    @Test
    void exportBelowDeactivateThresholdNeverActivatesRegardlessOfDuration() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(97, -60.0, 0.0)).isFalse();
        assertThat(h.update(97, -60.0, 120.0)).isFalse(); // well past the duration threshold
    }

    @Test
    void smallExportNeverActivatesRegardlessOfDuration() {
        BatteryFullHysteresis h = make(); // default exportConfirmsFullW=50.0
        assertThat(h.update(99, -20.0, 0.0)).isFalse();
        assertThat(h.update(99, -20.0, 120.0)).isFalse();
    }

    @Test
    void importNeverActivatesRegardlessOfDuration() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, 30.0, 0.0)).isFalse();
        assertThat(h.update(99, 30.0, 120.0)).isFalse();
    }

    @Test
    void noGridPowerReadingFallsBackToSocOnly() {
        BatteryFullHysteresis h = make();
        assertThat(h.update(99, null, 0.0)).isFalse();
        assertThat(h.update(99, null, 1000.0)).isFalse();
        assertThat(h.update(99)).isFalse();
    }

    @Test
    void exportConfirmsFullDisabledWhenThresholdIsZero() {
        BatteryFullHysteresis h = new BatteryFullHysteresis(100.0, 98.0, false, 0.0, 60.0);
        assertThat(h.update(99, -500.0, 0.0)).isFalse();
        assertThat(h.update(99, -500.0, 1000.0)).isFalse();
    }
}
