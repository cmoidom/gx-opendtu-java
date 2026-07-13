package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InverterEnergyHistoryTest {

    @Test
    void firstReadingCreatesAnEmptyBucketNotABogusTotal() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 144.0), 1704110400.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0)).isEqualTo(Map.of("hour", 1704110400.0));
    }

    @Test
    void subsequentReadingInSameHourAccumulatesDeltaPerSerial() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 100.0, "b", 50.0), 1704110400.0); // hour start
        history.record(Map.of("a", 110.0, "b", 55.0), 1704110700.0); // +5 min, same hour
        history.record(Map.of("a", 125.0, "b", 60.0), 1704111000.0); // +10 min, same hour
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("a")).isEqualTo(25.0);
        assertThat(snap.get(0).get("b")).isEqualTo(10.0);
    }

    @Test
    void newHourStartsANewBucket() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 100.0), 1704110400.0); // 12:00
        history.record(Map.of("a", 101.0), 1704113900.0); // 12:58
        history.record(Map.of("a", 103.0), 1704114100.0); // 13:01 -> new hour
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(snap.get(0).get("hour")).isEqualTo(1704110400.0);
        assertThat(snap.get(0).get("a")).isEqualTo(1.0);
        assertThat(snap.get(1).get("hour")).isEqualTo(1704114000.0);
        assertThat(snap.get(1).get("a")).isEqualTo(2.0);
    }

    @Test
    void counterResetSkipsTheBogusNegativeDeltaForThatSerialOnly() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 500.0, "b", 10.0), 1704110400.0);
        // "a" resets (midnight/restart), "b" keeps counting up normally.
        history.record(Map.of("a", 5.0, "b", 12.0), 1704110700.0);
        history.record(Map.of("a", 6.0, "b", 14.0), 1704111000.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("a")).isEqualTo(1.0); // only the post-reset delta (6-5) counted
        assertThat(snap.get(0).get("b")).isEqualTo(4.0); // never reset, both deltas counted
    }

    @Test
    void newSerialAddedMidStreamStartsFreshWithoutABogusTotal() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 100.0), 1704110400.0);
        // "b" appears for the first time here -- its 400.0 reading must not
        // be attributed wholesale to this bucket, same as a fresh restart.
        history.record(Map.of("a", 105.0, "b", 400.0), 1704110700.0);
        history.record(Map.of("a", 110.0, "b", 410.0), 1704111000.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("a")).isEqualTo(10.0);
        assertThat(snap.get(0).get("b")).isEqualTo(10.0);
    }

    @Test
    void retainHoursBoundsTheBucketCount() {
        InverterEnergyHistory history = new InverterEnergyHistory(2);
        double base = 1704110400.0;
        for (int i = 0; i < 5; i++) {
            history.record(Map.of("a", (double) i), base + i * 3600);
        }
        assertThat(history.snapshot()).hasSize(3); // retainHours + 1 (in-progress hour)
    }

    @Test
    void seedBucketsPopulatesSnapshotInOrder() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.seedBuckets(List.of(
                Map.of("hour", 1704110400.0, "a", 3.0),
                Map.of("hour", 1704114000.0, "a", 2.0, "b", 1.0)));

        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(snap.get(0).get("hour")).isEqualTo(1704110400.0);
        assertThat(snap.get(0).get("a")).isEqualTo(3.0);
        assertThat(snap.get(1).get("hour")).isEqualTo(1704114000.0);
        assertThat(snap.get(1).get("b")).isEqualTo(1.0);
    }

    @Test
    void seedBucketsThenRecordReconcilesLikeAFreshRestart() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.seedBuckets(List.of(Map.of("hour", 1704110400.0, "a", 3.0)));

        // The next real reading has no counter baseline yet (lastYieldDayWh
        // wasn't seeded) -- same "first reading" no-bogus-delta behavior as
        // a plain fresh restart.
        history.record(Map.of("a", 500.0), 1704110700.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("a")).isEqualTo(3.0); // unchanged, no bogus delta added
    }

    @Test
    void seedBucketsWithEmptyListIsNoOp() {
        InverterEnergyHistory history = new InverterEnergyHistory();
        history.record(Map.of("a", 100.0), 1704110400.0);

        history.seedBuckets(List.of());

        assertThat(history.snapshot()).hasSize(1);
    }
}
