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
        List<InverterConfig> inverters) {

    public double totalNominalPowerW() {
        return inverters.stream().mapToDouble(InverterConfig::nominalPowerW).sum();
    }

    public record OpenDTUConfig(String baseUrl, String username, String password) {}

    /**
     * unitId always 100 (com.victronenergy.system aggregate) unless overridden.
     * energyUnitId is nullable: resolved to unitId by ModbusGridMeter if unset,
     * exactly like src/grid_meter_modbus.py's ModbusGridMeter.__init__.
     */
    public record ModbusGridConfig(String host, int port, int unitId, Integer energyUnitId) {}

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

    public record WebConfig(boolean enabled, int port) {}

    /**
     * verboseTraces gates only the per-cycle state line, never errors/warnings
     * or one-off actions (fail-safe, charge-priority release, restart request).
     */
    public record LoggingConfig(boolean verboseTraces) {}
}
