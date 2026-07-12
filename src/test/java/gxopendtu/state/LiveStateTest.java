package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveStateTest {

    @Test
    void recordGridCarriesForwardLatestDecision() {
        LiveState state = new LiveState(10);
        state.updateDecision(80.0, "ON", 300.0, List.of(Map.of("serial", "a")));
        state.recordGrid(10.0, 8.0);

        LiveState.Snapshot snap = state.snapshotSince(0.0);
        assertThat(snap.history()).hasSize(1);
        Map<String, Object> sample = snap.history().get(0);
        assertThat(sample.get("grid_raw_w")).isEqualTo(10.0);
        assertThat(sample.get("grid_ema_w")).isEqualTo(8.0);
        assertThat(sample.get("soc_pct")).isEqualTo(80.0);
        assertThat(sample.get("injection_control")).isEqualTo("ON");
        assertThat(sample.get("consigne_w")).isEqualTo(300.0);
        assertThat(sample.get("inverters")).isEqualTo(List.of(Map.of("serial", "a")));
        assertThat(snap.latest()).isEqualTo(sample);
    }

    @Test
    void recordGridBeforeAnyDecisionHasNullFields() {
        LiveState state = new LiveState(10);
        state.recordGrid(5.0, 5.0);

        Map<String, Object> sample = state.snapshotSince(0.0).history().get(0);
        assertThat(sample.get("soc_pct")).isNull();
        assertThat(sample.get("injection_control")).isNull();
        assertThat(sample.get("consigne_w")).isNull();
        assertThat(sample.get("inverters")).isEqualTo(List.of());
    }

    @Test
    void ringBufferRespectsMaxSamples() {
        LiveState state = new LiveState(3);
        for (int i = 0; i < 5; i++) {
            state.recordGrid(i, i);
        }

        List<Map<String, Object>> history = state.snapshotSince(0.0).history();
        assertThat(history).hasSize(3);
        assertThat(history.stream().map(s -> s.get("grid_raw_w"))).containsExactly(2.0, 3.0, 4.0);
    }

    @Test
    void snapshotSinceFiltersOlderSamples() throws InterruptedException {
        LiveState state = new LiveState(10);
        state.recordGrid(1.0, 1.0);
        double firstT = (Double) state.snapshotSince(0.0).latest().get("t");
        Thread.sleep(20); // ensure a distinct, strictly-later timestamp regardless of clock resolution
        state.recordGrid(2.0, 2.0);

        LiveState.Snapshot snap = state.snapshotSince(firstT);
        assertThat(snap.history()).hasSize(1);
        assertThat(snap.history().get(0).get("grid_raw_w")).isEqualTo(2.0);
        assertThat(snap.latest().get("grid_raw_w")).isEqualTo(2.0);
    }

    @Test
    void mutatingInvertersListAfterCallDoesNotAffectRecordedSample() {
        LiveState state = new LiveState(10);
        List<Map<String, Object>> inverters = new ArrayList<>();
        inverters.add(Map.of("serial", "a"));
        state.updateDecision(null, "ON", 100.0, inverters);
        inverters.add(Map.of("serial", "b"));
        state.recordGrid(1.0, 1.0);

        Map<String, Object> sample = state.snapshotSince(0.0).history().get(0);
        assertThat(sample.get("inverters")).isEqualTo(List.of(Map.of("serial", "a")));
    }
}
