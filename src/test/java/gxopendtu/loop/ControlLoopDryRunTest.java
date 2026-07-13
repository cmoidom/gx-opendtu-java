package gxopendtu.loop;

import gxopendtu.control.CapacityEstimator;
import gxopendtu.control.SoftTargetController;
import gxopendtu.opendtu.LimitStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of tests/test_dry_run.py -- exercises ControlLoop's decision-cycle helpers via a fake OpenDTU client, no real HTTP. */
class ControlLoopDryRunTest {

    private static SoftTargetController makeController() {
        return new SoftTargetController(30, 0.4, 0.0, 100, 0, 5);
    }

    private static CapacityEstimator makeCapacity() {
        return new CapacityEstimator(Map.of("a", 600.0, "b", 400.0), 10);
    }

    private static List<String> captureLogs(Runnable action) {
        Logger logger = Logger.getLogger("gx-opendtu-zero-export");
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
        return messages;
    }

    @Test
    void dryRunNeverCallsOpenDTUWriteEndpoints() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(
                Map.of("a", 200.0, "b", 150.0),
                Map.of(
                        "a", new LimitStatus(100, 600, "Ok"),
                        "b", new LimitStatus(100, 400, "Ok")));
        ControlLoop.decisionCycle(
                client, makeController(), makeCapacity(), List.of("a", "b"), 100.0, 100.0, null, null, null, null, null, null, true, false, 0.0, null);
        assertThat(client.absoluteCalls).isEmpty();
    }

    @Test
    void normalModeCallsOpenDTUOnFirstDecision() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(
                Map.of("a", 200.0, "b", 150.0),
                Map.of(
                        "a", new LimitStatus(100, 600, "Ok"),
                        "b", new LimitStatus(100, 400, "Ok")));
        ControlLoop.decisionCycle(
                client, makeController(), makeCapacity(), List.of("a", "b"), 100.0, 100.0, null, null, null, null, null, null, false, false, 0.0, null);
        assertThat(client.absoluteCalls).hasSize(2);
    }

    @Test
    void dryRunFailsafeNeverCallsOpenDTU() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.applyFailsafe(client, List.of("a", "b"), true);
        assertThat(client.relativeCalls).isEmpty();
    }

    @Test
    void normalFailsafeCurtailsEveryInverterToZero() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.applyFailsafe(client, List.of("a", "b"), false);
        assertThat(client.relativeCalls).containsExactly(Map.entry("a", 0.0), Map.entry("b", 0.0));
    }

    @Test
    void dryRunReleaseForChargingNeverCallsOpenDTU() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.releaseForCharging(client, List.of("a", "b"), true);
        assertThat(client.relativeCalls).isEmpty();
    }

    @Test
    void normalReleaseForChargingUnlocksEveryInverterTo100() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.releaseForCharging(client, List.of("a", "b"), false);
        assertThat(client.relativeCalls).containsExactly(Map.entry("a", 100.0), Map.entry("b", 100.0));
    }

    @Test
    void offStateInvertersPayloadReportsActualPowerUncapped() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of("a", 210.0, "b", 95.0), Map.of())
                .withDataAgeS(Map.of("a", 12.0, "b", 90.0));
        List<Map<String, Object>> payload = ControlLoop.offStateInvertersPayload(
                client, List.of("a", "b"), Map.of("a", 600.0, "b", 400.0), Map.of("a", "Toit Sud"));

        assertThat(payload).hasSize(2);
        Map<String, Object> a = payload.get(0);
        assertThat(a.get("serial")).isEqualTo("a");
        assertThat(a.get("name")).isEqualTo("Toit Sud");
        assertThat(a.get("allocated_w")).isNull();
        assertThat(a.get("actual_w")).isEqualTo(210.0);
        assertThat(a.get("limit_relative_pct")).isEqualTo(100);
        assertThat(a.get("max_power_w")).isEqualTo(600.0);
        assertThat(a.get("acknowledged")).isNull();
        assertThat(a.get("data_age_s")).isEqualTo(12.0);

        Map<String, Object> b = payload.get(1);
        assertThat(b.get("serial")).isEqualTo("b");
        assertThat(b.get("name")).isNull();
        assertThat(b.get("actual_w")).isEqualTo(95.0);
        assertThat(b.get("max_power_w")).isEqualTo(400.0);
        assertThat(b.get("data_age_s")).isEqualTo(90.0); // reported here too, even while OFF -- a stale
        // RF reading is worth flagging regardless of the current injection-control mode.
    }

    @Test
    void offStateInvertersPayloadEmptyOnOpenDTUFailure() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of(), true);
        List<Map<String, Object>> payload =
                ControlLoop.offStateInvertersPayload(client, List.of("a", "b"), Map.of("a", 600.0, "b", 400.0), null);
        assertThat(payload).isEmpty();
    }

    @Test
    void verboseTracesTrueLogsStateLine() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(
                Map.of("a", 200.0, "b", 150.0),
                Map.of(
                        "a", new LimitStatus(100, 600, "Ok"),
                        "b", new LimitStatus(100, 400, "Ok")));
        List<String> logs = captureLogs(() -> ControlLoop.decisionCycle(
                client, makeController(), makeCapacity(), List.of("a", "b"), 100.0, 100.0, null, null, null, null, null, null, true, true, 0.0, null));
        assertThat(logs).anyMatch(m -> m.contains("grid_meter="));
    }

    @Test
    void verboseTracesFalseSuppressesStateLine() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(
                Map.of("a", 200.0, "b", 150.0),
                Map.of(
                        "a", new LimitStatus(100, 600, "Ok"),
                        "b", new LimitStatus(100, 400, "Ok")));
        List<String> logs = captureLogs(() -> ControlLoop.decisionCycle(
                client, makeController(), makeCapacity(), List.of("a", "b"), 100.0, 100.0, null, null, null, null, null, null, true, false, 0.0, null));
        assertThat(logs).noneMatch(m -> m.contains("grid_meter="));
    }

    @Test
    void floorWarningFiresWhenFloorExceedsTargetWhileExporting() {
        ControlLoop.FloorWarning result = ControlLoop.minInverterFloorWarning(
                10.0, -50.0, 0.0, Map.of("a", 60.0, "b", 60.0), Map.of("a", 600.0, "b", 600.0), Map.of("a", 600.0, "b", 600.0), List.of("a", "b"));
        assertThat(result.warning()).isTrue();
        assertThat(result.recommendedPct()).isEqualTo(0.0); // target was 0, so 0% would not have exceeded it
    }

    @Test
    void floorWarningRecommendsNonzeroPctWhenTargetIsPartway() {
        ControlLoop.FloorWarning result = ControlLoop.minInverterFloorWarning(
                10.0, -20.0, 60.0, Map.of("a", 60.0, "b", 60.0), Map.of("a", 600.0, "b", 600.0), Map.of("a", 600.0, "b", 600.0), List.of("a", "b"));
        assertThat(result.warning()).isTrue();
        assertThat(result.recommendedPct()).isEqualTo(5.0); // 60W / 1200W nominal = 5%
    }

    @Test
    void floorWarningSilentWhenNotExporting() {
        ControlLoop.FloorWarning result = ControlLoop.minInverterFloorWarning(
                10.0, 30.0, 0.0, Map.of("a", 60.0, "b", 60.0), Map.of("a", 600.0, "b", 600.0), Map.of("a", 600.0, "b", 600.0), List.of("a", "b"));
        assertThat(result.warning()).isFalse();
        assertThat(result.recommendedPct()).isNull();
    }

    @Test
    void floorWarningSilentWhenFloorDidNotExceedTarget() {
        ControlLoop.FloorWarning result = ControlLoop.minInverterFloorWarning(
                10.0, -5.0, 200.0, Map.of("a", 100.0, "b", 100.0), Map.of("a", 600.0, "b", 600.0), Map.of("a", 600.0, "b", 600.0), List.of("a", "b"));
        assertThat(result.warning()).isFalse();
        assertThat(result.recommendedPct()).isNull();
    }

    @Test
    void floorWarningDisabledWhenMinInverterPctIsZero() {
        ControlLoop.FloorWarning result = ControlLoop.minInverterFloorWarning(
                0.0, -50.0, 0.0, Map.of("a", 0.0, "b", 0.0), Map.of("a", 600.0, "b", 600.0), Map.of("a", 600.0, "b", 600.0), List.of("a", "b"));
        assertThat(result.warning()).isFalse();
        assertThat(result.recommendedPct()).isNull();
    }

    @Test
    void sendManualOverrideSetsEveryInverter() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.sendManualOverride(client, List.of("a", "b"), 50.0, false);
        assertThat(client.relativeCalls).containsExactly(Map.entry("a", 50.0), Map.entry("b", 50.0));
    }

    @Test
    void sendManualOverrideDryRunSendsNothing() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(Map.of(), Map.of());
        ControlLoop.sendManualOverride(client, List.of("a", "b"), 50.0, true);
        assertThat(client.relativeCalls).isEmpty();
    }

    @Test
    void manualOverridePayloadReportsForcedPctAndActualPower() {
        FakeOpenDTUApi client = new FakeOpenDTUApi(
                Map.of("a", 300.0, "b", 190.0),
                Map.of(
                        "a", new LimitStatus(50, 600, "Ok"),
                        "b", new LimitStatus(50, 380, "Pending")))
                .withDataAgeS(Map.of("a", 4.0, "b", 8.0));
        List<Map<String, Object>> payload =
                ControlLoop.manualOverridePayload(client, List.of("a", "b"), 50.0, Map.of("a", 600.0, "b", 380.0), Map.of("a", "Toit Sud"));

        Map<String, Object> a = payload.get(0);
        assertThat(a.get("serial")).isEqualTo("a");
        assertThat(a.get("name")).isEqualTo("Toit Sud");
        assertThat(a.get("allocated_w")).isEqualTo(300L);
        assertThat(a.get("actual_w")).isEqualTo(300.0);
        assertThat(a.get("limit_relative_pct")).isEqualTo(50.0);
        assertThat(a.get("acknowledged")).isEqualTo(true);
        assertThat(a.get("data_age_s")).isEqualTo(4.0);

        Map<String, Object> b = payload.get(1);
        assertThat(b.get("name")).isNull();
        assertThat(b.get("allocated_w")).isEqualTo(190L);
        assertThat(b.get("actual_w")).isEqualTo(190.0);
        assertThat(b.get("acknowledged")).isEqualTo(false);
        assertThat(b.get("data_age_s")).isEqualTo(8.0);
    }
}
