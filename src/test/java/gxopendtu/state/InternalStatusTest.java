package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalStatusTest {

    private static void updateControl(InternalStatus status, double error, double piIntegral) {
        status.updateControl(error, piIntegral, 200.0, 200.0, false, 0.0, 100.0, 200.0, true, 200.0, Map.of(), Map.of(), false, null);
    }

    @Test
    void updateControlPopulatesLatestAndAppendsHistory() {
        InternalStatus status = new InternalStatus(10);
        status.updateControl(20.0, 10.0, 230.0, 300.0, true, 100.0, 100.0, 300.0, true, 300.0,
                Map.of("a", 600.0), Map.of("a", 600.0), true, 42.0);

        InternalStatus.Snapshot snap = status.snapshotSince(0.0);
        assertThat(snap.latest().get("error")).isEqualTo(20.0);
        assertThat(snap.latest().get("pi_integral")).isEqualTo(10.0);
        assertThat(snap.latest().get("raw_target_before_floor")).isEqualTo(230.0);
        assertThat(snap.latest().get("raw_target_after_floor")).isEqualTo(300.0);
        assertThat(snap.latest().get("battery_floor_engaged")).isEqualTo(true);
        assertThat(snap.latest().get("battery_discharge_w")).isEqualTo(100.0);
        assertThat(snap.latest().get("ceilings_w")).isEqualTo(Map.of("a", 600.0));
        assertThat(snap.latest().get("min_inverter_floor_warning")).isEqualTo(true);
        assertThat(snap.latest().get("recommended_min_inverter_pct")).isEqualTo(42.0);

        assertThat(snap.history()).hasSize(1);
        assertThat(snap.history().get(0).get("error")).isEqualTo(20.0);
    }

    @Test
    void updateModeMergesIntoLatestWithoutTouchingHistory() {
        InternalStatus status = new InternalStatus(10);
        updateControl(status, 5.0, 1.0);
        status.updateMode(true, 12.5, "AUTO", Map.of("pct", 50.0, "remaining_s", 100.0), 0, false);

        InternalStatus.Snapshot snap = status.snapshotSince(0.0);
        assertThat(snap.latest().get("hysteresis_active")).isEqualTo(true);
        assertThat(snap.latest().get("export_streak_elapsed_s")).isEqualTo(12.5);
        assertThat(snap.latest().get("injection_mode_override")).isEqualTo("AUTO");
        assertThat(snap.latest().get("manual_override")).isEqualTo(Map.of("pct", 50.0, "remaining_s", 100.0));
        assertThat(snap.latest().get("error")).isEqualTo(5.0); // still carried from updateControl
        assertThat(snap.history()).hasSize(1); // updateMode never appends a sample
    }

    @Test
    void ringBufferRespectsMaxSamples() {
        InternalStatus status = new InternalStatus(3);
        for (int i = 0; i < 5; i++) {
            updateControl(status, i, i);
        }

        List<Map<String, Object>> history = status.snapshotSince(0.0).history();
        assertThat(history).hasSize(3);
        assertThat(history.stream().map(s -> s.get("error"))).containsExactly(2.0, 3.0, 4.0);
    }

    @Test
    void snapshotSinceFiltersOlderSamples() throws InterruptedException {
        InternalStatus status = new InternalStatus(10);
        updateControl(status, 1.0, 1.0);
        double firstT = (Double) status.snapshotSince(0.0).history().get(0).get("t");
        Thread.sleep(20); // ensure a distinct, strictly-later timestamp regardless of clock resolution
        updateControl(status, 2.0, 2.0);

        InternalStatus.Snapshot snap = status.snapshotSince(firstT);
        assertThat(snap.history()).hasSize(1);
        assertThat(snap.history().get(0).get("error")).isEqualTo(2.0);
    }

    @Test
    void emptyStatusHasNullLatestFields() {
        InternalStatus status = new InternalStatus(10);
        InternalStatus.Snapshot snap = status.snapshotSince(0.0);
        assertThat(snap.latest()).isEmpty();
        assertThat(snap.history()).isEmpty();
    }
}
