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

    public record ControlConfig(
            double kp,
            double ki,
            double decisionIntervalS,
            double stepAbsoluteW,
            double stepRelativePct,
            double minChangeW,
            double minInverterPct) {}

    public record CapacityProbeConfig(double stepW, double intervalS) {}

    public record BatteryConfig(
            boolean enabled, double activateAtPct, double deactivateBelowPct, double exportConfirmsFullW) {}

    /** name is display-only (dashboard legend/table) -- never used to address the inverter. */
    public record InverterConfig(String serial, double nominalPowerW, String name) {}

    /** The config page + live dashboard are always on -- only the port is configurable. */
    public record WebConfig(int port) {}

    /**
     * verboseTraces gates only the per-cycle state line, never errors/warnings
     * or one-off actions (fail-safe, charge-priority release, restart request).
     */
    public record LoggingConfig(boolean verboseTraces) {}

    /**
     * Long-term (multi-year) persistence of dashboard curves to a local
     * SQLite file (stats.db, next to config.json) -- separate from the
     * in-memory LiveState/HourlyEnergyHistory ring buffers used for the live
     * view, which stay ephemeral. intervalS gates how often a sample is
     * written (deliberately coarser than the live view's read_interval_s --
     * long-term trend curves don't need per-tick resolution). retentionDays
     * bounds the database size regardless of uptime.
     */
    public record StatsConfig(double intervalS, int retentionDays) {}
}
