package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveStateTest {

    /** Shape mirrors stats.StatsStore#loadRecentSamples's output. */
    private static Map<String, Object> seedSample(double t, Double socPct, String injectionControl) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("t", t);
        sample.put("grid_raw_w", 10.0);
        sample.put("grid_ema_w", 9.0);
        sample.put("soc_pct", socPct);
        sample.put("battery_power_w", -50.0);
        sample.put("battery_voltage_v", 51.2);
        sample.put("battery_current_a", -1.0);
        sample.put("injection_control", injectionControl);
        sample.put("consigne_w", null);
        sample.put("inverters", List.of(Map.of("serial", "a", "actual_w", 100.0)));
        sample.put("min_inverter_floor_warning", false);
        sample.put("recommended_min_inverter_pct", null);
        sample.put("sunspec_target_w", 500.0);
        sample.put("backfilled", true);
        return sample;
    }

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
        assertThat(sample.get("backfilled")).isEqualTo(false); // distinguishes live samples from seedHistory's
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

    @Test
    void seedHistoryPopulatesBufferAndCarriesForwardLatestFields() {
        LiveState state = new LiveState(10);
        state.seedHistory(List.of(seedSample(1000.0, 79.0, "ON"), seedSample(1300.0, 80.0, "ON")));

        List<Map<String, Object>> history = state.snapshotSince(0.0).history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("t")).isEqualTo(1000.0);
        assertThat(history.get(1).get("t")).isEqualTo(1300.0);

        // A live tick right after seeding carries forward the last seeded
        // sample's decision fields, same as after a real updateDecision call.
        state.recordGrid(11.0, 10.0);
        Map<String, Object> latest = state.snapshotSince(0.0).latest();
        assertThat(latest.get("grid_raw_w")).isEqualTo(11.0);
        assertThat(latest.get("soc_pct")).isEqualTo(80.0);
        assertThat(latest.get("injection_control")).isEqualTo("ON");
        assertThat(latest.get("battery_voltage_v")).isEqualTo(51.2);
        assertThat(latest.get("battery_current_a")).isEqualTo(-1.0);
        assertThat(latest.get("inverters")).isEqualTo(List.of(Map.of("serial", "a", "actual_w", 100.0)));
        assertThat(latest.get("sunspec_target_w")).isEqualTo(500.0);
    }

    @Test
    void updateSunSpecTargetCarriesForwardIndependentlyOfUpdateDecision() {
        LiveState state = new LiveState(10);
        state.updateDecision(80.0, "ON", 300.0, List.of(Map.of("serial", "a")));
        state.updateSunSpecTarget(650.0);
        state.recordGrid(1.0, 1.0);

        Map<String, Object> sample = state.snapshotSince(0.0).history().get(0);
        assertThat(sample.get("sunspec_target_w")).isEqualTo(650.0);
        assertThat(sample.get("consigne_w")).isEqualTo(300.0); // unaffected, independent sticky field

        // A later updateDecision call (control loop's own cadence) must not reset it.
        state.updateDecision(81.0, "ON", 400.0, List.of(Map.of("serial", "a")));
        state.recordGrid(2.0, 2.0);
        Map<String, Object> secondSample = state.snapshotSince(0.0).latest();
        assertThat(secondSample.get("sunspec_target_w")).isEqualTo(650.0);
    }

    @Test
    void seedHistoryRespectsMaxSamples() {
        LiveState state = new LiveState(2);
        state.seedHistory(List.of(seedSample(1000.0, 78.0, "ON"), seedSample(1100.0, 79.0, "ON"), seedSample(1200.0, 80.0, "ON")));

        List<Map<String, Object>> history = state.snapshotSince(0.0).history();
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(s -> s.get("t"))).containsExactly(1100.0, 1200.0);
    }

    @Test
    void seedHistoryWithEmptyListIsNoOp() {
        LiveState state = new LiveState(10);
        state.updateDecision(80.0, "ON", 300.0, List.of(Map.of("serial", "a")));
        state.recordGrid(1.0, 1.0);

        state.seedHistory(List.of());

        assertThat(state.snapshotSince(0.0).history()).hasSize(1);
    }
}
