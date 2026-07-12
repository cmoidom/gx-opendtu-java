package gxopendtu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gxopendtu.config.AppConfig.BatteryConfig;
import gxopendtu.config.AppConfig.CapacityProbeConfig;
import gxopendtu.config.AppConfig.ControlConfig;
import gxopendtu.config.AppConfig.GridConfig;
import gxopendtu.config.AppConfig.InverterConfig;
import gxopendtu.config.AppConfig.LoggingConfig;
import gxopendtu.config.AppConfig.ModbusGridConfig;
import gxopendtu.config.AppConfig.OpenDTUConfig;
import gxopendtu.config.AppConfig.StatsConfig;
import gxopendtu.config.AppConfig.WebConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loading and validation of the zero-export controller configuration file.
 *
 * Port of {@code src/config.py}. Same JSON schema as the Python "modbus" VM
 * deployment, minus the {@code grid.source} switch: this port only ever reads
 * the grid/battery over Modbus TCP, so {@code grid.modbus} is always required.
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigLoader() {}

    public static AppConfig loadConfig(Path path) {
        JsonNode raw;
        try {
            raw = MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read config file: " + path, e);
        }
        return parseConfig(raw);
    }

    public static AppConfig parseConfig(JsonNode raw) {
        JsonNode opendtuRaw = raw.path("opendtu");
        if (opendtuRaw.isMissingNode() || opendtuRaw.path("base_url").isMissingNode()) {
            throw new IllegalArgumentException("config.opendtu.base_url is required");
        }

        JsonNode invertersRaw = raw.path("inverters");
        List<InverterConfig> inverters = new ArrayList<>();
        if (invertersRaw.isArray()) {
            for (JsonNode inv : invertersRaw) {
                String name = textOrNull(inv, "name");
                inverters.add(new InverterConfig(
                        requireText(inv, "serial", "config.inverters[].serial is required"),
                        requireDouble(inv, "nominal_power_w", "config.inverters[].nominal_power_w is required"),
                        (name != null && !name.isEmpty()) ? name : null));
            }
        }
        if (inverters.isEmpty()) {
            throw new IllegalArgumentException("config.inverters must contain at least one inverter");
        }

        JsonNode gridRaw = raw.path("grid");
        JsonNode controlRaw = raw.path("control");
        JsonNode probeRaw = raw.path("capacity_probe");
        JsonNode batteryRaw = raw.path("battery");
        JsonNode webRaw = raw.path("web");
        JsonNode loggingRaw = raw.path("logging");
        JsonNode statsRaw = raw.path("stats");

        JsonNode modbusRaw = gridRaw.path("modbus");
        if (modbusRaw.path("host").isMissingNode()) {
            throw new IllegalArgumentException("config.grid.modbus.host is required");
        }
        ModbusGridConfig modbusCfg = new ModbusGridConfig(modbusRaw.path("host").asText(), modbusRaw.path("port").asInt(502));

        String username = textOrNull(opendtuRaw, "username");
        String password = textOrNull(opendtuRaw, "password");

        return new AppConfig(
                new OpenDTUConfig(
                        stripTrailingSlash(opendtuRaw.path("base_url").asText()),
                        (username != null && !username.isEmpty()) ? username : null,
                        (password != null && !password.isEmpty()) ? password : null),
                new GridConfig(
                        gridRaw.path("export_setpoint_w").asDouble(30.0),
                        gridRaw.path("read_interval_s").asDouble(2.0),
                        gridRaw.path("ema_alpha").asDouble(0.5),
                        modbusCfg),
                new ControlConfig(
                        controlRaw.path("kp").asDouble(0.4),
                        controlRaw.path("ki").asDouble(0.05),
                        controlRaw.path("decision_interval_s").asDouble(5.0),
                        controlRaw.path("step_absolute_w").asDouble(100.0),
                        controlRaw.path("step_relative_pct").asDouble(10.0),
                        controlRaw.path("min_change_w").asDouble(5.0),
                        controlRaw.path("min_inverter_pct").asDouble(5.0)),
                new CapacityProbeConfig(
                        probeRaw.path("step_w").asDouble(10.0), probeRaw.path("interval_s").asDouble(30.0)),
                new BatteryConfig(
                        batteryRaw.path("enabled").asBoolean(false),
                        batteryRaw.path("activate_at_pct").asDouble(100.0),
                        batteryRaw.path("deactivate_below_pct").asDouble(98.0),
                        batteryRaw.path("export_confirms_full_w").asDouble(50.0)),
                new WebConfig(webRaw.path("port").asInt(8080)),
                new LoggingConfig(loggingRaw.path("verbose_traces").asBoolean(true)),
                new StatsConfig(
                        statsRaw.path("interval_s").asDouble(300.0), statsRaw.path("retention_days").asInt(730)),
                inverters);
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asText();
    }

    private static String requireText(JsonNode node, String field, String errorMessage) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return child.asText();
    }

    private static double requireDouble(JsonNode node, String field, String errorMessage) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return child.asDouble();
    }
}
