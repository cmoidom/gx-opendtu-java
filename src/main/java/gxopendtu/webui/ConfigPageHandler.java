package gxopendtu.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.config.ConfigLoader;
import gxopendtu.state.HourlyEnergyHistory;
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
 * Built-in config editor: GET "/" and "/index.html" render the form, POST
 * "/save" writes config.json without restarting, POST "/apply" writes then
 * restarts the whole process (the supervisor -- systemd -- relaunches it
 * with the new config on the next load).
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
    private final StatsStore statsStore;

    ConfigPageHandler(Path configPath, LiveState liveState, HourlyEnergyHistory energyHistory, StatsStore statsStore) {
        this.configPath = configPath;
        this.liveState = liveState;
        this.energyHistory = energyHistory;
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
        if (!path.equals("/") && !path.equals("/index.html")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        sendHtml(exchange, 200, renderPage(loadRaw(configPath), "", ""));
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (!path.equals("/save") && !path.equals("/apply")) {
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
            sendHtml(exchange, 400, renderPage(raw, e.getMessage(), ""));
            return;
        }

        if (path.equals("/apply")) {
            sendHtml(exchange, 200, renderPage(raw, "", "Configuration enregistree, redemarrage du service en cours..."));
            LOG.warning(
                    "redemarrage demande via la page de configuration (bouton appliquer) -- "
                            + "le superviseur du service va le relancer");
            // Persist the latest known state immediately -- otherwise a
            // restart could lose up to one full stats.interval_s of
            // long-term history (see StatsStore/ControlLoop.run).
            statsStore.persistSnapshot(liveState, energyHistory);
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

        sendHtml(exchange, 200, renderPage(raw, "", "Configuration enregistree. Redemarrez le service pour l'appliquer."));
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
        ArrayNode inverters = MAPPER.createArrayNode();
        for (int i = 0; i < serials.size(); i++) {
            String serial = serials.get(i).trim();
            if (serial.isEmpty()) {
                continue;
            }
            String name = i < names.size() ? names.get(i).trim() : "";
            ObjectNode inv = MAPPER.createObjectNode();
            inv.put("serial", serial);
            inv.put("nominal_power_w", Double.parseDouble(powers.get(i)));
            if (name.isEmpty()) {
                inv.putNull("name");
            } else {
                inv.put("name", name);
            }
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
        grid.put("export_setpoint_w", Double.parseDouble(first(form, "grid.export_setpoint_w", "30")));
        grid.put("read_interval_s", Double.parseDouble(first(form, "grid.read_interval_s", "2")));
        grid.put("ema_alpha", Double.parseDouble(first(form, "grid.ema_alpha", "0.5")));
        ObjectNode modbus = MAPPER.createObjectNode();
        modbus.put("host", first(form, "grid.modbus.host", "").trim());
        modbus.put("port", (int) Double.parseDouble(first(form, "grid.modbus.port", "502")));
        modbus.put("unit_id", (int) Double.parseDouble(first(form, "grid.modbus.unit_id", "100")));
        grid.set("modbus", modbus);
        raw.set("grid", grid);

        ObjectNode control = MAPPER.createObjectNode();
        control.put("kp", Double.parseDouble(first(form, "control.kp", "0.4")));
        control.put("ki", Double.parseDouble(first(form, "control.ki", "0.05")));
        control.put("decision_interval_s", Double.parseDouble(first(form, "control.decision_interval_s", "5")));
        control.put("step_absolute_w", Double.parseDouble(first(form, "control.step_absolute_w", "100")));
        control.put("step_relative_pct", Double.parseDouble(first(form, "control.step_relative_pct", "10")));
        control.put("min_change_w", Double.parseDouble(first(form, "control.min_change_w", "5")));
        control.put("min_inverter_pct", Double.parseDouble(first(form, "control.min_inverter_pct", "5")));
        raw.set("control", control);

        ObjectNode probe = MAPPER.createObjectNode();
        probe.put("step_w", Double.parseDouble(first(form, "capacity_probe.step_w", "10")));
        probe.put("interval_s", Double.parseDouble(first(form, "capacity_probe.interval_s", "30")));
        raw.set("capacity_probe", probe);

        ObjectNode stats = MAPPER.createObjectNode();
        stats.put("interval_s", Double.parseDouble(first(form, "stats.interval_s", "300")));
        stats.put("retention_days", (int) Double.parseDouble(first(form, "stats.retention_days", "730")));
        raw.set("stats", stats);

        ObjectNode battery = MAPPER.createObjectNode();
        battery.put("enabled", form.containsKey("battery.enabled"));
        battery.put("activate_at_pct", Double.parseDouble(first(form, "battery.activate_at_pct", "100")));
        battery.put("deactivate_below_pct", Double.parseDouble(first(form, "battery.deactivate_below_pct", "98")));
        battery.put(
                "export_confirms_full_w", Double.parseDouble(first(form, "battery.export_confirms_full_w", "50")));
        raw.set("battery", battery);

        ObjectNode web = MAPPER.createObjectNode();
        web.put("port", (int) Double.parseDouble(first(form, "web.port", "8080")));
        raw.set("web", web);

        ObjectNode logging = MAPPER.createObjectNode();
        logging.put("verbose_traces", form.containsKey("logging.verbose_traces"));
        raw.set("logging", logging);

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
                    .append("<td><button type=\"button\" class=\"remove-btn\" "
                            + "onclick=\"this.closest('tr').remove()\">&times;</button></td></tr>\n");
        }
        return sb.toString();
    }

    private static String renderPage(JsonNode raw, String error, String message) {
        String banner = "";
        if (error != null && !error.isEmpty()) {
            banner = "<div class=\"banner error\">" + escape(error) + "</div>";
        } else if (message != null && !message.isEmpty()) {
            banner = "<div class=\"banner ok\">" + escape(message) + "</div>";
        }

        String invertersHtml = inverterRowsHtml(dig(raw, "inverters"));
        boolean batteryEnabled = dig(raw, "battery.enabled").asBoolean(false);
        boolean verboseTraces = dig(raw, "logging.verbose_traces").asBoolean(true);

        return "<!doctype html>\n"
                + "<html lang=\"fr\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>gx-opendtu-java - configuration</title>\n"
                + "<style>\n"
                + "  body { font-family: system-ui, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; color: #222; }\n"
                + "  h1 { font-size: 1.3rem; }\n"
                + "  fieldset { margin-bottom: 1.2rem; border: 1px solid #ccc; border-radius: 6px; }\n"
                + "  legend { font-weight: 600; padding: 0 0.4rem; }\n"
                + "  label { display: block; margin: 0.5rem 0 0.15rem; font-size: 0.9rem; }\n"
                + "  input[type=text], input[type=number], input[type=password] { width: 100%; padding: 0.35rem; box-sizing: border-box; }\n"
                + "  input[type=checkbox] { margin-right: 0.4rem; }\n"
                + "  table { width: 100%; border-collapse: collapse; }\n"
                + "  td { padding: 0.25rem; }\n"
                + "  .remove-btn { color: #b00; border: none; background: none; font-size: 1.2rem; cursor: pointer; }\n"
                + "  .banner { padding: 0.6rem 1rem; border-radius: 6px; margin-bottom: 1rem; }\n"
                + "  .banner.error { background: #fde2e2; color: #7a1212; }\n"
                + "  .banner.ok { background: #e2f6e2; color: #1a5c1a; }\n"
                + "  button.primary { padding: 0.6rem 1.2rem; background: #2563eb; color: white; border: none;\n"
                + "                    border-radius: 6px; cursor: pointer; font-size: 1rem; margin-right: 0.5rem; }\n"
                + "  button.apply-btn { background: #b45309; }\n"
                + "  #add-inv-btn { margin-top: 0.5rem; }\n"
                + "  .hint { color: #666; font-size: 0.82rem; margin: 0.2rem 0 0; }\n"
                + "  nav { margin-bottom: 1rem; font-size: 0.9rem; }\n"
                + "  nav a { color: #2563eb; text-decoration: none; }\n"
                + "  nav a:hover { text-decoration: underline; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<nav><a href=\"/\">Configuration</a> &middot; <a href=\"/dashboard\">Tableau de bord</a></nav>\n"
                + "<h1>gx-opendtu-java - configuration</h1>\n"
                + banner
                + "<form method=\"post\" action=\"/save\">\n"
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
                + "    <input type=\"number\" step=\"any\" name=\"grid.export_setpoint_w\" value=\"" + val(raw, "grid.export_setpoint_w", "30") + "\" required>\n"
                + "    <label>Intervalle de lecture (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"grid.read_interval_s\" value=\"" + val(raw, "grid.read_interval_s", "2") + "\" required>\n"
                + "    <label>Coefficient EMA (0-1)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" max=\"1\" name=\"grid.ema_alpha\" value=\"" + val(raw, "grid.ema_alpha", "0.5") + "\" required>\n"
                + "    <label>Hote Modbus (IP Cerbo GX)</label>\n"
                + "    <input type=\"text\" name=\"grid.modbus.host\" value=\"" + val(raw, "grid.modbus.host", "") + "\" required>\n"
                + "    <label>Port Modbus</label>\n"
                + "    <input type=\"number\" name=\"grid.modbus.port\" value=\"" + val(raw, "grid.modbus.port", "502") + "\">\n"
                + "    <label>Unit ID</label>\n"
                + "    <input type=\"number\" name=\"grid.modbus.unit_id\" value=\"" + val(raw, "grid.modbus.unit_id", "100") + "\">\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Asservissement (control)</legend>\n"
                + "    <label>kp</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.kp\" value=\"" + val(raw, "control.kp", "0.4") + "\" required>\n"
                + "    <label>ki</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.ki\" value=\"" + val(raw, "control.ki", "0.05") + "\" required>\n"
                + "    <label>Intervalle de decision (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.decision_interval_s\" value=\"" + val(raw, "control.decision_interval_s", "5") + "\" required>\n"
                + "    <label>Palier absolu (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.step_absolute_w\" value=\"" + val(raw, "control.step_absolute_w", "100") + "\" required>\n"
                + "    <label>Palier relatif (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.step_relative_pct\" value=\"" + val(raw, "control.step_relative_pct", "10") + "\" required>\n"
                + "    <label>Changement minimal (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"control.min_change_w\" value=\"" + val(raw, "control.min_change_w", "5") + "\" required>\n"
                + "    <label>Seuil mini onduleur (% de sa puissance nominale)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" max=\"100\" name=\"control.min_inverter_pct\" value=\"" + val(raw, "control.min_inverter_pct", "5") + "\" required>\n"
                + "    <p class=\"hint\">Un onduleur qui produit n'est jamais commande sous ce seuil. Mettre 0 pour desactiver. "
                + "Un arret complet (fail-safe, charge batterie) n'est jamais concerne.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Sonde de capacite (capacity_probe)</legend>\n"
                + "    <label>Palier de sonde (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"capacity_probe.step_w\" value=\"" + val(raw, "capacity_probe.step_w", "10") + "\" required>\n"
                + "    <label>Intervalle de sonde (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"capacity_probe.interval_s\" value=\"" + val(raw, "capacity_probe.interval_s", "30") + "\" required>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Statistiques long terme (stats)</legend>\n"
                + "    <label>Intervalle d'ecriture (s)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"1\" name=\"stats.interval_s\" value=\"" + val(raw, "stats.interval_s", "300") + "\" required>\n"
                + "    <label>Retention (jours)</label>\n"
                + "    <input type=\"number\" step=\"1\" min=\"1\" name=\"stats.retention_days\" value=\"" + val(raw, "stats.retention_days", "730") + "\" required>\n"
                + "    <p class=\"hint\">Courbes (reseau, SOC, batterie, par onduleur) et energie horaire persistees dans "
                + "stats.db pour l'historique long terme, independamment du tableau de bord temps reel (~30min/48h en memoire). "
                + "Ecrit a cet intervalle, plus immediatement a chaque \"Enregistrer et appliquer\". Les donnees plus "
                + "vieilles que la retention sont purgees automatiquement.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Batterie (priorite charge)</legend>\n"
                + "    <label><input type=\"checkbox\" name=\"battery.enabled\"" + (batteryEnabled ? " checked" : "") + "> Activer</label>\n"
                + "    <label>Seuil d'activation SOC (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"battery.activate_at_pct\" value=\"" + val(raw, "battery.activate_at_pct", "100") + "\" required>\n"
                + "    <label>Seuil de desactivation SOC (%)</label>\n"
                + "    <input type=\"number\" step=\"any\" name=\"battery.deactivate_below_pct\" value=\"" + val(raw, "battery.deactivate_below_pct", "98") + "\" required>\n"
                + "    <label>Export confirmant la batterie pleine (W)</label>\n"
                + "    <input type=\"number\" step=\"any\" min=\"0\" name=\"battery.export_confirms_full_w\" value=\"" + val(raw, "battery.export_confirms_full_w", "50") + "\" required>\n"
                + "    <p class=\"hint\">Passe en regulation ON des qu'un export reseau reel d'au moins cette puissance "
                + "est observe alors que le SOC est deja au-dessus du seuil de desactivation. Mettre 0 pour desactiver.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Onduleurs</legend>\n"
                + "    <table id=\"inv-table\">\n"
                + "      <thead><tr><th>Serie</th><th>Nom</th><th>Puissance nominale (W)</th><th></th></tr></thead>\n"
                + "      <tbody id=\"inv-tbody\">\n"
                + invertersHtml
                + "      </tbody>\n"
                + "    </table>\n"
                + "    <button type=\"button\" id=\"add-inv-btn\" onclick=\"addInverterRow()\">+ Ajouter un onduleur (manuel)</button>\n"
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
                + "    <input type=\"number\" name=\"web.port\" value=\"" + val(raw, "web.port", "8080") + "\" required>\n"
                + "    <p class=\"hint\">Necessite un redemarrage du service pour prendre effet.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <fieldset>\n"
                + "    <legend>Journalisation (logging)</legend>\n"
                + "    <label><input type=\"checkbox\" name=\"logging.verbose_traces\"" + (verboseTraces ? " checked" : "") + "> Tracer l'etat complet a chaque cycle</label>\n"
                + "    <p class=\"hint\">Ligne \"grid_meter=... injection_control=...\" loggee a chaque cycle de decision. "
                + "Desactiver si le <a href=\"/dashboard\">tableau de bord</a> suffit.</p>\n"
                + "  </fieldset>\n"
                + "\n"
                + "  <button type=\"submit\" formaction=\"/save\" class=\"primary\">Enregistrer</button>\n"
                + "  <button type=\"submit\" formaction=\"/apply\" class=\"primary apply-btn\"\n"
                + "          onclick=\"return confirm('Enregistrer et redemarrer le service maintenant ? Le pilotage sera brievement interrompu.');\">\n"
                + "    Enregistrer et appliquer (redemarre le service)\n"
                + "  </button>\n"
                + "  <p class=\"hint\">\"Enregistrer\" ecrit config.json sans redemarrer. \"Enregistrer et appliquer\" redemarre "
                + "le service tout de suite pour prendre en compte la nouvelle config.</p>\n"
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
                + "</script>\n"
                + "</body>\n"
                + "</html>\n";
    }
}
