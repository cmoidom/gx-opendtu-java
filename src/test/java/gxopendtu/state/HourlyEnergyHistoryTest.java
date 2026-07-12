package gxopendtu.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HourlyEnergyHistoryTest {

    @Test
    void hourStartFloorsToTheHour() {
        // 2024-01-01 12:34:56 UTC epoch -> floored to 12:00:00 UTC
        assertThat(HourlyEnergyHistory.hourStart(1704112496.0)).isEqualTo(1704110400.0);
    }

    @Test
    void firstReadingCreatesAnEmptyBucketNotABogusTotal() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.record(12345.0, 6789.0, 1704110400.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0)).isEqualTo(Map.of("hour", 1704110400.0, "from_kwh", 0.0, "to_kwh", 0.0));
    }

    @Test
    void subsequentReadingInSameHourAccumulatesDelta() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.record(100.0, 50.0, 1704110400.0); // hour start
        history.record(100.5, 50.2, 1704110700.0); // +5 min, same hour
        history.record(101.0, 50.5, 1704111000.0); // +10 min, same hour
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat((double) snap.get(0).get("from_kwh")).isEqualTo(1.0);
        assertThat((double) snap.get(0).get("to_kwh")).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void newHourStartsANewBucket() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.record(100.0, 50.0, 1704110400.0); // 12:00
        history.record(101.0, 50.5, 1704113900.0); // 12:58
        history.record(102.0, 51.0, 1704114100.0); // 13:01 -> new hour
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(snap.get(0).get("hour")).isEqualTo(1704110400.0);
        assertThat(snap.get(0).get("from_kwh")).isEqualTo(1.0);
        assertThat(snap.get(1).get("hour")).isEqualTo(1704114000.0);
        assertThat(snap.get(1).get("from_kwh")).isEqualTo(1.0);
    }

    @Test
    void counterResetSkipsTheBogusNegativeDelta() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.record(500.0, 200.0, 1704110400.0);
        history.record(5.0, 200.0, 1704110700.0); // counter reset/replaced
        history.record(6.0, 200.5, 1704111000.0); // resumes counting up from new baseline
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("from_kwh")).isEqualTo(1.0);
        assertThat((double) snap.get(0).get("to_kwh")).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void retainHoursBoundsTheBucketCount() {
        HourlyEnergyHistory history = new HourlyEnergyHistory(2);
        double base = 1704110400.0;
        for (int i = 0; i < 5; i++) {
            history.record(i, i, base + i * 3600);
        }
        assertThat(history.snapshot()).hasSize(3); // retainHours + 1 (in-progress hour)
    }

    @Test
    void seedBucketsPopulatesSnapshotInOrder() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.seedBuckets(List.of(
                Map.of("hour", 1704110400.0, "from_kwh", 3.0, "to_kwh", 0.5),
                Map.of("hour", 1704114000.0, "from_kwh", 2.0, "to_kwh", 0.2)));

        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(snap.get(0).get("hour")).isEqualTo(1704110400.0);
        assertThat(snap.get(0).get("from_kwh")).isEqualTo(3.0);
        assertThat(snap.get(1).get("hour")).isEqualTo(1704114000.0);
    }

    @Test
    void seedBucketsThenRecordReconcilesLikeAFreshRestart() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.seedBuckets(List.of(Map.of("hour", 1704110400.0, "from_kwh", 3.0, "to_kwh", 0.5)));

        // The next real reading has no cumulative-counter baseline yet
        // (lastFromKwh/lastToKwh weren't seeded) -- same "first reading"
        // no-bogus-delta behavior as a plain fresh restart.
        history.record(500.0, 200.0, 1704110700.0);
        List<Map<String, Object>> snap = history.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("from_kwh")).isEqualTo(3.0); // unchanged, no bogus delta added
    }

    @Test
    void seedBucketsWithEmptyListIsNoOp() {
        HourlyEnergyHistory history = new HourlyEnergyHistory();
        history.record(100.0, 50.0, 1704110400.0);

        history.seedBuckets(List.of());

        assertThat(history.snapshot()).hasSize(1);
    }
}
