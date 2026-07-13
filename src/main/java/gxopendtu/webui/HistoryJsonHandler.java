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
 * GET /history.json?since=&lt;epoch&gt;&amp;until=&lt;epoch&gt; -- serves
 * older dashboard samples straight from stats.db's multi-year retention, for
 * panning/zooming further back than {@code LiveState}'s ~30 min in-memory
 * window. Complements /status.json (the live, incrementally-polled feed)
 * rather than replacing it -- see dashboard.html's maybeLoadOlderHistory.
 */
final class HistoryJsonHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StatsStore statsStore;

    HistoryJsonHandler(StatsStore statsStore) {
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
            List<Map<String, Object>> history = (since != null && until != null && until > since)
                    ? statsStore.loadSamplesBetween(since, until)
                    : List.of();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("history", history);

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
