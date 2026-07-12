package gxopendtu.battery;

/**
 * Reads the aggregated battery SOC/power for the optional charge-priority
 * hysteresis (see gxopendtu.control.BatteryFullHysteresis).
 */
public interface BatterySoc {

    double readSocPct();

    /** Positive = charging, negative = discharging. Dashboard display only, not used for gating. */
    double readPowerW();
}
