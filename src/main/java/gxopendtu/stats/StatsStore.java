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
 * {@link #recordLatestSample} does NOT write to SQLite itself -- it buffers
 * the sample in memory ({@code pendingSamples}), and {@link #flushBufferedSamples}
 * writes everything buffered so far in one transaction. loop.ControlLoop.run
 * calls the latter once a minute (a fixed constant, deliberately separate
 * from config.stats.intervalS -- that value already means "downsample bucket
 * width," reusing it here would silently change the crash-loss window
 * whenever someone tunes downsampling), turning what would otherwise be one
 * fsync every ~2s into one every ~60s for however many samples accumulated.
 * The tradeoff: an unclean shutdown (crash, power loss -- NOT a normal
 * systemd stop/restart, which flushes explicitly, see below) can now lose up
 * to ~1 minute of high-resolution samples instead of ~2s.
 *
 * Written from two places, both threads: continuously from the control loop
 * thread (see loop.ControlLoop.run) and immediately from an HTTP handler
 * thread when "Enregistrer et appliquer" is pressed (see
 * webui.ConfigPageHandler, which calls {@link #flushBufferedSamples} itself
 * so a planned restart never loses anything still buffered). All operations
 * are serialized behind a single lock -- SQLite itself serializes writes at
 * the file level, but this keeps a multi-statement operation (e.g. the
 * per-inverter batch insert) from interleaving with a concurrent call from
 * the other thread.
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
    private final PreparedStatement insertSampleStmt;
    private final PreparedStatement insertInverterSampleStmt;
    private final List<BufferedSample> pendingSamples = new ArrayList<>();

    /** One recordLatestSample call's worth of data, held in memory until the next {@link #flushBufferedSamples}. */
    private record BufferedSample(
            double t,
            double gridRawW,
            double gridEmaW,
            Double socPct,
            Double batteryPowerW,
            Double batteryVoltageV,
            Double batteryCurrentA,
            String injectionControl,
            Map<String, Double> inverterActualW) {}

    public StatsStore(Path dbPath) {
        this.dbPath = dbPath;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                // NORMAL (rather than the default FULL) skips an fsync on
                // every commit that WAL mode's own checkpointing already
                // makes safe against corruption -- the only added risk is
                // losing the last few not-yet-checkpointed transactions on
                // an unclean shutdown, which is already the accepted
                // trade-off documented above for the sample buffer itself.
                statement.execute("PRAGMA synchronous=NORMAL");
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
            // Prepared once and reused for the lifetime of this StatsStore
            // (both by recordSample's direct callers and by
            // flushBufferedSamples) instead of recompiling the same SQL on
            // every single sample.
            insertSampleStmt = connection.prepareStatement(
                    "INSERT OR REPLACE INTO samples "
                            + "(t, grid_raw_w, grid_ema_w, soc_pct, battery_power_w, battery_voltage_v, "
                            + "battery_current_a, injection_control) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            insertInverterSampleStmt = connection.prepareStatement(
                    "INSERT OR REPLACE INTO inverter_samples (t, serial, actual_w) VALUES (?, ?, ?)");
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
     * InverterEnergyHistory buckets, and persists all three -- including an
     * explicit {@link #flushBufferedSamples} so a planned restart/shutdown
     * never loses whatever recordLatestSample has buffered but not yet
     * written to disk.
     */
    public void persistSnapshot(
            LiveState liveState, HourlyEnergyHistory energyHistory, InverterEnergyHistory inverterEnergyHistory) {
        recordLatestSample(liveState);
        flushBufferedSamples();
        upsertHourlyEnergy(energyHistory.snapshot());
        upsertInverterHourlyEnergy(inverterEnergyHistory.snapshot());
    }

    /**
     * Buffers the latest LiveState sample in memory -- called every
     * fast-loop tick (see loop.ControlLoop.run) so stats.db's resolution
     * matches the live view's for the most recent config.stats.highResRetentionDays.
     * Does NOT write to SQLite itself; see {@link #flushBufferedSamples}.
     * Older rows are later thinned down to config.stats.intervalS by
     * {@link #downsampleOlderThan}, which is what actually keeps the
     * database from growing at this cadence forever.
     */
    public void recordLatestSample(LiveState liveState) {
        bufferSampleFromLiveState(liveState.snapshotSince(0).latest());
    }

    private void bufferSampleFromLiveState(Map<String, Object> sample) {
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
        lock.lock();
        try {
            pendingSamples.add(new BufferedSample(
                    t, gridRawW, gridEmaW, socPct, batteryPowerW, batteryVoltageV, batteryCurrentA,
                    injectionControl, inverterActualW));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes every sample buffered by {@link #recordLatestSample} since the
     * last flush, in a single transaction -- one fsync for however many ~2s
     * ticks have accumulated, instead of one per tick. Called once a minute
     * from loop.ControlLoop.run, and once more explicitly from
     * {@link #persistSnapshot} before a planned restart/shutdown. A no-op if
     * nothing has been buffered since the last call.
     */
    public void flushBufferedSamples() {
        lock.lock();
        try {
            if (pendingSamples.isEmpty()) {
                return;
            }
            writeSamplesBatch(pendingSamples);
            pendingSamples.clear();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to flush buffered stats samples", e);
        } finally {
            lock.unlock();
        }
    }

    /** Caller must hold {@code lock}. Writes every sample in {@code samples} in one transaction. */
    private void writeSamplesBatch(List<BufferedSample> samples) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (BufferedSample s : samples) {
                long tSeconds = Math.round(s.t());
                insertSampleStmt.setLong(1, tSeconds);
                insertSampleStmt.setDouble(2, s.gridRawW());
                insertSampleStmt.setDouble(3, s.gridEmaW());
                setNullableDouble(insertSampleStmt, 4, s.socPct());
                setNullableDouble(insertSampleStmt, 5, s.batteryPowerW());
                setNullableDouble(insertSampleStmt, 6, s.batteryVoltageV());
                setNullableDouble(insertSampleStmt, 7, s.batteryCurrentA());
                insertSampleStmt.setString(8, s.injectionControl());
                insertSampleStmt.addBatch();

                Map<String, Double> inverterActualW = s.inverterActualW();
                if (inverterActualW != null) {
                    for (Map.Entry<String, Double> entry : inverterActualW.entrySet()) {
                        insertInverterSampleStmt.setLong(1, tSeconds);
                        insertInverterSampleStmt.setString(2, entry.getKey());
                        insertInverterSampleStmt.setDouble(3, entry.getValue());
                        insertInverterSampleStmt.addBatch();
                    }
                }
            }
            insertSampleStmt.executeBatch();
            insertInverterSampleStmt.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
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
     * "Energie reseau (par heure)" bar chart doesn't reset to empty on every
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
     * Hourly energy buckets strictly within {@code [sinceEpochSeconds,
     * untilEpochSeconds)} (chronological order) -- used by
     * {@code webui.HourlyEnergyJsonHandler} so the dashboard's per-chart day
     * picker can look at a single arbitrary past day instead of only ever
     * the live {@link #loadHourlyEnergy} feed. {@code until} is exclusive so
     * passing a day's start/next-day's-start as since/until cleanly excludes
     * the following day's first bucket.
     */
    public List<Map<String, Object>> loadHourlyEnergyBetween(double sinceEpochSeconds, double untilEpochSeconds) {
        lock.lock();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT hour, from_kwh, to_kwh FROM hourly_energy WHERE hour >= ? AND hour < ? ORDER BY hour")) {
            stmt.setLong(1, Math.round(sinceEpochSeconds));
            stmt.setLong(2, Math.round(untilEpochSeconds));
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
            LOG.log(Level.SEVERE, "failed to load hourly energy range for the dashboard day picker", e);
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

    /**
     * Hourly per-inverter energy yield (Wh) strictly within
     * {@code [sinceEpochSeconds, untilEpochSeconds)} -- the per-inverter
     * counterpart of {@link #loadHourlyEnergyBetween}, same day-picker use.
     */
    public List<Map<String, Object>> loadInverterHourlyEnergyBetween(double sinceEpochSeconds, double untilEpochSeconds) {
        lock.lock();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT hour, serial, wh FROM inverter_hourly_energy WHERE hour >= ? AND hour < ? ORDER BY hour")) {
            stmt.setLong(1, Math.round(sinceEpochSeconds));
            stmt.setLong(2, Math.round(untilEpochSeconds));
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
            LOG.log(Level.SEVERE, "failed to load inverter hourly energy range for the dashboard day picker", e);
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

    /**
     * Writes a single sample immediately (unlike {@link #recordLatestSample},
     * which only buffers) -- used directly by tests, and as the single-item
     * case of the same {@link #writeSamplesBatch} transaction logic
     * {@link #flushBufferedSamples} uses for a whole buffer at once.
     */
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
            writeSamplesBatch(List.of(new BufferedSample(
                    t, gridRawW, gridEmaW, socPct, batteryPowerW, batteryVoltageV, batteryCurrentA,
                    injectionControl, inverterActualW)));
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
            insertSampleStmt.close();
            insertInverterSampleStmt.close();
            connection.close();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "failed to close stats database", e);
        } finally {
            lock.unlock();
        }
    }
}
