package gxopendtu.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.stats.StatsStore;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /hourly-energy.json?since=&lt;epoch&gt;&amp;until=&lt;epoch&gt; --
 * serves the two hourly bar charts (grid energy, per-inverter energy) for an
 * arbitrary past day straight from stats.db's multi-year retention, for the
 * dashboard's per-chart day picker. Complements /status.json (which only
 * ever reflects the live ~48h rolling window) rather than replacing it.
 */
final class HourlyEnergyJsonHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StatsStore statsStore;

    HourlyEnergyJsonHandler(StatsStore statsStore) {
        this.statsStore = statsStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
            Double since = parseDouble(query.get("since"));
            Double until = parseDouble(query.get("until"));
            boolean validRange = since != null && until != null && until > since;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "hourly_energy",
                    validRange ? statsStore.loadHourlyEnergyBetween(since, until) : List.of());
            payload.put(
                    "hourly_inverter_energy",
                    validRange ? statsStore.loadInverterHourlyEnergyBetween(since, until) : List.of());

            byte[] body = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }
}
