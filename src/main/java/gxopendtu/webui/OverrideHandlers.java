package gxopendtu.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.ManualOverride;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The dashboard's manual controls: POST /override/pct (force 25/50/75/100%
 * for 5 minutes), /override/pct/clear (cancel early), /override/mode
 * (sticky AUTO/ON/OFF). Port of the corresponding handlers in src/webui.py.
 */
final class OverrideHandlers {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ManualOverride manualOverride;
    private final InjectionModeOverride injectionMode;

    OverrideHandlers(ManualOverride manualOverride, InjectionModeOverride injectionMode) {
        this.manualOverride = manualOverride;
        this.injectionMode = injectionMode;
    }

    HttpHandler pctHandler() {
        return exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                Map<String, String> form = parseForm(readBody(exchange));
                double pct;
                try {
                    pct = Double.parseDouble(form.getOrDefault("pct", "0"));
                } catch (NumberFormatException e) {
                    sendJson(exchange, 400, Map.of("error", "pct invalide"));
                    return;
                }
                if (pct != 25.0 && pct != 50.0 && pct != 75.0 && pct != 100.0) {
                    sendJson(exchange, 400, Map.of("error", "pct doit etre 25, 50, 75 ou 100"));
                    return;
                }
                manualOverride.set(pct);
                LOG.warning(String.format(
                        "forcage manuel demande via la page de config: %.0f%% pendant %d min",
                        pct, (long) (ManualOverride.DEFAULT_DURATION_S / 60)));
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", true);
                response.put("override", manualOverride.snapshot());
                sendJson(exchange, 200, response);
            } finally {
                exchange.close();
            }
        };
    }

    HttpHandler pctClearHandler() {
        return exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                manualOverride.clear();
                sendJson(exchange, 200, Map.of("ok", true));
            } finally {
                exchange.close();
            }
        };
    }

    HttpHandler modeHandler() {
        return exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                Map<String, String> form = parseForm(readBody(exchange));
                String mode = form.getOrDefault("mode", "AUTO");
                try {
                    injectionMode.setMode(mode);
                } catch (IllegalArgumentException e) {
                    sendJson(exchange, 400, Map.of("error", "mode invalide"));
                    return;
                }
                LOG.warning("mode de regulation change via la page de config: " + mode);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", true);
                response.put("mode", injectionMode.getMode().name());
                sendJson(exchange, 200, response);
            } finally {
                exchange.close();
            }
        };
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
