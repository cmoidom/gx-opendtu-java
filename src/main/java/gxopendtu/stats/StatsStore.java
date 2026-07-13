package gxopendtu.stats;

import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-term persistence of dashboard curves to a local SQLite file
 * (stats.db, next to config.json) -- separate from the in-memory
 * {@link LiveState}/{@link HourlyEnergyHistory} ring buffers used for the
 * live view, which stay ephemeral (~30 min / 48 h).
 *
 * Two resolutions, to balance disk usage against being able to zoom into a
 * specific past moment: {@link #recordLatestSample} is called every
 * fast-loop tick (matching the live view's own cadence, config.grid.readIntervalS)
 * for the most recent config.stats.highResRetentionDays, then
 * {@link #downsampleOlderThan} thins anything older down to one row per
 * config.stats.intervalS (both run daily from loop.ControlLoop.run,
 * alongside {@link #pruneOlderThan} which bounds the database to
 * config.stats.retentionDays overall).
 *
 * Written from two places, both threads: continuously from the control loop
 * thread (see loop.ControlLoop.run) and immediately from an HTTP handler
 * thread when "Enregistrer et appliquer" is pressed (see
 * webui.ConfigPageHandler), so a restart never loses more than a couple of
 * seconds of data. All operations are serialized behind a single lock --
 * SQLite itself serializes writes at the file level, but this keeps a
 * multi-statement operation (e.g. the per-inverter batch insert) from
 * interleaving with a concurrent call from the other thread.
 *
 * Never throws on a failed write/prune/downsample (logs and drops the
 * sample instead): a hiccup persisting long-term stats must never interrupt
 * the live control loop. Opening the database (constructor) is the one
 * operation allowed to throw, since there's nothing sensible to run without it.
 */
public final class StatsStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");

    private final ReentrantLock lock = new ReentrantLock();
    private final Connection connection;
    private final Path dbPath;

    public StatsStore(Path dbPath) {
        this.dbPath = dbPath;
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
                                + "battery_voltage_v REAL, "
                                + "battery_current_a REAL, "
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
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS inverter_hourly_energy ("
                                + "hour INTEGER, "
                                + "serial TEXT, "
                                + "wh REAL, "
                                + "PRIMARY KEY (hour, serial))");
                // Migration for databases created before battery_voltage_v/
                // battery_current_a existed -- CREATE TABLE IF NOT EXISTS is a
                // no-op against an already-existing samples table, so a
                // pre-existing stats.db needs these columns added explicitly.
                ensureColumn(statement, "samples", "battery_voltage_v", "REAL");
                ensureColumn(statement, "samples", "battery_current_a", "REAL");
            }
        } catch (SQLException e) {
            throw new StatsStoreException("failed to open stats database at " + dbPath, e);
        }
    }

    private static void ensureColumn(Statement statement, String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /**
     * Reads the latest LiveState sample and the current HourlyEnergyHistory/
     * InverterEnergyHistory buckets, and persists all three.
     */
    public void persistSnapshot(
            LiveState liveState, HourlyEnergyHistory energyHistory, InverterEnergyHistory inverterEnergyHistory) {
        recordLatestSample(liveState);
        upsertHourlyEnergy(energyHistory.snapshot());
        upsertInverterHourlyEnergy(inverterEnergyHistory.snapshot());
    }

    /**
     * Persists just the latest LiveState sample (not the hourly energy
     * buckets) -- called every fast-loop tick (see loop.ControlLoop.run) so
     * stats.db's resolution matches the live view's for the most recent
     * config.stats.highResRetentionDays. Older rows are later thinned down
     * to config.stats.intervalS by {@link #downsampleOlderThan}, which is
     * what actually keeps the database from growing at this cadence forever.
     */
    public void recordLatestSample(LiveState liveState) {
        recordSampleFromLiveState(liveState.snapshotSince(0).latest());
    }

    private void recordSampleFromLiveState(Map<String, Object> sample) {
        if (sample == null) {
            return;
        }
        double t = (Double) sample.get("t");
        double gridRawW = (Double) sample.get("grid_raw_w");
        double gridEmaW = (Double) sample.get("grid_ema_w");
        Double socPct = (Double) sample.get("soc_pct");
        Double batteryPowerW = (Double) sample.get("battery_power_w");
        Double batteryVoltageV = (Double) sample.get("battery_voltage_v");
        Double batteryCurrentA = (Double) sample.get("battery_current_a");
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
        recordSample(t, gridRawW, gridEmaW, socPct, batteryPowerW, batteryVoltageV, batteryCurrentA, injectionControl, inverterActualW);
    }

    /**
     * The most recent {@code limit} samples (oldest first), in the same map
     * shape {@link LiveState#recordGrid}/{@code updateDecision} produce --
     * used to seed {@code LiveState}'s in-memory ring buffer at startup so
     * the dashboard doesn't show empty charts right after a restart (see
     * {@code LiveState.seedHistory}). {@code consigne_w},
     * {@code min_inverter_floor_warning} and {@code recommended_min_inverter_pct}
     * aren't persisted to stats.db (control-loop-only, not meaningful for a
     * coarse long-term view), so they come back as their "not set" default.
     */
    public List<Map<String, Object>> loadRecentSamples(int limit) {
        lock.lock();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT t, grid_raw_w, grid_ema_w, soc_pct, battery_power_w, battery_voltage_v, "
                            + "battery_current_a, injection_control FROM samples ORDER BY t DESC LIMIT ?")) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("t", (double) rs.getLong("t"));
                        sample.put("grid_raw_w", rs.getDouble("grid_raw_w"));
                        sample.put("grid_ema_w", rs.getDouble("grid_ema_w"));
                        sample.put("soc_pct", nullableColumn(rs, "soc_pct"));
                        sample.put("battery_power_w", nullableColumn(rs, "battery_power_w"));
                        sample.put("battery_voltage_v", nullableColumn(rs, "battery_voltage_v"));
                        sample.put("battery_current_a", nullableColumn(rs, "battery_current_a"));
                        sample.put("injection_control", rs.getString("injection_control"));
                        sample.put("consigne_w", null);
                        sample.put("inverters", List.of());
                        sample.put("min_inverter_floor_warning", false);
                        sample.put("recommended_min_inverter_pct", null);
                        // Tells the dashboard this point comes from stats.db's
                        // coarser interval, not the live ~2s cadence -- see
                        // dashboard.html's gap-break logic: two consecutive
                        // backfilled points are always connected by a line,
                        // regardless of how far apart they are, since a
                        // stats.interval_s-sized gap between them is normal,
                        // not a genuine outage.
                        sample.put("backfilled", true);
                        rows.add(sample);
                    }
                }
            }
            Collections.reverse(rows); // DESC query -> chronological order, oldest first
            if (rows.isEmpty()) {
                return rows;
            }

            long minT = Math.round((Double) rows.get(0).get("t"));
            Map<Long, List<Map<String, Object>>> invertersByT = new LinkedHashMap<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT t, serial, actual_w FROM inverter_samples WHERE t >= ? ORDER BY t")) {
                stmt.setLong(1, minT);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong("t");
                        Map<String, Object> inv = new LinkedHashMap<>();
                        inv.put("serial", rs.getString("serial"));
                        inv.put("actual_w", rs.getDouble("actual_w"));
                        invertersByT.computeIfAbsent(t, k -> new ArrayList<>()).add(inv);
                    }
                }
            }
            for (Map<String, Object> sample : rows) {
                long t = Math.round((Double) sample.get("t"));
                sample.put("inverters", invertersByT.getOrDefault(t, List.of()));
            }
            return rows;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to load recent stats for dashboard backfill", e);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Samples strictly within {@code [sinceEpochSeconds, untilEpochSeconds]}
     * (chronological order), same map shape as {@link #loadRecentSamples} --
     * used by {@code webui.HistoryJsonHandler} so the dashboard can page in
     * older history from stats.db's multi-year retention as the user pans/
     * zooms earlier than {@code LiveState}'s ~30 min in-memory window, rather
     * than only ever seeing the one-time startup backfill.
     */
    public List<Map<String, Object>> loadSamplesBetween(double sinceEpochSeconds, double untilEpochSeconds) {
        lock.lock();
        try {
            long since = Math.round(sinceEpochSeconds);
            long until = Math.round(untilEpochSeconds);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT t, grid_raw_w, grid_ema_w, soc_pct, battery_power_w, battery_voltage_v, "
                            + "battery_current_a, injection_control FROM samples WHERE t >= ? AND t <= ? ORDER BY t")) {
                stmt.setLong(1, since);
                stmt.setLong(2, until);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("t", (double) rs.getLong("t"));
                        sample.put("grid_raw_w", rs.getDouble("grid_raw_w"));
                        sample.put("grid_ema_w", rs.getDouble("grid_ema_w"));
                        sample.put("soc_pct", nullableColumn(rs, "soc_pct"));
                        sample.put("battery_power_w", nullableColumn(rs, "battery_power_w"));
                        sample.put("battery_voltage_v", nullableColumn(rs, "battery_voltage_v"));
                        sample.put("battery_current_a", nullableColumn(rs, "battery_current_a"));
                        sample.put("injection_control", rs.getString("injection_control"));
                        sample.put("consigne_w", null);
                        sample.put("inverters", List.of());
                        sample.put("min_inverter_floor_warning", false);
                        sample.put("recommended_min_inverter_pct", null);
                        sample.put("backfilled", true); // see loadRecentSamples's javadoc
                        rows.add(sample);
                    }
                }
            }
            if (rows.isEmpty()) {
                return rows;
            }

            Map<Long, List<Map<String, Object>>> invertersByT = new LinkedHashMap<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT t, serial, actual_w FROM inverter_samples WHERE t >= ? AND t <= ? ORDER BY t")) {
                stmt.setLong(1, since);
                stmt.setLong(2, until);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong("t");
                        Map<String, Object> inv = new LinkedHashMap<>();
                        inv.put("serial", rs.getString("serial"));
                        inv.put("actual_w", rs.getDouble("actual_w"));
                        invertersByT.computeIfAbsent(t, k -> new ArrayList<>()).add(inv);
                    }
                }
            }
            for (Map<String, Object> sample : rows) {
                long t = Math.round((Double) sample.get("t"));
                sample.put("inverters", invertersByT.getOrDefault(t, List.of()));
            }
            return rows;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to load stats range for dashboard history paging", e);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hourly energy buckets since {@code sinceEpochSeconds} (chronological
     * order), in the same map shape {@link HourlyEnergyHistory#snapshot()}
     * produces -- used to seed {@code HourlyEnergyHistory} at startup so the
     * "Energie reseau par heure" bar chart doesn't reset to empty on every
     * restart, same reasoning as {@link #loadRecentSamples}.
     */
    public List<Map<String, Object>> loadHourlyEnergy(double sinceEpochSeconds) {
        lock.lock();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT hour, from_kwh, to_kwh FROM hourly_energy WHERE hour >= ? ORDER BY hour")) {
            stmt.setLong(1, Math.round(sinceEpochSeconds));
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> bucket = new LinkedHashMap<>();
                    bucket.put("hour", (double) rs.getLong("hour"));
                    bucket.put("from_kwh", rs.getDouble("from_kwh"));
                    bucket.put("to_kwh", rs.getDouble("to_kwh"));
                    rows.add(bucket);
                }
            }
            return rows;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to load hourly energy for dashboard backfill", e);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hourly per-inverter energy yield (Wh) since {@code sinceEpochSeconds}
     * (chronological order), in the same flat map shape
     * {@link InverterEnergyHistory#snapshot()} produces ({@code hour} plus
     * one key per serial) -- used to seed {@code InverterEnergyHistory} at
     * startup, same reasoning as {@link #loadHourlyEnergy}.
     */
    public List<Map<String, Object>> loadInverterHourlyEnergy(double sinceEpochSeconds) {
        lock.lock();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT hour, serial, wh FROM inverter_hourly_energy WHERE hour >= ? ORDER BY hour")) {
            stmt.setLong(1, Math.round(sinceEpochSeconds));
            Map<Long, Map<String, Object>> bucketsByHour = new LinkedHashMap<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long hour = rs.getLong("hour");
                    Map<String, Object> bucket =
                            bucketsByHour.computeIfAbsent(hour, h -> new LinkedHashMap<>(Map.of("hour", (double) h)));
                    bucket.put(rs.getString("serial"), rs.getDouble("wh"));
                }
            }
            return new ArrayList<>(bucketsByHour.values());
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to load inverter hourly energy for dashboard backfill", e);
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /** Row count of the {@code samples} table -- shown on the config page alongside {@link #sizeBytes()}. */
    public long sampleCount() {
        lock.lock();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM samples")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to count stats rows", e);
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * On-disk size of the main stats.db file (not counting the WAL/SHM
     * sidecar files SQLite's WAL journal mode creates) -- shown on the
     * config page alongside {@link #sampleCount()}.
     */
    public long sizeBytes() {
        try {
            return Files.size(dbPath);
        } catch (IOException e) {
            return -1;
        }
    }

    private static Double nullableColumn(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    void recordSample(
            double t,
            double gridRawW,
            double gridEmaW,
            Double socPct,
            Double batteryPowerW,
            Double batteryVoltageV,
            Double batteryCurrentA,
            String injectionControl,
            Map<String, Double> inverterActualW) {
        lock.lock();
        try {
            long tSeconds = Math.round(t);
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT OR REPLACE INTO samples "
                            + "(t, grid_raw_w, grid_ema_w, soc_pct, battery_power_w, battery_voltage_v, "
                            + "battery_current_a, injection_control) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setLong(1, tSeconds);
                stmt.setDouble(2, gridRawW);
                stmt.setDouble(3, gridEmaW);
                setNullableDouble(stmt, 4, socPct);
                setNullableDouble(stmt, 5, batteryPowerW);
                setNullableDouble(stmt, 6, batteryVoltageV);
                setNullableDouble(stmt, 7, batteryCurrentA);
                stmt.setString(8, injectionControl);
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

    /**
     * Persists the current {@code HourlyEnergyHistory} buckets -- called on
     * its own cadence (config.stats.intervalS, see loop.ControlLoop.run),
     * independently of {@link #recordLatestSample} which now runs every
     * fast-loop tick: hourly energy barely changes at that resolution, so
     * there's no need to upsert it that often too.
     */
    public void upsertHourlyEnergy(List<Map<String, Object>> buckets) {
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

    /**
     * Persists the current {@code InverterEnergyHistory} buckets -- called on
     * the same cadence as {@link #upsertHourlyEnergy} (see loop.ControlLoop.run).
     */
    public void upsertInverterHourlyEnergy(List<Map<String, Object>> buckets) {
        lock.lock();
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO inverter_hourly_energy (hour, serial, wh) VALUES (?, ?, ?)")) {
            for (Map<String, Object> bucket : buckets) {
                long hour = Math.round((Double) bucket.get("hour"));
                for (Map.Entry<String, Object> entry : bucket.entrySet()) {
                    if ("hour".equals(entry.getKey())) {
                        continue;
                    }
                    stmt.setLong(1, hour);
                    stmt.setString(2, entry.getKey());
                    stmt.setDouble(3, (Double) entry.getValue());
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to persist inverter hourly energy", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Thins {@code samples} rows older than {@code highResCutoffEpochSeconds}
     * down to one row per {@code bucketSeconds}-wide bucket (keeping each
     * bucket's LATEST row -- the same "whatever's current when the timer
     * fires" snapshot semantics {@link #recordSample} always used, just
     * applied retroactively instead of gating the write itself), then drops
     * any {@code inverter_samples} rows that no longer have a matching
     * {@code samples} row. Called once a day (see loop.ControlLoop.run),
     * BEFORE {@link #pruneOlderThan} -- this is what keeps stats.db from
     * growing at the live read cadence for the full retention period: only
     * the most recent config.stats.highResRetentionDays stays at full
     * resolution, everything older is coarsened to config.stats.intervalS.
     * A no-op on rows that are already at or below that resolution (e.g.
     * pre-existing 5-minute data from before this feature, or a bucket that
     * only ever had one row to begin with).
     */
    public void downsampleOlderThan(double highResCutoffEpochSeconds, double bucketSeconds) {
        lock.lock();
        try {
            long cutoff = Math.round(highResCutoffEpochSeconds);
            long bucket = Math.max(1, Math.round(bucketSeconds));
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM samples WHERE t < ? AND t NOT IN "
                            + "(SELECT MAX(t) FROM samples WHERE t < ? GROUP BY (t / ?))")) {
                stmt.setLong(1, cutoff);
                stmt.setLong(2, cutoff);
                stmt.setLong(3, bucket);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM inverter_samples WHERE t < ? AND t NOT IN (SELECT t FROM samples WHERE t < ?)")) {
                stmt.setLong(1, cutoff);
                stmt.setLong(2, cutoff);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to downsample aged-out stats", e);
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
                    "DELETE FROM hourly_energy WHERE hour < ?",
                    "DELETE FROM inverter_hourly_energy WHERE hour < ?")) {
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
