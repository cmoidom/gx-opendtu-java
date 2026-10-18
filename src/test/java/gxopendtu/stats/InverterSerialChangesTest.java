package gxopendtu.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InverterSerialChangesTest {

    @Test
    void aRenamedInverterKeepsItsHistoryUnderTheNewSerialAndARemovedOneIsPurged(@TempDir Path tmpDir) throws Exception {
        Path configPath = tmpDir.resolve("config.json");
        try (StatsStore store = new StatsStore(tmpDir.resolve("stats.db"))) {
            store.recordSample(1_000.0, 0.0, 0.0, null, null, null, null, "ON", Map.of("old", 100.0, "gone", 50.0));
            store.recordSample(2_000.0, 0.0, 0.0, null, null, null, null, "ON", Map.of("new", 120.0));
            // the swap hour exists under both serials: summed
            store.upsertInverterHourlyEnergy(List.of(
                    Map.of("hour", 3600.0, "old", 300.0, "gone", 40.0),
                    Map.of("hour", 7200.0, "old", 100.0, "new", 50.0)));

            InverterSerialChanges.recordPending(configPath, List.of(InverterSerialChanges.Change.rename("old", "new")));
            InverterSerialChanges.recordPending(configPath, List.of(InverterSerialChanges.Change.purge("gone")));
            assertThat(InverterSerialChanges.loadPending(configPath)).hasSize(2); // appended, not overwritten

            InverterSerialChanges.applyPending(configPath, store);

            List<Map<String, Object>> samples = store.loadRecentSamples(10);
            assertThat(serials(samples.get(0))).containsExactly("new"); // t=1000: was "old", "gone" purged
            assertThat(serials(samples.get(1))).containsExactly("new");
            List<Map<String, Object>> hourly = store.loadInverterHourlyEnergy(0.0);
            assertThat(hourly.get(0)).containsEntry("new", 300.0).doesNotContainKeys("old", "gone");
            assertThat(hourly.get(1)).containsEntry("new", 150.0).doesNotContainKey("old");
        }
        assertThat(Files.exists(tmpDir.resolve("inverter_changes.json"))).isFalse(); // done, not re-applied
    }

    @Test
    void nothingPendingIsANoOp(@TempDir Path tmpDir) {
        try (StatsStore store = new StatsStore(tmpDir.resolve("stats.db"))) {
            InverterSerialChanges.applyPending(tmpDir.resolve("config.json"), store);
        }
        assertThat(Files.exists(tmpDir.resolve("inverter_changes.json"))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> serials(Map<String, Object> sample) {
        return ((List<Map<String, Object>>) sample.get("inverters")).stream().map(inv -> inv.get("serial")).toList();
    }
}
