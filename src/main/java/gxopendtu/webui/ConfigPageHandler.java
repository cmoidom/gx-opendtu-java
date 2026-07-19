package gxopendtu.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.BuildInfo;
import gxopendtu.config.ConfigLoader;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.stats.StatsStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Built-in config editor: GET "/config" renders the form, POST
 * "/config/save" writes config.json without restarting, POST
 * "/config/apply" writes then restarts the whole process (the supervisor --
 * systemd -- relaunches it with the new config on the next load). Lives at
 * "/config" rather than "/" -- the dashboard (webui.DashboardHandler) takes
 * the root path instead, since that's the page checked far more often.
 *
 * Deliberately does NOT touch the running control loop's in-memory state --
 * "Enregistrer" just writes the file and shows a message. Port of the config
 * page half of src/webui.py (ConfigHandler.do_GET/do_POST + _render_page +
 * _form_to_raw + _write_raw), minus the D-Bus "source" toggle: this port
 * only ever reads over Modbus TCP, so the modbus fields are always shown.
 *
 * No authentication (matches the OpenDTU API's own default) -- anyone on
 * the LAN that can reach this port can change the controller's configuration.
 */
final class ConfigPageHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configPath;
    private final LiveState liveState;
    private final HourlyEnergyHistory energyHistory;
    private final InverterEnergyHistory inverterEnergyHistory;
    private final StatsStore statsStore;

    ConfigPageHandler(
            Path configPath,
            LiveState liveState,
            HourlyEnergyHistory energyHistory,
            InverterEnergyHistory inverterEnergyHistory,
            StatsStore statsStore) {
        this.configPath = configPath;
        this.liveState = liveState;
        this.energyHistory = energyHistory;
        this.inverterEnergyHistory = inverterEnergyHistory;
        this.statsStore = statsStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if ("GET".equals(method)) {
                handleGet(exchange, path);
            } else if ("POST".equals(method)) {
                handlePost(exchange, path);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if (!path.equals("/config")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        sendHtml(exchange, 200, renderPage(loadRaw(configPath), "", "", statsInfo()));
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (!path.equals("/config/save") && !path.equals("/config/apply")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String body = readBody(exchange);
        Map<String, List<String>> form = parseForm(body);

        JsonNode raw = MAPPER.createObjectNode();
        try {
            raw = formToRaw(form);
            ConfigLoader.parseConfig(raw); // validate before writing
            writeRaw(configPath, raw);
        } catch (RuntimeException e) {
            sendHtml(exchange, 400, renderPage(raw, e.getMessage(), "", statsInfo()));
            return;
        }

        if (path.equals("/config/apply")) {
            sendHtml(exchange, 200, renderPage(raw, "", "Configuration enregistree, redemarrage du service en cours...", statsInfo()));
            LOG.warning(
                    "redemarrage demande via la page de configuration (bouton appliquer) -- "
                            + "le superviseur du service va le relancer");
            // Persist the latest known state immediately -- the sample itself
            // is already written every fast-loop tick (see StatsStore's
            // javadoc), but this also flushes hourly_energy right away
            // instead of leaving it up to stats.interval_s stale.
            statsStore.persistSnapshot(liveState, energyHistory, inverterEnergyHistory);
            // Delayed so the response above has time to flush to the client's
            // socket before the process exits.
            Thread exitThread = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(1); // exit code 1: compatible with systemd's Restart=on-failure
            });
            exitThread.setDaemon(true);
            exitThread.start();
            return;
        }

        sendHtml(exchange, 200, renderPage(raw, "", "Configuration enregistree. Redemarrez le service pour l'appliquer.", statsInfo()));
    }

    private String statsInfo() {
        long bytes = statsStore.sizeBytes();
        long rows = statsStore.sampleCount();
        String sizeLabel = bytes >= 0 ? formatBytes(bytes) : "taille inconnue";
        String rowsLabel = rows >= 0 ? rows + " lignes" : "nombre de lignes inconnu";
        return sizeLabel + ", " + rowsLabel;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " o";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f Ko", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f Mo", mb);
        }
        return String.format("%.2f Go", mb / 1024.0);
    }

    private static JsonNode loadRaw(Path configPath) {
        try {
            return MAPPER.readTree(Files.readString(configPath));
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static JsonNode dig(JsonNode raw, String dottedPath) {
        JsonNode node = raw;
        for (String key : dottedPath.split("\\.")) {
            node = node.path(key);
        }
        return node;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private static String val(JsonNode raw, String dottedPath, String defaultValue) {
        return escape(dig(raw, dottedPath).asText(defaultValue));
    }

    /**
     * Like {@link #val}, but for numeric fields: reads the value as a
     * double and formats it through {@link #d} instead of taking
     * {@code JsonNode.asText()} verbatim. A value round-tripped through
     * {@code formToRaw}'s {@code ObjectNode.put(String, double)} is written
     * as a JSON double, and {@code asText()} on a whole-number double
     * renders it with a trailing ".0" (e.g. "5.0") -- every saved config
     * ends up displaying that way for any untouched whole-number default
     * without this.
     */
    private static String numVal(JsonNode raw, String dottedPath, double defaultValue) {
        return d(dig(raw, dottedPath).asDouble(defaultValue));
    }

    /**
     * " class=\"changed-value\"" if this field's current value differs from
     * its default, "" otherwise -- lets a saved config that drifted from
     * defaults be spotted at a glance on the config page, without needing a
     * separate source of truth (compares against the exact same
     * {@code defaultValue} string {@link #val} itself falls back to).
     *
     * Compares as numbers, not text: a value round-tripped through
     * {@code formToRaw}'s {@code ObjectNode.put(String, double)} is written
     * as a JSON double, and {@code JsonNode.asText()} on a whole-number
     * double renders it with a trailing ".0" (e.g. "5.0") -- {@link #d}
     * deliberately omits that for whole numbers, so a plain text comparison
     * flagged every untouched whole-number default as "changed".
     */
    private static String changedClass(JsonNode raw, String dottedPath, String defaultValue) {
        double def = Double.parseDouble(defaultValue);
        double current = dig(raw, dottedPath).asDouble(def);
        return current == def ? "" : " class=\"changed-value\"";
    }

    /**
     * Formats a {@code ConfigLoader.Defaults} double the same way the
     * hand-typed literals it replaces read (no trailing ".0" on whole
     * numbers) -- so every default here and in {@link #formToRaw} comes from
     * that single shared constant instead of a second, independently typed
     * copy of the same value that could silently drift out of sync with it.
     */
    private static String d(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> parseForm(String body) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String first(Map<String, List<String>> form, String key, String defaultValue) {
        List<String> values = form.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : defaultValue;
    }

    static JsonNode formToRaw(Map<String, List<String>> form) {
        List<String> serials = form.getOrDefault("inverter_serial", List.of());
        List<String> powers = form.getOrDefault("inverter_nominal_power_w", List.of());
        List<String> names = form.getOrDefault("inverter_name", List.of());
        // Paired with a hidden input of the same name in each row (see
        // addInverterRow / the server-rendered rows below) so every row
        // submits exactly one "true"/"false" value regardless of checked
        // state -- a bare checkbox alone submits nothing when unchecked,
        // which would desync this list's indices from inverter_serial's.
        List<String> controllableFlags = form.getOrDefault("inverter_controllable", List.of());
        ArrayNode inverters = MAPPER.createArrayNode();
        for (int i = 0; i < serials.size(); i++) {
            String serial = serials.get(i).trim();
            if (serial.isEmpty()) {
                continue;
            }
            String name = i < names.size() ? names.get(i).trim() : "";
            boolean controllable = i >= controllableFlags.size() || !"false".equals(controllableFlags.get(i));
            ObjectNode inv = MAPPER.createObjectNode();
            inv.put("serial", serial);
            inv.put("nominal_power_w", Double.parseDouble(powers.get(i)));
            if (name.isEmpty()) {
                inv.putNull("name");
            } else {
                inv.put("name", name);
            }
            inv.put("controllable", controllable);
            inverters.add(inv);
        }

        ObjectNode raw = MAPPER.createObjectNode();

        ObjectNode opendtu = MAPPER.createObjectNode();
        opendtu.put("base_url", first(form, "opendtu.base_url", "").trim());
        String username = first(form, "opendtu.username", "").trim();
        if (username.isEmpty()) {
            opendtu.putNull("username");
        } else {
            opendtu.put("username", username);
        }
        String password = first(form, "opendtu.password", "");
        if (password.isEmpty()) {
            opendtu.putNull("password");
        } else {
            opendtu.put("password", password);
        }
        raw.set("opendtu", opendtu);

        ObjectNode grid = MAPPER.createObjectNode();
        grid.put("export_setpoint_w",
                Double.parseDouble(first(form, "grid.export_setpoint_w", d(ConfigLoader.Defaults.GRID_EXPORT_SETPOINT_W))));
        grid.put("read_interval_s",
                Double.parseDouble(first(form, "grid.read_interval_s", d(ConfigLoader.Defaults.GRID_READ_INTERVAL_S))));
        grid.put("ema_alpha", Double.parseDouble(first(form, "grid.ema_alpha", d(ConfigLoader.Defaults.GRID_EMA_ALPHA))));
        ObjectNode modbus = MAPPER.createObjectNode();
        modbus.put("host", first(form, "grid.modbus.host", "").trim());
        modbus.put("port",
                (int) Double.parseDouble(first(form, "grid.modbus.port", d(ConfigLoader.Defaults.GRID_MODBUS_PORT))));
        grid.set("modbus", modbus);
        raw.set("grid", grid);

        ObjectNode control = MAPPER.createObjectNode();
        control.put("kp", Double.parseDouble(first(form, "control.kp", d(ConfigLoader.Defaults.CONTROL_KP))));
        control.put("ki", Double.parseDouble(first(form, "control.ki", d(ConfigLoader.Defaults.CONTROL_KI))));
        control.put("decision_interval_s",
                Double.parseDouble(first(form, "control.decision_interval_s", d(ConfigLoader.Defaults.CONTROL_DECISION_INTERVAL_S))));
        control.put("step_absolute_w",
                Double.parseDouble(first(form, "control.step_absolute_w", d(ConfigLoader.Defaults.CONTROL_STEP_ABSOLUTE_W))));
        control.put("step_relative_pct",
                Double.parseDouble(first(form, "control.step_relative_pct", d(ConfigLoader.Defaults.CONTROL_STEP_RELATIVE_PCT))));
        control.put("min_change_w",
                Double.parseDouble(first(form, "control.min_change_w", d(ConfigLoader.Defaults.CONTROL_MIN_CHANGE_W))));
        control.put("min_inverter_pct",
                Double.parseDouble(first(form, "control.min_inverter_pct", d(ConfigLoader.Defaults.CONTROL_MIN_INVERTER_PCT))));
        control.put("min_battery_discharge_w",
                Double.parseDouble(first(
                        form, "control.min_battery_discharge_w", d(ConfigLoader.Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W))));
        raw.set("control", control);

        ObjectNode probe = MAPPER.createObjectNode();
        probe.put("step_w", Double.parseDouble(first(form, "capacity_probe.step_w", d(ConfigLoader.Defaults.CAPACITY_PROBE_STEP_W))));
        probe.put("interval_s",
                Double.parseDouble(first(form, "capacity_probe.interval_s", d(ConfigLoader.Defaults.CAPACITY_PROBE_INTERVAL_S))));
        raw.set("capacity_probe", probe);

        ObjectNode stats = MAPPER.createObjectNode();
        stats.put("interval_s", Double.parseDouble(first(form, "stats.interval_s", d(ConfigLoader.Defaults.STATS_INTERVAL_S))));
        stats.put("retention_days",
                (int) Double.parseDouble(first(form, "stats.retention_days", d(ConfigLoader.Defaults.STATS_RETENTION_DAYS))));
        stats.put("high_res_retention_days", (int) Double.parseDouble(
                first(form, "stats.high_res_retention_days", d(ConfigLoader.Defaults.STATS_HIGH_RES_RETENTION_DAYS))));
        raw.set("stats", stats);

        ObjectNode battery = MAPPER.createObjectNode();
        battery.put("enabled", form.containsKey("battery.enabled"));
        battery.put("activate_at_pct",
                Double.parseDouble(first(form, "battery.activate_at_pct", d(ConfigLoader.Defaults.BATTERY_ACTIVATE_AT_PCT))));
        battery.put("deactivate_below_pct",
                Double.parseDouble(first(form, "battery.deactivate_below_pct", d(ConfigLoader.Defaults.BATTERY_DEACTIVATE_BELOW_PCT))));
        battery.put("export_confirms_full_w",
                Double.parseDouble(first(form, "battery.export_confirms_full_w", d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W))));
        battery.put("export_confirms_full_duration_s",
                Double.parseDouble(first(
                        form, "battery.export_confirms_full_duration_s",
                        d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S))));
        raw.set("battery", battery);

        ObjectNode web = MAPPER.createObjectNode();
        web.put("port", (int) Double.parseDouble(first(form, "web.port", d(ConfigLoader.Defaults.WEB_PORT))));
        web.put("chart_height_px",
                (int) Double.parseDouble(first(form, "web.chart_height_px", d(ConfigLoader.Defaults.CHART_HEIGHT_PX))));
        raw.set("web", web);

        ObjectNode logging = MAPPER.createObjectNode();
        logging.put("verbose_traces", form.containsKey("logging.verbose_traces"));
        raw.set("logging", logging);

        ObjectNode sunspecProxy = MAPPER.createObjectNode();
        sunspecProxy.put("enabled", form.containsKey("sunspec_proxy.enabled"));
        sunspecProxy.put("tcp_port",
                (int) Double.parseDouble(first(form, "sunspec_proxy.tcp_port", d(ConfigLoader.Defaults.SUNSPEC_PROXY_TCP_PORT))));
        sunspecProxy.put("poll_interval_s",
                Double.parseDouble(first(form, "sunspec_proxy.poll_interval_s", d(ConfigLoader.Defaults.SUNSPEC_PROXY_POLL_INTERVAL_S))));
        sunspecProxy.put("manufacturer",
                first(form, "sunspec_proxy.manufacturer", ConfigLoader.Defaults.SUNSPEC_PROXY_MANUFACTURER).trim());
        sunspecProxy.put("model", first(form, "sunspec_proxy.model", ConfigLoader.Defaults.SUNSPEC_PROXY_MODEL).trim());
        sunspecProxy.put("serial_number",
                first(form, "sunspec_proxy.serial_number", ConfigLoader.Defaults.SUNSPEC_PROXY_SERIAL_NUMBER).trim());
        raw.set("sunspec_proxy", sunspecProxy);

        raw.set("inverters", inverters);
        return raw;
    }

    private static void writeRaw(Path configPath, JsonNode raw) {
        Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(raw) + "\n");
            Files.move(
                    tmpPath,
                    configPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write config to " + configPath, e);
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
    }

    private static String inverterRowsHtml(JsonNode inverters) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode inv : inverters) {
            sb.append("<tr class=\"inv-row\">")
                    .append("<td><input type=\"text\" name=\"inverter_serial\" value=\"")
                    .append(escape(inv.path("serial").asText("")))
                    .append("\" required></td>")
                    .append("<td><input type=\"text\" name=\"inverter_name\" value=\"")
                    .append(escape(inv.path("name").isMissingNode() || inv.path("name").isNull() ? "" : inv.path("name").asText("")))
                    .append("\" placeholder=\"(optionnel)\"></td>")
                    .append("<td><input type=\"number\" name=\"inverter_nominal_power_w\" value=\"")
                    .append(escape(inv.path("nominal_power_w").asText("")))
                    .append("\" step=\"1\" min=\"1\" required></td>")
                    .append("<td><input type=\"hidden\" name=\"inverter_controllable\" value=\"")
                    .append(inv.path("controllable").asBoolean(true) ? "true" : "false")
                    .append("\"><input type=\"checkbox\"")
                    .append(inv.path("controllable").asBoolean(true) ? " checked" : "")
                    .append(" onchange=\"this.previousElementSibling.value = this.checked ? 'true' : 'false'\"></td>")
                    .append("<td><button type=\"button\" class=\"remove-btn\" "
                            + "onclick=\"this.closest('tr').remove()\">&times;</button></td></tr>\n");
        }
        return sb.toString();
    }

    private static String renderPage(JsonNode raw, String error, String message, String statsInfo) {
        String banner = "";
        if (error != null && !error.isEmpty()) {
            banner = "<div class=\"banner error\">" + escape(error) + "</div>";
        } else if (message != null && !message.isEmpty()) {
            banner = "<div class=\"banner ok\">" + escape(message) + "</div>";
        }

        String invertersHtml = inverterRowsHtml(dig(raw, "inverters"));
        boolean batteryEnabled = dig(raw, "battery.enabled").asBoolean(ConfigLoader.Defaults.BATTERY_ENABLED);
        boolean verboseTraces = dig(raw, "logging.verbose_traces").asBoolean(ConfigLoader.Defaults.LOGGING_VERBOSE_TRACES);
        boolean sunspecProxyEnabled =
                dig(raw, "sunspec_proxy.enabled").asBoolean(ConfigLoader.Defaults.SUNSPEC_PROXY_ENABLED);

        return "<!doctype html>\n"
                + "<html lang=\"fr\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>GX-DTU Injection Controller - configuration</title>\n"
                + "<style>\n"
                + "  :root {\n"
                + "    --surface-1: #fcfcfb; --page: #f9f9f7; --text-primary: #0b0b0b; --text-secondary: #52514e;\n"
                + "    --muted: #898781; --border: rgba(11,11,11,0.10);\n"
                + "    --series-1: #2a78d6; --good: #0ca30c; --warning: #fab219; --critical: #d03b3b;\n"
                + "  }\n"
                + "  @media (prefers-color-scheme: dark) {\n"
                + "    :root {\n"
                + "      --surface-1: #1a1a19; --page: #0d0d0d; --text-primary: #ffffff; --text-secondary: #c3c2b7;\n"
                + "      --muted: #898781; --border: rgba(255,255,255,0.10);\n"
                + "      --series-1: #3987e5; --good: #0ca30c; --warning: #fab219; --critical: #e66767;\n"
                + "    }\n"
                + "  }\n"
                + "  * { box-sizing: border-box; }\n"
                + "  body { font-family: system-ui, -apple-system, \"Segoe UI\", sans-serif; max-width: 960px; margin: 2rem auto;\n"
                + "         padding: 0 1rem; color: var(--text-primary); background: var(--page); }\n"
                + "  nav { margin-bottom: 1.2rem; display: flex; justify-content: center; gap: 0.4rem; }\n"
                + "  nav a { padding: 0.4rem 0.9rem; border-radius: 6px; font-size: 0.85rem; text-decoration: none;\n"
                + "          color: var(--text-secondary); border: 1px solid var(--border); background: var(--surface-1); }\n"
                + "  nav a:hover { background: var(--border); }\n"
                + "  nav a.active { background: var(--series-1); color: white; border-color: var(--series-1); }\n"
                + "  .eyebrow { font-size: 0.72rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase;\n"
                + "             color: var(--muted); margin: 0 0 1rem; text-align: center; }\n"
                + "  .eyebrow .build-tag { font-weight: 400; text-transform: none; letter-spacing: normal; }\n"
                + "  fieldset { margin-bottom: 1.2rem; border: 1px solid var(--border); border-radius: 8px;\n"
                + "             padding: 0.9rem 1rem 1.1rem; background: var(--surface-1);\n"
                + "             display: grid; grid-template-columns: minmax(200px, 260px) 1fr;\n"
                + "             column-gap: 1rem; row-gap: 0.5rem; align-items: center; }\n"
                + "  legend { font-weight: 600; padding: 0 0.4rem; color: var(--text-primary); grid-column: 1 / -1; }\n"
                + "  label { margin: 0; font-size: 0.9rem; color: var(--text-secondary); grid-column: 1; }\n"
                + "  label:has(input[type=checkbox]) { grid-column: 1 / -1; color: var(--text-primary); }\n"
                + "  input[type=text], input[type=number], input[type=password] {\n"
                + "    grid-column: 2; width: 100%; padding: 0.4rem 0.5rem; box-sizing: border-box; font: inherit;\n"
                + "    background: var(--page); color: var(--text-primary); border: 1px solid var(--border); border-radius: 6px;\n"
                + "  }\n"
                + "  input[type=text]:focus, input[type=number]:focus, input[type=password]:focus {\n"
                + "    outline: 2px solid var(--series-1); outline-offset: -1px;\n"
                + "  }\n"
                + "  input[type=checkbox] { margin-right: 0.4rem; }\n"
                + "  fieldset > p.hint, fieldset > table, fieldset > button, fieldset > div {\n"
                + "    grid-column: 1 / -1;\n"
                + "  }\n"
                + "  fieldset > button { justify-self: start; }\n"
                + "  table { width: 100%; border-collapse: collapse; }\n"
                + "  td { padding: 0.35rem 0.25rem; }\n"
                + "  button { font: inherit; }\n"
                + "  button:not(.primary):not(.remove-btn) { padding: 0.45rem 0.9rem; border-radius: 6px; cursor: pointer;\n"
                + "    border: 1px solid var(--text-secondary); background: var(--page); color: var(--text-primary); }\n"
                + "  button:not(.primary):not(.remove-btn):hover { background: var(--border); }\n"
                + "  .remove-btn { color: var(--critical); border: none; background: none; font-size: 1.2rem; cursor: pointer; }\n"
                + "  .banner { padding: 0.6rem 1rem; border-radius: 8px; margin-bottom: 1rem; font-size: 0.9rem; }\n"
                + "  .banner.error { background: #fde2e2; border: 1px solid var(--critical); color: #7a1212; }\n"
                + "  .banner.ok { background: #e2f6e2; border: 1px solid var(--good); color: #1a5c1a; }\n"
                + "  @media (prefers-color-scheme: dark) {\n"
                + "    .banner.error { background: #3a1414; color: #ffffff; }\n"
                + "    .banner.ok { background: #163a16; color: #ffffff; }\n"
                + "  }\n"
                + "  button.primary { padding: 0.6rem 1.2rem; background: var(--series-1); color: white; border: none;\n"
                + "                    border-radius: 6px; cursor: pointer; font-size: 1rem; margin-right: 0.5rem; }\n"
                + "  button.apply-btn { background: var(--warning); color: #1a1200; }\n"
                + "  #add-inv-btn { margin-top: 0.5rem; }\n"
                + "  .hint { color: var(--muted); font-size: 0.82rem; margin: 0.2rem 0 0; }\n"
                + "  input.changed-value { color: var(--warning); font-weight: 600; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<nav><a href=\"/dashboard\">Tableau de bord</a><a href=\"/config\" class=\"active\">Configuration</a>"
                + "<a href=\"/internal\">Etat interne</a></nav>\n"
                + "<p class=\"eyebrow\">GX-DTU Injection Controller"
                + (BuildInfo.timestamp() != null
                        ? " <span class=\"build-tag\">(build " + escape(BuildInfo.timestamp()) + ")</span>"
                        : "")
                + "</p>\n"
                + banner
                + "<form method=\"post\" action=\"/config/save\">\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>OpenDTU</legend>\n"
                + "    <label>URL de base OpenDTU</label>\n"
                + "    <input type=\"text\" name=\"opendtu.base_url\" value=\"" + val(raw, "opendtu.base_url", "") + "\" required>\n"
                + "    <label>Nom d'utilisateur (si Basic Auth activee sur OpenDTU)</label>\n"
                + "    <input type=\"text\" name=\"opendtu.username\" value=\"" + val(raw, "opendtu.username", "") + "\" autocomplete=\"off\">\n"
                + "    <label>Mot de passe</label>\n"
                + "    <input type=\"password\" name=\"opendtu.password\" value=\"" + val(raw, "opendtu.password", "") + "\" autocomplete=\"off\">\n"
                + "    <p class=\"hint\">Necessaire uniquement si OpenDTU renvoie \"401 Unauthorized\" -- laisser vide sinon. "
                + "Sans authentification, le controleur ne peut pas limiter les onduleurs, y compris le repli fail-safe.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Reseau (grid, Modbus TCP)</legend>\n"
                + "    <label>Consigne d'export (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"grid.export_setpoint_w\" value=\"" + numVal(raw, "grid.export_setpoint_w", ConfigLoader.Defaults.GRID_EXPORT_SETPOINT_W) + "\"" + changedClass(raw, "grid.export_setpoint_w", d(ConfigLoader.Defaults.GRID_EXPORT_SETPOINT_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.GRID_EXPORT_SETPOINT_W) + "\" required>\n"
                + "    <p class=\"hint\">C'est la puissance reseau (soutirage positif / export negatif) que le "
                + "regulateur essaie de maintenir en permanence -- pas un budget d'energie, une cible de puissance "
                + "instantanee reevaluee en continu. Valeur positive (defaut 30 W) : vise une petite marge de "
                + "soutirage, jamais 0 W pile, pour ne jamais basculer en export a cause du bruit de mesure ou du "
                + "temps de reaction du regulateur -- c'est le mode \"zero export\" normal de ce projet. Valeur "
                + "negative (ex. -50) : vise au contraire un export reel controle de cette puissance (ici 50 W) en "
                + "permanence, si vous voulez au contraire autoriser/viser une injection reseau continue.</p>\n"
                + "    <label>Intervalle de lecture (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"grid.read_interval_s\" value=\"" + numVal(raw, "grid.read_interval_s", ConfigLoader.Defaults.GRID_READ_INTERVAL_S) + "\"" + changedClass(raw, "grid.read_interval_s", d(ConfigLoader.Defaults.GRID_READ_INTERVAL_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.GRID_READ_INTERVAL_S) + "\" required>\n"
                + "    <p class=\"hint\">Cadence de lecture de la puissance reseau (boucle rapide). C'est la resolution "
                + "du tableau de bord temps reel et la base de temps du lissage EMA ci-dessous -- ne pilote pas "
                + "directement les onduleurs (voir \"Intervalle de decision\" plus bas).</p>\n"
                + "    <label>Coefficient EMA (0-1)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" max=\"1\" name=\"grid.ema_alpha\" value=\"" + numVal(raw, "grid.ema_alpha", ConfigLoader.Defaults.GRID_EMA_ALPHA) + "\"" + changedClass(raw, "grid.ema_alpha", d(ConfigLoader.Defaults.GRID_EMA_ALPHA)) + " placeholder=\"" + d(ConfigLoader.Defaults.GRID_EMA_ALPHA) + "\" required>\n"
                + "    <p class=\"hint\">Lissage de la puissance reseau (moyenne mobile exponentielle) avant qu'elle "
                + "n'atteigne le regulateur -- une lecture brute unique est trop bruitee pour piloter directement. Plus "
                + "proche de 1 : reagit vite aux vraies variations mais laisse plus de bruit passer. Plus proche de 0 : "
                + "plus lisse mais plus lent a suivre un vrai changement de consommation/production.</p>\n"
                + "    <label>Hote Modbus (IP Cerbo GX)</label>\n"
                + "    <input type=\"text\" name=\"grid.modbus.host\" value=\"" + val(raw, "grid.modbus.host", "") + "\" required>\n"
                + "    <label>Port Modbus</label>\n"
                + "    <input type=\"number\" name=\"grid.modbus.port\" value=\"" + numVal(raw, "grid.modbus.port", ConfigLoader.Defaults.GRID_MODBUS_PORT) + "\"" + changedClass(raw, "grid.modbus.port", d(ConfigLoader.Defaults.GRID_MODBUS_PORT)) + " placeholder=\"" + d(ConfigLoader.Defaults.GRID_MODBUS_PORT) + "\">\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Asservissement (control)</legend>\n"
                + "    <p class=\"hint\">Le regulateur PI calcule la puissance a demander aux onduleurs a partir de "
                + "l'ecart entre la puissance reseau mesuree et la consigne d'export (ci-dessus). kp et ki reglent sa "
                + "reactivite -- inutile d'y toucher sauf si la regulation oscille (descendre kp) ou reagit trop "
                + "lentement (monter kp/ki).</p>\n"
                + "    <label>Gain proportionnel -- kp</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.kp\" value=\"" + numVal(raw, "control.kp", ConfigLoader.Defaults.CONTROL_KP) + "\"" + changedClass(raw, "control.kp", d(ConfigLoader.Defaults.CONTROL_KP)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_KP) + "\" required>\n"
                + "    <p class=\"hint\">Reaction immediate a l'ecart actuel : plus kp est grand, plus fort et rapide est "
                + "l'ajustement a chaque cycle. Trop haut -- ca oscille (la puissance part dans un sens puis l'autre) ; "
                + "trop bas -- la regulation reagit mollement aux variations rapides de production/consommation.</p>\n"
                + "    <label>Gain integral -- ki</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.ki\" value=\"" + numVal(raw, "control.ki", ConfigLoader.Defaults.CONTROL_KI) + "\"" + changedClass(raw, "control.ki", d(ConfigLoader.Defaults.CONTROL_KI)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_KI) + "\" required>\n"
                + "    <p class=\"hint\">Corrige un ecart qui persiste dans le temps (kp seul ne l'annule jamais "
                + "completement) en accumulant l'erreur cycle apres cycle. Trop haut -- la correction s'emballe et "
                + "depasse la cible (overshoot) ; trop bas -- un petit ecart residuel peut rester longtemps.</p>\n"
                + "    <label>Intervalle de decision (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.decision_interval_s\" value=\"" + numVal(raw, "control.decision_interval_s", ConfigLoader.Defaults.CONTROL_DECISION_INTERVAL_S) + "\"" + changedClass(raw, "control.decision_interval_s", d(ConfigLoader.Defaults.CONTROL_DECISION_INTERVAL_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_DECISION_INTERVAL_S) + "\" required>\n"
                + "    <p class=\"hint\">Cadence a laquelle le regulateur PI recalcule sa cible ET envoie effectivement "
                + "une commande a OpenDTU -- plus lente et distincte de l'intervalle de lecture reseau ci-dessus (qui ne "
                + "fait que mettre a jour la mesure lissee en memoire). Trop court : sollicite OpenDTU/RF inutilement ; "
                + "trop long : reagit plus lentement aux changements de consommation.</p>\n"
                + "    <label>Palier absolu (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.step_absolute_w\" value=\"" + numVal(raw, "control.step_absolute_w", ConfigLoader.Defaults.CONTROL_STEP_ABSOLUTE_W) + "\"" + changedClass(raw, "control.step_absolute_w", d(ConfigLoader.Defaults.CONTROL_STEP_ABSOLUTE_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_STEP_ABSOLUTE_W) + "\" required>\n"
                + "    <label>Palier relatif (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.step_relative_pct\" value=\"" + numVal(raw, "control.step_relative_pct", ConfigLoader.Defaults.CONTROL_STEP_RELATIVE_PCT) + "\"" + changedClass(raw, "control.step_relative_pct", d(ConfigLoader.Defaults.CONTROL_STEP_RELATIVE_PCT)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_STEP_RELATIVE_PCT) + "\" required>\n"
                + "    <p class=\"hint\">Limite le changement de consigne autorise par cycle de decision (evite les a-coups) "
                + "-- la limite reellement appliquee est la plus grande des deux : le palier absolu (W) ou le palier "
                + "relatif (% de la capacite totale actuelle des onduleurs). Le palier absolu protege les petites "
                + "installations (un pourcentage serait trop fin) ; le palier relatif evite qu'une grosse installation "
                + "soit bridee par un palier absolu trop petit.</p>\n"
                + "    <label>Changement minimal (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.min_change_w\" value=\"" + numVal(raw, "control.min_change_w", ConfigLoader.Defaults.CONTROL_MIN_CHANGE_W) + "\"" + changedClass(raw, "control.min_change_w", d(ConfigLoader.Defaults.CONTROL_MIN_CHANGE_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_MIN_CHANGE_W) + "\" required>\n"
                + "    <p class=\"hint\">En dessous de cet ecart avec la derniere consigne envoyee, rien n'est renvoye a "
                + "OpenDTU -- evite de solliciter la liaison RF/HTTP pour des variations negligeables.</p>\n"
                + "    <label>Seuil mini onduleur (% de sa puissance nominale)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" max=\"100\" name=\"control.min_inverter_pct\" value=\"" + numVal(raw, "control.min_inverter_pct", ConfigLoader.Defaults.CONTROL_MIN_INVERTER_PCT) + "\"" + changedClass(raw, "control.min_inverter_pct", d(ConfigLoader.Defaults.CONTROL_MIN_INVERTER_PCT)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_MIN_INVERTER_PCT) + "\" required>\n"
                + "    <p class=\"hint\">Un onduleur qui produit n'est jamais commande sous ce seuil. Mettre 0 pour desactiver. "
                + "Un arret complet (fail-safe, charge batterie) n'est jamais concerne.</p>\n"
                + "    <label>Decharge batterie ignoree en dessous de (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" name=\"control.min_battery_discharge_w\" value=\"" + numVal(raw, "control.min_battery_discharge_w", ConfigLoader.Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W) + "\"" + changedClass(raw, "control.min_battery_discharge_w", d(ConfigLoader.Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W) + "\" required>\n"
                + "    <p class=\"hint\">En dessous de cette puissance, une decharge batterie est consideree comme du bruit "
                + "normal (auto-consommation/flottaison a batterie pleine) et n'oblige pas la consigne a remonter. Au-dessus, "
                + "la consigne est relevee pour que le solaire couvre la decharge plutot que la batterie. Mettre 0 pour "
                + "revenir a l'ancien comportement (toute decharge, meme infime, relance la consigne).</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Sonde de capacite (capacity_probe)</legend>\n"
                + "    <p class=\"hint\">Quand un onduleur ne produit pas ce qui lui a ete demande (nuage, ombre passagere), "
                + "le systeme suppose qu'il est limite par l'ensoleillement et abaisse temporairement son plafond de "
                + "puissance estime, pour ne pas lui redemander plus qu'il ne peut donner. Cette sonde relance "
                + "periodiquement ce plafond a la hausse, pour verifier s'il peut de nouveau produire plus (le nuage est "
                + "passe) -- sans ca, un onduleur resterait bride en permanence apres une seule ombre passagere.</p>\n"
                + "    <label>Palier de sonde (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"capacity_probe.step_w\" value=\"" + numVal(raw, "capacity_probe.step_w", ConfigLoader.Defaults.CAPACITY_PROBE_STEP_W) + "\"" + changedClass(raw, "capacity_probe.step_w", d(ConfigLoader.Defaults.CAPACITY_PROBE_STEP_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.CAPACITY_PROBE_STEP_W) + "\" required>\n"
                + "    <p class=\"hint\">De combien remonter le plafond estime a chaque sonde (jamais au-dela de la "
                + "puissance nominale reelle de l'onduleur).</p>\n"
                + "    <label>Intervalle de sonde (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"capacity_probe.interval_s\" value=\"" + numVal(raw, "capacity_probe.interval_s", ConfigLoader.Defaults.CAPACITY_PROBE_INTERVAL_S) + "\"" + changedClass(raw, "capacity_probe.interval_s", d(ConfigLoader.Defaults.CAPACITY_PROBE_INTERVAL_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.CAPACITY_PROBE_INTERVAL_S) + "\" required>\n"
                + "    <p class=\"hint\">A quelle frequence relancer le plafond a la hausse.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Statistiques long terme (stats)</legend>\n"
                + "    <p class=\"hint\">Courbes (reseau, SOC, batterie, par onduleur) et energie horaire persistees dans "
                + "stats.db pour l'historique long terme, independamment du tableau de bord temps reel (~30min/48h en memoire). "
                + "Deux resolutions pour limiter la taille du fichier : chaque mesure est ecrite telle quelle (meme cadence "
                + "que le direct) pendant la duree \"haute resolution\" ci-dessous, puis les points plus vieux sont "
                + "regroupes a l'intervalle de purge (un seul point conserve par intervalle) -- vous perdez alors le detail "
                + "fin, pas la courbe elle-meme.</p>\n"
                + "    <label>Duree haute resolution (jours)</label>\n"
                + "    <input type=\"number\" step=\"1\" min=\"1\" name=\"stats.high_res_retention_days\" value=\"" + numVal(raw, "stats.high_res_retention_days", ConfigLoader.Defaults.STATS_HIGH_RES_RETENTION_DAYS) + "\"" + changedClass(raw, "stats.high_res_retention_days", d(ConfigLoader.Defaults.STATS_HIGH_RES_RETENTION_DAYS)) + " placeholder=\"" + d(ConfigLoader.Defaults.STATS_HIGH_RES_RETENTION_DAYS) + "\" required>\n"
                + "    <p class=\"hint\">Pendant ces N derniers jours, le tableau de bord peut zoomer sur n'importe quel "
                + "instant precis (meme resolution que le direct). Au-dela, seule la tendance generale reste visible. "
                + "Plus cette duree est longue, plus stats.db grossit vite -- voir la taille actuelle ci-dessous.</p>\n"
                + "    <label>Intervalle de regroupement au-dela (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"1\" name=\"stats.interval_s\" value=\"" + numVal(raw, "stats.interval_s", ConfigLoader.Defaults.STATS_INTERVAL_S) + "\"" + changedClass(raw, "stats.interval_s", d(ConfigLoader.Defaults.STATS_INTERVAL_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.STATS_INTERVAL_S) + "\" required>\n"
                + "    <p class=\"hint\">Une fois la duree haute resolution depassee, un seul point conserve par intervalle "
                + "de ce nombre de secondes (300s = 5 min par defaut).</p>\n"
                + "    <label>Retention totale (jours)</label>\n"
                + "    <input type=\"number\" step=\"1\" min=\"1\" name=\"stats.retention_days\" value=\"" + numVal(raw, "stats.retention_days", ConfigLoader.Defaults.STATS_RETENTION_DAYS) + "\"" + changedClass(raw, "stats.retention_days", d(ConfigLoader.Defaults.STATS_RETENTION_DAYS)) + " placeholder=\"" + d(ConfigLoader.Defaults.STATS_RETENTION_DAYS) + "\" required>\n"
                + "    <p class=\"hint\">Donnees plus vieilles que cette retention totale purgees automatiquement (doit "
                + "etre >= duree haute resolution).</p>\n"
                + "    <p class=\"hint\">stats.db : " + escape(statsInfo) + "</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Batterie (priorite charge)</legend>\n"
                + "    <label><input type=\"checkbox\" name=\"battery.enabled\"" + (batteryEnabled ? " checked" : "") + "> Activer</label>\n"
                + "    <p class=\"hint\">Desactive : la regulation zero-export normale tourne en permanence (comme si la "
                + "batterie n'existait pas). Active : quand la batterie n'est pas encore pleine, les onduleurs sont "
                + "debrides (100%) pour la charger au maximum, meme si ca veut dire exporter brievement -- la regulation "
                + "zero-export ne reprend qu'une fois la batterie consideree pleine (voir les deux seuils ci-dessous).</p>\n"
                + "    <label>Seuil d'activation SOC (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"battery.activate_at_pct\" value=\"" + numVal(raw, "battery.activate_at_pct", ConfigLoader.Defaults.BATTERY_ACTIVATE_AT_PCT) + "\"" + changedClass(raw, "battery.activate_at_pct", d(ConfigLoader.Defaults.BATTERY_ACTIVATE_AT_PCT)) + " placeholder=\"" + d(ConfigLoader.Defaults.BATTERY_ACTIVATE_AT_PCT) + "\" required>\n"
                + "    <label>Seuil de desactivation SOC (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"battery.deactivate_below_pct\" value=\"" + numVal(raw, "battery.deactivate_below_pct", ConfigLoader.Defaults.BATTERY_DEACTIVATE_BELOW_PCT) + "\"" + changedClass(raw, "battery.deactivate_below_pct", d(ConfigLoader.Defaults.BATTERY_DEACTIVATE_BELOW_PCT)) + " placeholder=\"" + d(ConfigLoader.Defaults.BATTERY_DEACTIVATE_BELOW_PCT) + "\" required>\n"
                + "    <p class=\"hint\">Deux seuils distincts (avec une zone morte entre les deux) pour eviter que le "
                + "systeme bascule sans arret entre les deux modes quand le SOC oscille autour d'une seule valeur. "
                + "\"Activation\" = SOC a partir duquel la batterie est consideree pleine et la regulation zero-export "
                + "reprend (onduleurs de nouveau brides). \"Desactivation\" = SOC en dessous duquel on repasse en charge "
                + "prioritaire (onduleurs debrides). Toujours activation &gt;= desactivation.</p>\n"
                + "    <label>Export confirmant la batterie pleine (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" name=\"battery.export_confirms_full_w\" value=\"" + numVal(raw, "battery.export_confirms_full_w", ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W) + "\"" + changedClass(raw, "battery.export_confirms_full_w", d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W)) + " placeholder=\"" + d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W) + "\" required>\n"
                + "    <label>Duree minimale de cet export (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" name=\"battery.export_confirms_full_duration_s\" value=\"" + numVal(raw, "battery.export_confirms_full_duration_s", ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S) + "\"" + changedClass(raw, "battery.export_confirms_full_duration_s", d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S) + "\" required>\n"
                + "    <p class=\"hint\">Passe en regulation ON dès que l'export reseau reel reste en continu au moins a "
                + "cette puissance pendant au moins cette duree, alors que le SOC est deja au-dessus du seuil de "
                + "desactivation -- l'estimation du SOC peut avoir du retard sur la realite (frequent en fin de charge "
                + "sur certaines chimies de batterie type LFP), donc un export reel mesure est une preuve plus fiable "
                + "que la batterie est pleine que le SOC lui-meme. La duree minimale evite qu'un pic d'export isole et "
                + "bref (ex. une charge qui s'eteint un instant) declenche ca a tort. Mettre 0 pour desactiver cette "
                + "detection precoce (la duree devient alors sans effet).</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Onduleurs</legend>\n"
                + "    <table id=\"inv-table\">\n"
                + "      <thead><tr><th>Serie</th><th>Nom</th><th>Puissance nominale (W)</th><th>Pilotable</th><th></th></tr></thead>\n"
                + "      <tbody id=\"inv-tbody\">\n"
                + invertersHtml
                + "      </tbody>\n"
                + "    </table>\n"
                + "    <button type=\"button\" id=\"add-inv-btn\" onclick=\"addInverterRow()\">+ Ajouter un onduleur (manuel)</button>\n"
                + "    <p class=\"hint\">Decocher \"Pilotable\" pour un onduleur : sa puissance mesuree reste affichee sur le "
                + "tableau de bord, mais la regulation ne lui envoie plus jamais de commande (jamais de changement de limite), "
                + "quel que soit le mode (auto, charge batterie prioritaire, forcage manuel, fail-safe compris).</p>\n"
                + "\n"
                + "    <div style=\"margin-top:0.8rem\">\n"
                + "      <button type=\"button\" onclick=\"fetchInverters()\">Charger la liste depuis OpenDTU</button>\n"
                + "      <p class=\"hint\" id=\"fetch-status\"></p>\n"
                + "      <div id=\"discovered-list\"></div>\n"
                + "      <p class=\"hint\">Cocher un onduleur decouvert l'ajoute a la liste ci-dessus -- decocher ne retire rien, "
                + "utilisez le bouton &times; sur la ligne pour retirer un onduleur deja ajoute.</p>\n"
                + "    </div>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Page de configuration (web)</legend>\n"
                + "    <label>Port</label>\n"
                + "    <input type=\"number\" name=\"web.port\" value=\"" + numVal(raw, "web.port", ConfigLoader.Defaults.WEB_PORT) + "\"" + changedClass(raw, "web.port", d(ConfigLoader.Defaults.WEB_PORT)) + " placeholder=\"" + d(ConfigLoader.Defaults.WEB_PORT) + "\" required>\n"
                + "    <p class=\"hint\">Necessite un redemarrage du service pour prendre effet.</p>\n"
                + "    <label>Hauteur des graphiques du tableau de bord (px)</label>\n"
                + "    <input type=\"number\" name=\"web.chart_height_px\" min=\"" + ConfigLoader.Defaults.CHART_HEIGHT_PX_MIN
                + "\" max=\"" + ConfigLoader.Defaults.CHART_HEIGHT_PX_MAX
                + "\" value=\"" + numVal(raw, "web.chart_height_px", ConfigLoader.Defaults.CHART_HEIGHT_PX) + "\"" + changedClass(raw, "web.chart_height_px", d(ConfigLoader.Defaults.CHART_HEIGHT_PX)) + " placeholder=\"" + d(ConfigLoader.Defaults.CHART_HEIGHT_PX) + "\" required>\n"
                + "    <p class=\"hint\">De " + ConfigLoader.Defaults.CHART_HEIGHT_PX_MIN + " (defaut) a "
                + ConfigLoader.Defaults.CHART_HEIGHT_PX_MAX + " -- meme hauteur pour tous les graphiques. "
                + "Prend effet au prochain chargement de la page (pas besoin de redemarrer le service).</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Proxy SunSpec (spike, experimental)</legend>\n"
                + "    <label><input type=\"checkbox\" name=\"sunspec_proxy.enabled\"" + (sunspecProxyEnabled ? " checked" : "") + "> Activer</label>\n"
                + "    <p class=\"hint\">Expose un serveur Modbus TCP SunSpec (modeles 1/101/120/123) en plus de la "
                + "regulation existante -- pour tester si Venus OS le detecte. Puissance reelle des onduleurs cote lecture ; "
                + "ce que Victron ecrit (WMaxLimPct/Conn) est juste affiche sur /internal, jamais transmis a OpenDTU/aux "
                + "onduleurs reels. Aucun effet sur la regulation zero-export ci-dessus, active ou non.</p>\n"
                + "    <label>Port TCP</label>\n"
                + "    <input type=\"number\" step=\"1\" name=\"sunspec_proxy.tcp_port\" value=\"" + numVal(raw, "sunspec_proxy.tcp_port", ConfigLoader.Defaults.SUNSPEC_PROXY_TCP_PORT) + "\"" + changedClass(raw, "sunspec_proxy.tcp_port", d(ConfigLoader.Defaults.SUNSPEC_PROXY_TCP_PORT)) + " placeholder=\"" + d(ConfigLoader.Defaults.SUNSPEC_PROXY_TCP_PORT) + "\" required>\n"
                + "    <p class=\"hint\">502 (standard Modbus) par defaut : confirme sur une installation Venus OS que "
                + "le scan Modbus ne cherche que ce port, un port personnalise (ex. 1502) n'est pas detecte. Necessite "
                + "root ou la capacite CAP_NET_BIND_SERVICE sur Linux pour se lier en dessous de 1024 -- voir "
                + "deploy/systemd/gx-opendtu-zero-export.service.</p>\n"
                + "    <label>Intervalle de lecture OpenDTU (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0.1\" name=\"sunspec_proxy.poll_interval_s\" value=\"" + numVal(raw, "sunspec_proxy.poll_interval_s", ConfigLoader.Defaults.SUNSPEC_PROXY_POLL_INTERVAL_S) + "\"" + changedClass(raw, "sunspec_proxy.poll_interval_s", d(ConfigLoader.Defaults.SUNSPEC_PROXY_POLL_INTERVAL_S)) + " placeholder=\"" + d(ConfigLoader.Defaults.SUNSPEC_PROXY_POLL_INTERVAL_S) + "\" required>\n"
                + "    <label>Fabricant declare</label>\n"
                + "    <input type=\"text\" name=\"sunspec_proxy.manufacturer\" value=\"" + val(raw, "sunspec_proxy.manufacturer", ConfigLoader.Defaults.SUNSPEC_PROXY_MANUFACTURER) + "\">\n"
                + "    <p class=\"hint\">\"Fronius\" par defaut : le projet de reference sur lequel ce spike est base a "
                + "trouve que cette valeur donne la meilleure compatibilite avec Victron -- pas encore verifie de maniere "
                + "independante sur cette installation.</p>\n"
                + "    <label>Modele declare</label>\n"
                + "    <input type=\"text\" name=\"sunspec_proxy.model\" value=\"" + val(raw, "sunspec_proxy.model", ConfigLoader.Defaults.SUNSPEC_PROXY_MODEL) + "\">\n"
                + "    <label>Numero de serie declare</label>\n"
                + "    <input type=\"text\" name=\"sunspec_proxy.serial_number\" value=\"" + val(raw, "sunspec_proxy.serial_number", ConfigLoader.Defaults.SUNSPEC_PROXY_SERIAL_NUMBER) + "\">\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Journalisation (logging)</legend>\n"
                + "    <label><input type=\"checkbox\" name=\"logging.verbose_traces\"" + (verboseTraces ? " checked" : "") + "> Tracer l'etat complet a chaque cycle</label>\n"
                + "    <p class=\"hint\">Ligne \"grid_meter=... injection_control=...\" loggee a chaque cycle de decision. "
                + "Desactiver si le <a href=\"/dashboard\">tableau de bord</a> suffit.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <button type=\"submit\" formaction=\"/config/save\" class=\"primary\">Enregistrer</button>\n"
                + "  <button type=\"submit\" formaction=\"/config/apply\" class=\"primary apply-btn\"\n"
                + "          onclick=\"return confirm('Enregistrer et redemarrer le service maintenant ? Le pilotage sera brievement interrompu.');\">\n"
                + "    Enregistrer et appliquer (redemarre le service)\n"
                + "  </button>\n"
                + "  <button type=\"button\" onclick=\"resetTuningToDefaults()\">Reinitialiser les reglages de tuning</button>\n"
                + "  <p class=\"hint\">\"Enregistrer\" ecrit config.json sans redemarrer. \"Enregistrer et appliquer\" redemarre "
                + "le service tout de suite pour prendre en compte la nouvelle config. \"Reinitialiser les reglages de "
                + "tuning\" ne fait que pre-remplir le formulaire (PI, plancher batterie, sondage de capacite, hysterese "
                + "batterie, graphiques, journalisation, historique) avec les valeurs par defaut -- rien n'est ecrit avant "
                + "de cliquer sur Enregistrer. Ne touche pas aux identifiants OpenDTU, a la config Modbus, aux onduleurs, "
                + "au setpoint d'export ni a l'activation de la batterie.</p>\n"
                + "</form>\n"
                + "\n"
                + "<script>\n"
                + "function addInverterRow(serial, power, name) {\n"
                + "  const tbody = document.getElementById('inv-tbody');\n"
                + "  const tr = document.createElement('tr');\n"
                + "  tr.className = 'inv-row';\n"
                + "  tr.innerHTML = '<td><input type=\"text\" name=\"inverter_serial\" value=\"' + (serial || '') +\n"
                + "    '\" required></td>' +\n"
                + "    '<td><input type=\"text\" name=\"inverter_name\" value=\"' + (name || '') + '\" placeholder=\"(optionnel)\"></td>' +\n"
                + "    '<td><input type=\"number\" name=\"inverter_nominal_power_w\" value=\"' + (power || '') +\n"
                + "    '\" step=\"1\" min=\"1\" required></td>' +\n"
                + "    '<td><input type=\"hidden\" name=\"inverter_controllable\" value=\"true\">' +\n"
                + "    '<input type=\"checkbox\" checked onchange=\"this.previousElementSibling.value = " +
                        "this.checked ? \\'true\\' : \\'false\\'\"></td>' +\n"
                + "    '<td><button type=\"button\" class=\"remove-btn\" onclick=\"this.closest(\\'tr\\').remove()\">&times;</button></td>';\n"
                + "  tbody.appendChild(tr);\n"
                + "}\n"
                + "\n"
                + "function existingSerials() {\n"
                + "  return Array.from(document.querySelectorAll('input[name=\"inverter_serial\"]')).map(i => i.value.trim());\n"
                + "}\n"
                + "\n"
                + "function fetchInverters() {\n"
                + "  const baseUrl = document.querySelector('input[name=\"opendtu.base_url\"]').value.trim();\n"
                + "  const username = document.querySelector('input[name=\"opendtu.username\"]').value.trim();\n"
                + "  const password = document.querySelector('input[name=\"opendtu.password\"]').value;\n"
                + "  const status = document.getElementById('fetch-status');\n"
                + "  const list = document.getElementById('discovered-list');\n"
                + "  status.textContent = 'Chargement...';\n"
                + "  list.innerHTML = '';\n"
                + "  const params = new URLSearchParams({ base_url: baseUrl, username: username, password: password });\n"
                + "  fetch('/fetch-inverters?' + params.toString())\n"
                + "    .then(r => r.json())\n"
                + "    .then(data => {\n"
                + "      if (data.error) { status.textContent = 'Erreur: ' + data.error; return; }\n"
                + "      if (!data.inverters.length) { status.textContent = 'Aucun onduleur trouve sur cet OpenDTU.'; return; }\n"
                + "      status.textContent = data.inverters.length + ' onduleur(s) trouve(s) sur OpenDTU :';\n"
                + "      const known = existingSerials();\n"
                + "      data.inverters.forEach(inv => {\n"
                + "        const already = known.includes(inv.serial);\n"
                + "        const row = document.createElement('div');\n"
                + "        const cb = document.createElement('input');\n"
                + "        cb.type = 'checkbox';\n"
                + "        cb.checked = already;\n"
                + "        cb.disabled = already;\n"
                + "        cb.dataset.serial = inv.serial;\n"
                + "        cb.dataset.power = inv.max_power_w;\n"
                + "        cb.dataset.name = inv.name || '';\n"
                + "        cb.onchange = function() {\n"
                + "          if (this.checked && !existingSerials().includes(this.dataset.serial)) {\n"
                + "            addInverterRow(this.dataset.serial, this.dataset.power, this.dataset.name);\n"
                + "            this.disabled = true;\n"
                + "          }\n"
                + "        };\n"
                + "        const label = document.createElement('label');\n"
                + "        label.appendChild(cb);\n"
                + "        label.appendChild(document.createTextNode(\n"
                + "          ' ' + (inv.name || '(sans nom)') + ' (' + inv.serial + ') - ' + inv.max_power_w + ' W' +\n"
                + "          (already ? ' [deja gere]' : '')\n"
                + "        ));\n"
                + "        row.appendChild(label);\n"
                + "        list.appendChild(row);\n"
                + "      });\n"
                + "    })\n"
                + "    .catch(err => { status.textContent = 'Erreur: ' + err; });\n"
                + "}\n"
                + "\n"
                + "function resetTuningToDefaults() {\n"
                + "  if (!confirm('Pre-remplir les reglages de tuning avec leurs valeurs par defaut ? Les identifiants "
                + "OpenDTU, la config Modbus, les onduleurs, le setpoint d\\'export et l\\'activation de la batterie ne "
                + "sont pas touches. Rien n\\'est enregistre avant de cliquer sur Enregistrer.')) {\n"
                + "    return;\n"
                + "  }\n"
                + "  const defaults = {\n"
                + "    'grid.read_interval_s': " + d(ConfigLoader.Defaults.GRID_READ_INTERVAL_S) + ",\n"
                + "    'grid.ema_alpha': " + d(ConfigLoader.Defaults.GRID_EMA_ALPHA) + ",\n"
                + "    'control.kp': " + d(ConfigLoader.Defaults.CONTROL_KP) + ",\n"
                + "    'control.ki': " + d(ConfigLoader.Defaults.CONTROL_KI) + ",\n"
                + "    'control.decision_interval_s': " + d(ConfigLoader.Defaults.CONTROL_DECISION_INTERVAL_S) + ",\n"
                + "    'control.step_absolute_w': " + d(ConfigLoader.Defaults.CONTROL_STEP_ABSOLUTE_W) + ",\n"
                + "    'control.step_relative_pct': " + d(ConfigLoader.Defaults.CONTROL_STEP_RELATIVE_PCT) + ",\n"
                + "    'control.min_change_w': " + d(ConfigLoader.Defaults.CONTROL_MIN_CHANGE_W) + ",\n"
                + "    'control.min_inverter_pct': " + d(ConfigLoader.Defaults.CONTROL_MIN_INVERTER_PCT) + ",\n"
                + "    'control.min_battery_discharge_w': " + d(ConfigLoader.Defaults.CONTROL_MIN_BATTERY_DISCHARGE_W) + ",\n"
                + "    'capacity_probe.step_w': " + d(ConfigLoader.Defaults.CAPACITY_PROBE_STEP_W) + ",\n"
                + "    'capacity_probe.interval_s': " + d(ConfigLoader.Defaults.CAPACITY_PROBE_INTERVAL_S) + ",\n"
                + "    'battery.activate_at_pct': " + d(ConfigLoader.Defaults.BATTERY_ACTIVATE_AT_PCT) + ",\n"
                + "    'battery.deactivate_below_pct': " + d(ConfigLoader.Defaults.BATTERY_DEACTIVATE_BELOW_PCT) + ",\n"
                + "    'battery.export_confirms_full_w': " + d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_W) + ",\n"
                + "    'battery.export_confirms_full_duration_s': "
                + d(ConfigLoader.Defaults.BATTERY_EXPORT_CONFIRMS_FULL_DURATION_S) + ",\n"
                + "    'web.chart_height_px': " + ConfigLoader.Defaults.CHART_HEIGHT_PX + ",\n"
                + "    'stats.interval_s': " + d(ConfigLoader.Defaults.STATS_INTERVAL_S) + ",\n"
                + "    'stats.retention_days': " + ConfigLoader.Defaults.STATS_RETENTION_DAYS + ",\n"
                + "    'stats.high_res_retention_days': " + ConfigLoader.Defaults.STATS_HIGH_RES_RETENTION_DAYS + "\n"
                + "  };\n"
                + "  Object.entries(defaults).forEach(([fieldName, value]) => {\n"
                + "    const el = document.querySelector('[name=\"' + fieldName + '\"]');\n"
                + "    if (el) el.value = value;\n"
                + "  });\n"
                + "  const verboseTraces = document.querySelector('[name=\"logging.verbose_traces\"]');\n"
                + "  if (verboseTraces) verboseTraces.checked = " + ConfigLoader.Defaults.LOGGING_VERBOSE_TRACES + ";\n"
                + "}\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>\n";
    }
}
