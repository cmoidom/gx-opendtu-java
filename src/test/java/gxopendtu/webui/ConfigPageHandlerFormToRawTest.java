package gxopendtu.webui;

import com.fasterxml.jackson.databind.JsonNode;
import gxopendtu.config.AppConfig;
import gxopendtu.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPageHandlerFormToRawTest {

    @Test
    void formToRawProducesConfigParseableByConfigLoader() {
        Map<String, List<String>> form = Map.ofEntries(
                Map.entry("opendtu.base_url", List.of("http://192.168.1.50")),
                Map.entry("opendtu.username", List.of("")),
                Map.entry("opendtu.password", List.of("")),
                Map.entry("grid.export_setpoint_w", List.of("30")),
                Map.entry("grid.read_interval_s", List.of("2")),
                Map.entry("grid.ema_alpha", List.of("0.5")),
                Map.entry("grid.modbus.host", List.of("192.168.1.10")),
                Map.entry("grid.modbus.port", List.of("502")),
                Map.entry("grid.modbus.unit_id", List.of("100")),
                Map.entry("control.kp", List.of("0.4")),
                Map.entry("control.min_inverter_pct", List.of("10")),
                Map.entry("capacity_probe.step_w", List.of("10")),
                Map.entry("battery.activate_at_pct", List.of("100")),
                Map.entry("web.port", List.of("8080")),
                Map.entry("inverter_serial", List.of("114181801234", "114181805678")),
                Map.entry("inverter_nominal_power_w", List.of("600", "380")),
                Map.entry("inverter_name", List.of("", "Toit Sud")));

        JsonNode raw = ConfigPageHandler.formToRaw(form);
        AppConfig config = ConfigLoader.parseConfig(raw);

        assertThat(config.opendtu().baseUrl()).isEqualTo("http://192.168.1.50");
        assertThat(config.grid().modbus().host()).isEqualTo("192.168.1.10");
        assertThat(config.inverters()).hasSize(2);
        assertThat(config.inverters().get(0).name()).isNull();
        assertThat(config.inverters().get(1).name()).isEqualTo("Toit Sud");
        assertThat(config.battery().enabled()).isFalse(); // checkbox absent from form
    }

    @Test
    void blankInverterSerialRowsAreSkipped() {
        Map<String, List<String>> form = Map.of(
                "opendtu.base_url", List.of("http://x"),
                "grid.modbus.host", List.of("10.0.0.1"),
                "inverter_serial", List.of("", "  ", "a"),
                "inverter_nominal_power_w", List.of("100", "200", "300"),
                "inverter_name", List.of("", "", ""));

        JsonNode raw = ConfigPageHandler.formToRaw(form);
        assertThat(raw.path("inverters").size()).isEqualTo(1);
        assertThat(raw.path("inverters").get(0).path("serial").asText()).isEqualTo("a");
    }

    @Test
    void checkboxesPresentInFormAreEnabled() {
        Map<String, List<String>> form = Map.of(
                "opendtu.base_url", List.of("http://x"),
                "grid.modbus.host", List.of("10.0.0.1"),
                "battery.enabled", List.of("on"),
                "logging.verbose_traces", List.of("on"),
                "inverter_serial", List.of("a"),
                "inverter_nominal_power_w", List.of("100"),
                "inverter_name", List.of(""));

        AppConfig config = ConfigLoader.parseConfig(ConfigPageHandler.formToRaw(form));
        assertThat(config.battery().enabled()).isTrue();
        assertThat(config.logging().verboseTraces()).isTrue();
    }

    @Test
    void batteryVoltageUnitIdBlankIsOmittedNotZero() {
        Map<String, List<String>> form = Map.of(
                "opendtu.base_url", List.of("http://x"),
                "grid.modbus.host", List.of("10.0.0.1"),
                "battery.voltage_unit_id", List.of(""),
                "inverter_serial", List.of("a"),
                "inverter_nominal_power_w", List.of("100"),
                "inverter_name", List.of(""));

        AppConfig config = ConfigLoader.parseConfig(ConfigPageHandler.formToRaw(form));
        assertThat(config.battery().voltageUnitId()).isNull();
    }

    @Test
    void batteryVoltageUnitIdRoundTripsWhenProvided() {
        Map<String, List<String>> form = Map.of(
                "opendtu.base_url", List.of("http://x"),
                "grid.modbus.host", List.of("10.0.0.1"),
                "battery.voltage_unit_id", List.of("225"),
                "inverter_serial", List.of("a"),
                "inverter_nominal_power_w", List.of("100"),
                "inverter_name", List.of(""));

        AppConfig config = ConfigLoader.parseConfig(ConfigPageHandler.formToRaw(form));
        assertThat(config.battery().voltageUnitId()).isEqualTo(225);
    }
}
