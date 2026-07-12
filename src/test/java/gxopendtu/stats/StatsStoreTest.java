package gxopendtu.stats;

import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.LiveState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatsStoreTest {

    private static int countRows(Path dbPath, String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void persistSnapshotWritesSampleAndInverterRows(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("stats.db");
        LiveState liveState = new LiveState();
        liveState.updateDecision(
                80.0,
                "ON",
                300.0,
                List.of(Map.of("serial", "a", "actual_w", 210.0), Map.of("serial", "b", "actual_w", 90.0)),
                -548.0,
                51.23,
                -10.7,
                false,
                null);
        liveState.recordGrid(100.0, 95.0);
        HourlyEnergyHistory energyHistory = new HourlyEnergyHistory();
        energyHistory.record(10.0, 2.0, 1_800_000_000.0);

        try (StatsStore store = new StatsStore(dbPath)) {
            store.persistSnapshot(liveState, energyHistory);
        }

        assertThat(countRows(dbPath, "samples")).isEqualTo(1);
        assertThat(countRows(dbPath, "inverter_samples")).isEqualTo(2);
        assertThat(countRows(dbPath, "hourly_energy")).isEqualTo(1);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT grid_raw_w, grid_ema_w, soc_pct, battery_power_w, battery_voltage_v, "
                                + "battery_current_a, injection_control FROM samples")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble("grid_raw_w")).isEqualTo(100.0);
            assertThat(rs.getDouble("grid_ema_w")).isEqualTo(95.0);
            assertThat(rs.getDouble("soc_pct")).isEqualTo(80.0);
            assertThat(rs.getDouble("battery_power_w")).isEqualTo(-548.0);
            assertThat(rs.getDouble("battery_voltage_v")).isEqualTo(51.23);
            assertThat(rs.getDouble("battery_current_a")).isEqualTo(-10.7);
            assertThat(rs.getString("injection_control")).isEqualTo("ON");
        }
    }

    @Test
    void persistSnapshotOfEmptyLiveStateDoesNothing(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("stats.db");
        LiveState liveState = new LiveState();
        HourlyEnergyHistory energyHistory = new HourlyEnergyHistory();

        try (StatsStore store = new StatsStore(dbPath)) {
            store.persistSnapshot(liveState, energyHistory);
        }

        assertThat(countRows(dbPath, "samples")).isZero();
    }

    @Test
    void pruneOlderThanDeletesOnlyOldRows(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("stats.db");
        try (StatsStore store = new StatsStore(dbPath)) {
            store.recordSample(1_000.0, 10.0, 10.0, null, null, null, null, "ON", Map.of("a", 100.0));
            store.recordSample(2_000_000.0, 20.0, 20.0, null, null, null, null, "ON", Map.of("a", 200.0));
            store.upsertHourlyEnergy(List.of(
                    Map.of("hour", 0.0, "from_kwh", 1.0, "to_kwh", 0.0),
                    Map.of("hour", 2_000_000.0, "from_kwh", 2.0, "to_kwh", 0.0)));

            store.pruneOlderThan(1_000_000.0);
        }

        assertThat(countRows(dbPath, "samples")).isEqualTo(1);
        assertThat(countRows(dbPath, "inverter_samples")).isEqualTo(1);
        assertThat(countRows(dbPath, "hourly_energy")).isEqualTo(1);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT t FROM samples")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("t")).isEqualTo(2_000_000L);
        }
    }

    @Test
    void upsertHourlyEnergyIsIdempotentPerHour(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("stats.db");
        try (StatsStore store = new StatsStore(dbPath)) {
            store.upsertHourlyEnergy(List.of(Map.of("hour", 3600.0, "from_kwh", 1.0, "to_kwh", 0.5)));
            store.upsertHourlyEnergy(List.of(Map.of("hour", 3600.0, "from_kwh", 2.5, "to_kwh", 0.7)));
        }

        assertThat(countRows(dbPath, "hourly_energy")).isEqualTo(1);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT from_kwh, to_kwh FROM hourly_energy")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble("from_kwh")).isEqualTo(2.5); // last write wins, not accumulated
            assertThat(rs.getDouble("to_kwh")).isEqualTo(0.7);
        }
    }

    @Test
    void reopeningExistingDatabaseReusesSchema(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("stats.db");
        try (StatsStore store = new StatsStore(dbPath)) {
            store.recordSample(1_000.0, 10.0, 10.0, null, null, null, null, "ON", Map.of());
        }
        try (StatsStore store = new StatsStore(dbPath)) {
            store.recordSample(2_000.0, 20.0, 20.0, null, null, null, null, "ON", Map.of());
        }

        assertThat(countRows(dbPath, "samples")).isEqualTo(2);
    }

    @Test
    void addsBatteryVoltageAndCurrentColumnsToAPreExistingDatabase(@TempDir Path tmpDir) throws Exception {
        // Simulates a stats.db created before battery_voltage_v/battery_current_a
        // existed: CREATE TABLE IF NOT EXISTS alone would silently no-op against
        // this table, so opening StatsStore must add the missing columns itself.
        Path dbPath = tmpDir.resolve("stats.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE samples (t INTEGER PRIMARY KEY, grid_raw_w REAL, grid_ema_w REAL, "
                            + "soc_pct REAL, battery_power_w REAL, injection_control TEXT)");
            stmt.execute("INSERT INTO samples (t, grid_raw_w, grid_ema_w) VALUES (500, 1.0, 1.0)");
        }

        try (StatsStore store = new StatsStore(dbPath)) {
            store.recordSample(1_000.0, 10.0, 10.0, null, null, 51.0, -5.0, "ON", Map.of());
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT t, battery_voltage_v, battery_current_a FROM samples ORDER BY t")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("t")).isEqualTo(500L);
            rs.getDouble("battery_voltage_v");
            assertThat(rs.wasNull()).isTrue(); // pre-existing row: column added, but value unknown
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("t")).isEqualTo(1000L);
            assertThat(rs.getDouble("battery_voltage_v")).isEqualTo(51.0);
            assertThat(rs.getDouble("battery_current_a")).isEqualTo(-5.0);
        }
    }
}
