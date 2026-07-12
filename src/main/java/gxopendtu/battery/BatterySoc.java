package gxopendtu.battery;

/**
 * Reads the aggregated battery SOC/power for the optional charge-priority
 * hysteresis (see gxopendtu.control.BatteryFullHysteresis).
 */
public interface BatterySoc {

    double readSocPct();

    /** Positive = charging, negative = discharging. Dashboard display only, not used for gating. */
    double readPowerW();

    /** Same sign convention as readPowerW(). Dashboard display only. Throws if not configured. */
    double readCurrentA();

    /** Dashboard display only. Throws (BatterySocUnavailableException) if not configured for this install. */
    double readVoltageV();
}
