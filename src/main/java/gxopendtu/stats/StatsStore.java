package gxopendtu.stats;

import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.LiveState;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-term (multi-year) persistence of dashboard curves to a local SQLite
 * file (stats.db, next to config.json) -- separate from the in-memory
 * {@link LiveState}/{@link HourlyEnergyHistory} ring buffers used for the
 * live view, which stay ephemeral (~30 min / 48 h). Written at a much
 * coarser interval (config.stats.intervalS, default 5 min): long-term trend
 * curves spanning months or years don't need per-tick resolution, and this
 * keeps both write load and database size low over a 2-year retention.
 *
 * Written from two places, both threads: periodically from the control loop
 * thread (see loop.ControlLoop.run) and immediately from an HTTP handler
 * thread when "Enregistrer et appliquer" is pressed (see
 * webui.ConfigPageHandler), so a restart never loses more than one
 * interval's worth of data. All operations are serialized behind a single
 * lock -- SQLite itself serializes writes at the file level, but this keeps
 * a multi-statement operation (e.g. the per-inverter batch insert) from
 * interleaving with a concurrent call from the other thread.
 *
 * Never throws on a failed write/prune (logs and drops the sample instead):
 * a hiccup persisting long-term stats must never interrupt the live control
 * loop. Opening the database (constructor) is the one operation allowed to
 * throw, since there's nothing sensible to run without it.
 */
public final class StatsStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");

    private final ReentrantLock lock = new ReentrantLock();
    private final Connection connection;

    public StatsStore(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS samples ("
                                + "t INTEGER PRIMARY KEY, "
                                + "grid_raw_w REAL, "
                                + "grid_ema_w REAL, "
                                + "soc_pct REAL, "
                                + "battery_power_w REAL, "
                                + "injection_control TEXT)");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS inverter_samples ("
                                + "t INTEGER, "
                                + "serial TEXT, "
                                + "actual_w REAL, "
                                + "PRIMARY KEY (t, serial))");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS hourly_energy ("
                                + "hour INTEGER PRIMARY KEY, "
                                + "from_kwh REAL, "
                                + "to_kwh REAL)");
            }
        } catch (SQLException e) {
            throw new StatsStoreException("failed to open stats database at " + dbPath, e);
        }
    }

    /** Reads the latest LiveState sample and the current HourlyEnergyHistory buckets, and persists both. */
    public void persistSnapshot(LiveState liveState, HourlyEnergyHistory energyHistory) {
        recordSampleFromLiveState(liveState.snapshotSince(0).latest());
        upsertHourlyEnergy(energyHistory.snapshot());
    }

    @SuppressWarnings("unchecked")
    private void recordSampleFromLiveState(Map<String, Object> sample) {
        if (sample == null) {
            return;
        }
        double t = (Double) sample.get("t");
        double gridRawW = (Double) sample.get("grid_raw_w");
        double gridEmaW = (Double) sample.get("grid_ema_w");
        Double socPct = (Double) sample.get("soc_pct");
        Double batteryPowerW = (Double) sample.get("battery_power_w");
        String injectionControl = (String) sample.get("injection_control");

        Map<String, Double> inverterActualW = new LinkedHashMap<>();
        Object inverters = sample.get("inverters");
        if (inverters instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> inv) {
                    Object serial = inv.get("serial");
                    Object actualW = inv.get("actual_w");
                    if (serial != null && actualW instanceof Double d) {
                        inverterActualW.put(String.valueOf(serial), d);
                    }
                }
            }
        }
        recordSample(t, gridRawW, gridEmaW, socPct, batteryPowerW, injectionControl, inverterActualW);
    }

    void recordSample(
            double t,
            double gridRawW,
            double gridEmaW,
            Double socPct,
            Double batteryPowerW,
            String injectionControl,
            Map<String, Double> inverterActualW) {
        lock.lock();
        try {
            long tSeconds = Math.round(t);
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT OR REPLACE INTO samples "
                            + "(t, grid_raw_w, grid_ema_w, soc_pct, battery_power_w, injection_control) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setLong(1, tSeconds);
                stmt.setDouble(2, gridRawW);
                stmt.setDouble(3, gridEmaW);
                setNullableDouble(stmt, 4, socPct);
                setNullableDouble(stmt, 5, batteryPowerW);
                stmt.setString(6, injectionControl);
                stmt.executeUpdate();
            }
            if (inverterActualW != null && !inverterActualW.isEmpty()) {
                try (PreparedStatement stmt = connection.prepareStatement(
                        "INSERT OR REPLACE INTO inverter_samples (t, serial, actual_w) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, Double> entry : inverterActualW.entrySet()) {
                        stmt.setLong(1, tSeconds);
                        stmt.setString(2, entry.getKey());
                        stmt.setDouble(3, entry.getValue());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to persist stats sample", e);
        } finally {
            lock.unlock();
        }
    }

    void upsertHourlyEnergy(List<Map<String, Object>> buckets) {
        lock.lock();
        try (PreparedStatement stmt =
                connection.prepareStatement("INSERT OR REPLACE INTO hourly_energy (hour, from_kwh, to_kwh) VALUES (?, ?, ?)")) {
            for (Map<String, Object> bucket : buckets) {
                stmt.setLong(1, Math.round((Double) bucket.get("hour")));
                stmt.setDouble(2, (Double) bucket.get("from_kwh"));
                stmt.setDouble(3, (Double) bucket.get("to_kwh"));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to persist hourly energy", e);
        } finally {
            lock.unlock();
        }
    }

    /** Deletes every row strictly older than the given epoch-seconds cutoff, in all three tables. */
    public void pruneOlderThan(double cutoffEpochSeconds) {
        lock.lock();
        try {
            long cutoff = Math.round(cutoffEpochSeconds);
            for (String sql : List.of(
                    "DELETE FROM samples WHERE t < ?",
                    "DELETE FROM inverter_samples WHERE t < ?",
                    "DELETE FROM hourly_energy WHERE hour < ?")) {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setLong(1, cutoff);
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to prune old stats", e);
        } finally {
            lock.unlock();
        }
    }

    private static void setNullableDouble(PreparedStatement stmt, int index, Double value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.REAL);
        } else {
            stmt.setDouble(index, value);
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to close stats database", e);
        } finally {
            lock.unlock();
        }
    }
}
