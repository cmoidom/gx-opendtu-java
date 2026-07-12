package gxopendtu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final String FULL_CONFIG = """
            {
              "opendtu": { "base_url": "http://192.168.1.50/" },
              "grid": {
                "export_setpoint_w": 30,
                "read_interval_s": 2,
                "ema_alpha": 0.5,
                "modbus": { "host": "192.168.1.10", "port": 502, "unit_id": 100 }
              },
              "control": { "kp": 0.4, "ki": 0.05, "decision_interval_s": 5,
                           "step_absolute_w": 100, "step_relative_pct": 10, "min_change_w": 5 },
              "capacity_probe": { "step_w": 10, "interval_s": 30 },
              "battery": { "enabled": false, "activate_at_pct": 100, "deactivate_below_pct": 98 },
              "inverters": [
                { "serial": "114181801234", "nominal_power_w": 600 },
                { "serial": "114181805678", "nominal_power_w": 380, "name": "Toit Sud" }
              ]
            }
            """;

    @Test
    void parsesFullConfigWithDefaults() {
        AppConfig config = ConfigLoader.parseConfig(json(FULL_CONFIG));

        assertThat(config.opendtu().baseUrl()).isEqualTo("http://192.168.1.50"); // trailing slash stripped
        assertThat(config.opendtu().username()).isNull();
        assertThat(config.grid().modbus().host()).isEqualTo("192.168.1.10");
        assertThat(config.grid().modbus().unitId()).isEqualTo(100);
        assertThat(config.grid().modbus().energyUnitId()).isNull(); // resolved to unitId by ModbusGridMeter, not here
        assertThat(config.control().minInverterPct()).isEqualTo(5.0); // default, absent from FULL_CONFIG
        assertThat(config.web().port()).isEqualTo(8080);
        assertThat(config.logging().verboseTraces()).isTrue();
        assertThat(config.stats().intervalS()).isEqualTo(300.0); // default
        assertThat(config.stats().retentionDays()).isEqualTo(730); // default, ~2 years
        assertThat(config.inverters()).hasSize(2);
        assertThat(config.inverters().get(1).name()).isEqualTo("Toit Sud");
        assertThat(config.totalNominalPowerW()).isEqualTo(980.0);
        assertThat(config.battery().voltageUnitId()).isNull(); // absent from FULL_CONFIG
    }

    @Test
    void batteryVoltageUnitIdIsPreservedWhenExplicitlySet() {
        String raw = """
                {
                  "opendtu": { "base_url": "http://x" },
                  "grid": { "modbus": { "host": "10.0.0.1" } },
                  "battery": { "enabled": true, "voltage_unit_id": 225 },
                  "inverters": [{ "serial": "a", "nominal_power_w": 100 }]
                }
                """;
        AppConfig config = ConfigLoader.parseConfig(json(raw));
        assertThat(config.battery().voltageUnitId()).isEqualTo(225);
    }

    @Test
    void statsIntervalAndRetentionCanBeOverridden() {
        String raw = """
                {
                  "opendtu": { "base_url": "http://x" },
                  "grid": { "modbus": { "host": "10.0.0.1" } },
                  "stats": { "interval_s": 60, "retention_days": 30 },
                  "inverters": [{ "serial": "a", "nominal_power_w": 100 }]
                }
                """;
        AppConfig config = ConfigLoader.parseConfig(json(raw));
        assertThat(config.stats().intervalS()).isEqualTo(60.0);
        assertThat(config.stats().retentionDays()).isEqualTo(30);
    }

    @Test
    void missingOpendtuBaseUrlIsRejected() {
        assertThatThrownBy(() -> ConfigLoader.parseConfig(json("{\"inverters\": [{\"serial\": \"a\", \"nominal_power_w\": 100}]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.opendtu.base_url is required");
    }

    @Test
    void emptyInvertersListIsRejected() {
        assertThatThrownBy(() -> ConfigLoader.parseConfig(
                        json("{\"opendtu\": {\"base_url\": \"http://x\"}, \"inverters\": []}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.inverters must contain at least one inverter");
    }

    @Test
    void missingModbusHostIsRejected() {
        String raw = """
                {
                  "opendtu": { "base_url": "http://x" },
                  "grid": { "modbus": {} },
                  "inverters": [{ "serial": "a", "nominal_power_w": 100 }]
                }
                """;
        assertThatThrownBy(() -> ConfigLoader.parseConfig(json(raw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.grid.modbus.host is required");
    }

    @Test
    void energyUnitIdIsPreservedWhenExplicitlySet() {
        String raw = """
                {
                  "opendtu": { "base_url": "http://x" },
                  "grid": { "modbus": { "host": "10.0.0.1", "energy_unit_id": 30 } },
                  "inverters": [{ "serial": "a", "nominal_power_w": 100 }]
                }
                """;
        AppConfig config = ConfigLoader.parseConfig(json(raw));
        assertThat(config.grid().modbus().energyUnitId()).isEqualTo(30);
    }
}
