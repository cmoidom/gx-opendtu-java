package gxopendtu.battery;

/**
 * Victron-side state that explains what the battery/Multi is allowed to do,
 * read for diagnosis only (2026-10-20, after the battery capped its
 * discharge around 800W for ~30 min on 2026-10-19 18:27-18:55 while the
 * house drew ~1.8kW). Never used for control. Each field is null when its
 * own read failed.
 *
 * @param dischargeLimitA BMS max discharge current (DCL), A
 * @param chargeLimitA BMS max charge current (CCL), A
 * @param systemState com.victronenergy.system /SystemState/State (3=Bulk, 9=Inverting, 256=Discharging...)
 * @param batteryLifeState ESS BatteryLife state (2-4=self-consumption, 5=discharge disabled, 9=keep charged...)
 * @param essSetpointW power setpoint the ESS gives the Multi on its AC input, W (+ = taken from AC)
 */
public record VictronStatus(
        Double dischargeLimitA,
        Double chargeLimitA,
        Integer systemState,
        Integer batteryLifeState,
        Double essSetpointW) {}
