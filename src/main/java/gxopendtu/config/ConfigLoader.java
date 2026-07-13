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

    /**
     * Single source of truth for every config.json field's default value --
     * referenced both here (parsing/validation) and by
     * {@code webui.ConfigPageHandler} (the form's fallback values and the
     * config page's displayed defaults), so the two can never silently drift
     * apart the way they previously could (each kept its own hand-typed copy
     * of the same default).
     */
    public static final class Defaults {
        private Defaults() {}

        public static final double GRID_EXPORT_SETPOINT_W = 30.0;
        public static final double GRID_READ_INTERVAL_S = 2.0;
        public static final double GRID_EMA_ALPHA = 0.5;
        public static final int GRID_MODBUS_PORT = 502;

        public static final double CONTROL_KP = 0.4;
        public static final double CONTROL_KI = 0.05;
        public static final double CONTROL_DECISION_INTERVAL_S = 5.0;
        public static final double CONTROL_STEP_ABSOLUTE_W = 100.0;
        public static final double CONTROL_STEP_RELATIVE_PCT = 10.0;
        public static final double CONTROL_MIN_CHANGE_W = 5.0;
        public static final double CONTROL_MIN_INVERTER_PCT = 5.0;

        public static final double CAPACITY_PROBE_STEP_W = 10.0;
        public static final double CAPACITY_PROBE_INTERVAL_S = 30.0;

        public static final boolean BATTERY_ENABLED = false;
        public static final double BATTERY_ACTIVATE_AT_PCT = 100.0;
        public static final double BATTERY_DEACTIVATE_BELOW_PCT = 98.0;
        public static final double BATTERY_EXPORT_CONFIRMS_FULL_W = 50.0;

        public static final int WEB_PORT = 8080;

        public static final boolean LOGGING_VERBOSE_TRACES = true;

        public static final double STATS_INTERVAL_S = 300.0;
        public static final int STATS_RETENTION_DAYS = 730;
        public static final int STATS_HIGH_RES_RETENTION_DAYS = 30;
    }

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
        ModbusGridConfig modbusCfg = new ModbusGridConfig(
                modbusRaw.path("host").asText(), modbusRaw.path("port").asInt(Defaults.GRID_MODBUS_PORT));

        int statsRetentionDays = statsRaw.path("retention_days").asInt(Defaults.STATS_RETENTION_DAYS);
        int statsHighResRetentionDays =
                statsRaw.path("high_res_retention_days").asInt(Defaults.STATS_HIGH_RES_RETENTION_DAYS);
        if (statsHighResRetentionDays > statsRetentionDays) {
            throw new IllegalArgumentException(
                    "config.stats.high_res_retention_days must not exceed config.stats.retention_days");
        }

        String username = textOrNull(opendtuRaw, "username");
        String password = textOrNull(opendtuRaw, "password");

        return new AppConfig(
                new OpenDTUConfig(
                        stripTrailingSlash(opendtuRaw.path("base_url").asText()),
                        (username != null && !username.isEmpty()) ? username : null,
                        (password != null && !password.isEmpty()) ? password : null),
                new GridConfig(
                        gridRaw.path("export_setpoint_w").asDouble(Defaults.GRID_EXPORT_SETPOINT_W),
                        gridRaw.path("read_interval_s").asDouble(Defaults.GRID_READ_INTERVAL_S),
                        gridRaw.path("ema_alpha").asDouble(Defaults.GRID_EMA_ALPHA),
                        modbusCfg),
                new ControlConfig(
                        controlRaw.path("kp").asDouble(Defaults.CONTROL_KP),
                        controlRaw.path("ki").asDouble(Defaults.CONTROL_KI),
                        controlRaw.path("decision_interval_s").asDouble(Defaults.CONTROL_DECISION_INTERVAL_S),
                        controlRaw.path("step_absolute_w").asDouble(Defaults.CONTROL_STEP_ABSOLUTE_W),
                        controlRaw.path("step_relative_pct").asDouble(Defaults.CONTROL_STEP_RELATIVE_PCT),
                        controlRaw.path("min_change_w").asDouble(Defaults.CONTROL_MIN_CHANGE_W),
                        controlRaw.path("min_inverter_pct").asDouble(Defaults.CONTROL_MIN_INVERTER_PCT)),
                new CapacityProbeConfig(
                        probeRaw.path("step_w").asDouble(Defaults.CAPACITY_PROBE_STEP_W),
                        probeRaw.path("interval_s").asDouble(Defaults.CAPACITY_PROBE_INTERVAL_S)),
                new BatteryConfig(
                        batteryRaw.path("enabled").asBoolean(Defaults.BATTERY_ENABLED),
                        batteryRaw.path("activate_at_pct").asDouble(Defaults.BATTERY_ACTIVATE_AT_PCT),
                        batteryRaw.path("deactivate_below_pct").asDouble(Defaults.BATTERY_DEACTIVATE_BELOW_PCT),
                        batteryRaw.path("export_confirms_full_w").asDouble(Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W)),
                new WebConfig(webRaw.path("port").asInt(Defaults.WEB_PORT)),
                new LoggingConfig(loggingRaw.path("verbose_traces").asBoolean(Defaults.LOGGING_VERBOSE_TRACES)),
                new StatsConfig(
                        statsRaw.path("interval_s").asDouble(Defaults.STATS_INTERVAL_S),
                        statsRetentionDays,
                        statsHighResRetentionDays),
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
