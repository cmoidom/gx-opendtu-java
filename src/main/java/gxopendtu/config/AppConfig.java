package gxopendtu.config;

import java.util.List;

/**
 * Parsed and validated zero-export controller configuration.
 *
 * Port of {@code src/config.py}. Unlike the Python original, there is no
 * "dbus" grid source: this port only ever runs off-device (Linux VM), so
 * {@link GridConfig} always carries a {@link ModbusGridConfig}.
 */
public record AppConfig(
        OpenDTUConfig opendtu,
        GridConfig grid,
        ControlConfig control,
        CapacityProbeConfig capacityProbe,
        BatteryConfig battery,
        WebConfig web,
        LoggingConfig logging,
        StatsConfig stats,
        List<InverterConfig> inverters) {

    public double totalNominalPowerW() {
        return inverters.stream().mapToDouble(InverterConfig::nominalPowerW).sum();
    }

    public record OpenDTUConfig(String baseUrl, String username, String password) {}

    public record ModbusGridConfig(String host, int port) {}

    public record GridConfig(double exportSetpointW, double readIntervalS, double emaAlpha, ModbusGridConfig modbus) {}

    /**
     * minBatteryDischargeW: below this magnitude, battery discharge is
     * treated as harmless float/self-consumption noise rather than a real
     * shortfall solar should cover -- see SoftTargetController.computeTarget.
     */
    public record ControlConfig(
            double kp,
            double ki,
            double decisionIntervalS,
            double stepAbsoluteW,
            double stepRelativePct,
            double minChangeW,
            double minInverterPct,
            double minBatteryDischargeW) {}

    public record CapacityProbeConfig(double stepW, double intervalS) {}

    public record BatteryConfig(
            boolean enabled,
            double activateAtPct,
            double deactivateBelowPct,
            double exportConfirmsFullW,
            double exportConfirmsFullDurationS) {}

    /**
     * name is display-only (dashboard legend/table) -- never used to address
     * the inverter. controllable (default true): when false, this inverter
     * is still read (power/yield, for dashboard display and the PI's grid-
     * balance accounting) but never commanded -- excluded from water-filling,
     * CapacityEstimator, and every code path that calls
     * setAbsoluteLimitW/setRelativeLimitPct (decisionCycle, releaseForCharging,
     * applyFailsafe, sendManualOverride included).
     */
    public record InverterConfig(String serial, double nominalPowerW, String name, boolean controllable) {}

    /**
     * The config page + live dashboard are always on -- port and dashboard
     * chart height are the only configurable bits. chartHeightPx applies to
     * every chart on /dashboard uniformly, clamped to
     * [ConfigLoader.Defaults.CHART_HEIGHT_PX_MIN, ..._MAX] at load time.
     */
    public record WebConfig(int port, int chartHeightPx) {}

    /**
     * verboseTraces gates only the per-cycle state line, never errors/warnings
     * or one-off actions (fail-safe, charge-priority release, restart request).
     */
    public record LoggingConfig(boolean verboseTraces) {}

    /**
     * Long-term persistence of dashboard curves to a local SQLite file
     * (stats.db, next to config.json) -- separate from the in-memory
     * LiveState/HourlyEnergyHistory ring buffers used for the live view,
     * which stay ephemeral. Two resolutions, to balance disk usage against
     * being able to zoom into a specific past moment: every sample is
     * written at the live cadence (grid.read_interval_s) for the most
     * recent highResRetentionDays, then thinned down to one row per
     * intervalS once older than that (StatsStore#downsampleOlderThan) --
     * intervalS is therefore the coarse bucket size for aged-out data, not
     * "how often a sample is written" (that's continuous now). retentionDays
     * bounds the database size regardless of uptime, at that coarse
     * resolution, and must be &gt;= highResRetentionDays.
     */
    public record StatsConfig(double intervalS, int retentionDays, int highResRetentionDays) {}
}
