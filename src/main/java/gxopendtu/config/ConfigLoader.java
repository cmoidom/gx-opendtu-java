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
import gxopendtu.config.AppConfig.SunSpecProxyConfig;
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
        public static final double CONTROL_MIN_BATTERY_DISCHARGE_W = 150.0;

        // 50W (2026-07-13, was 10W): the linear nudge only needs to cover the
        // backoff cooldown and non-saturated ambient recovery now -- the
        // saturated/still-keeping-up case jumps straight to nominal instead
        // (CapacityEstimator.probeTick's optimistic-recovery path), which is
        // just as safe with a bigger step since the persistence check
        // corrects either size of guess in the same few cycles.
        public static final double CAPACITY_PROBE_STEP_W = 50.0;
        public static final double CAPACITY_PROBE_INTERVAL_S = 30.0;

        public static final boolean BATTERY_ENABLED = false;
        public static final double BATTERY_ACTIVATE_AT_PCT = 100.0;
        public static final double BATTERY_DEACTIVATE_BELOW_PCT = 98.0;
        public static final double BATTERY_EXPORT_CONFIRMS_FULL_W = 50.0;
        public static final double BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S = 60.0;

        public static final int WEB_PORT = 8080;
        public static final int CHART_HEIGHT_PX = 200;
        public static final int CHART_HEIGHT_PX_MIN = 200;
        public static final int CHART_HEIGHT_PX_MAX = 500;

        public static final boolean LOGGING_VERBOSE_TRACES = true;

        public static final double STATS_INTERVAL_S = 300.0;
        public static final int STATS_RETENTION_DAYS = 730;
        public static final int STATS_HIGH_RES_RETENTION_DAYS = 30;

        public static final boolean SUNSPEC_PROXY_ENABLED = false;
        // 502 (2026-07-19, was 1502): confirmed on a live Venus OS -- its Modbus
        // scan only checks the standard port, a custom port (1502) went
        // undetected. Binding <1024 needs root or CAP_NET_BIND_SERVICE on Linux
        // -- see deploy/systemd/gx-opendtu-zero-export.service.
        public static final int SUNSPEC_PROXY_TCP_PORT = 502;
        public static final double SUNSPEC_PROXY_POLL_INTERVAL_S = 2.0;
        // "Fronius" (2026-07-19): the reference bridge this spike is modeled on
        // (github.com/Geoffn-Hub/esphome-sunspec-proxy) found this manufacturer
        // string gives the best Victron compatibility -- borrowed as-is, not
        // independently verified against this project's own Venus OS yet.
        public static final String SUNSPEC_PROXY_MANUFACTURER = "Fronius";
        public static final String SUNSPEC_PROXY_MODEL = "gx-opendtu-java";
        public static final String SUNSPEC_PROXY_SERIAL_NUMBER = "GXOPENDTU-SPIKE-001";
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
                        (name != null && !name.isEmpty()) ? name : null,
                        inv.path("controllable").asBoolean(true)));
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
        JsonNode sunspecProxyRaw = raw.path("sunspec_proxy");

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

        int chartHeightPx = webRaw.path("chart_height_px").asInt(Defaults.CHART_HEIGHT_PX);
        if (chartHeightPx < Defaults.CHART_HEIGHT_PX_MIN || chartHeightPx > Defaults.CHART_HEIGHT_PX_MAX) {
            throw new IllegalArgumentException(String.format(
                    "config.web.chart_height_px must be between %d and %d",
                    Defaults.CHART_HEIGHT_PX_MIN, Defaults.CHART_HEIGHT_PX_MAX));
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
                        controlRaw.path("min_inverter_pct").asDouble(Defaults.CONTROL_MIN_INVERTER_PCT),
                        controlRaw.path("min_battery_discharge_w").asDouble(Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W)),
                new CapacityProbeConfig(
                        probeRaw.path("step_w").asDouble(Defaults.CAPACITY_PROBE_STEP_W),
                        probeRaw.path("interval_s").asDouble(Defaults.CAPACITY_PROBE_INTERVAL_S)),
                new BatteryConfig(
                        batteryRaw.path("enabled").asBoolean(Defaults.BATTERY_ENABLED),
                        batteryRaw.path("activate_at_pct").asDouble(Defaults.BATTERY_ACTIVATE_AT_PCT),
                        batteryRaw.path("deactivate_below_pct").asDouble(Defaults.BATTERY_DEACTIVATE_BELOW_PCT),
                        batteryRaw.path("export_confirms_full_w").asDouble(Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W),
                        batteryRaw.path("export_confirms_full_duration_s")
                                .asDouble(Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S)),
                new WebConfig(webRaw.path("port").asInt(Defaults.WEB_PORT), chartHeightPx),
                new LoggingConfig(loggingRaw.path("verbose_traces").asBoolean(Defaults.LOGGING_VERBOSE_TRACES)),
                new StatsConfig(
                        statsRaw.path("interval_s").asDouble(Defaults.STATS_INTERVAL_S),
                        statsRetentionDays,
                        statsHighResRetentionDays),
                new SunSpecProxyConfig(
                        sunspecProxyRaw.path("enabled").asBoolean(Defaults.SUNSPEC_PROXY_ENABLED),
                        sunspecProxyRaw.path("tcp_port").asInt(Defaults.SUNSPEC_PROXY_TCP_PORT),
                        sunspecProxyRaw.path("poll_interval_s").asDouble(Defaults.SUNSPEC_PROXY_POLL_INTERVAL_S),
                        sunspecProxyRaw.path("manufacturer").asText(Defaults.SUNSPEC_PROXY_MANUFACTURER),
                        sunspecProxyRaw.path("model").asText(Defaults.SUNSPEC_PROXY_MODEL),
                        sunspecProxyRaw.path("serial_number").asText(Defaults.SUNSPEC_PROXY_SERIAL_NUMBER)),
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
