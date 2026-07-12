package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualOverrideTest {

    @Test
    void inactiveByDefault() {
        ManualOverride override = new ManualOverride();
        assertThat(override.activePct()).isNull();
        assertThat(override.snapshot()).isNull();
    }

    @Test
    void setMakesItActive() {
        ManualOverride override = new ManualOverride();
        override.set(50.0, 60.0);
        assertThat(override.activePct()).isEqualTo(50.0);
        Map<String, Object> snap = override.snapshot();
        assertThat(snap.get("pct")).isEqualTo(50.0);
        double remaining = (double) snap.get("remaining_s");
        assertThat(remaining).isGreaterThan(0).isLessThanOrEqualTo(60.0);
    }

    @Test
    void clearDeactivatesImmediately() {
        ManualOverride override = new ManualOverride();
        override.set(100.0, 60.0);
        override.clear();
        assertThat(override.activePct()).isNull();
        assertThat(override.snapshot()).isNull();
    }

    @Test
    void expiresAfterDuration() {
        ManualOverride override = new ManualOverride();
        override.set(25.0, -1.0); // already expired
        assertThat(override.activePct()).isNull();
        assertThat(override.snapshot()).isNull();
    }

    @Test
    void activePctClearsStateOnceExpired() {
        ManualOverride override = new ManualOverride();
        override.set(75.0, -1.0);
        override.activePct(); // first call observes expiry and clears
        assertThat(override.snapshot()).isNull();
    }

    @Test
    void setAgainReplacesPreviousValueAndDuration() {
        ManualOverride override = new ManualOverride();
        override.set(25.0, 60.0);
        override.set(100.0, 60.0);
        assertThat(override.activePct()).isEqualTo(100.0);
    }

    @Test
    void injectionModeDefaultsToAuto() {
        assertThat(new InjectionModeOverride().getMode()).isEqualTo(InjectionModeOverride.Mode.AUTO);
    }

    @Test
    void injectionModeCanBeSetToOnOrOff() {
        InjectionModeOverride mode = new InjectionModeOverride();
        mode.setMode(InjectionModeOverride.Mode.ON);
        assertThat(mode.getMode()).isEqualTo(InjectionModeOverride.Mode.ON);
        mode.setMode(InjectionModeOverride.Mode.OFF);
        assertThat(mode.getMode()).isEqualTo(InjectionModeOverride.Mode.OFF);
        mode.setMode(InjectionModeOverride.Mode.AUTO);
        assertThat(mode.getMode()).isEqualTo(InjectionModeOverride.Mode.AUTO);
    }

    @Test
    void injectionModeRejectsInvalidValue() {
        InjectionModeOverride mode = new InjectionModeOverride();
        assertThatThrownBy(() -> mode.setMode("BOGUS")).isInstanceOf(IllegalArgumentException.class);
    }
}
