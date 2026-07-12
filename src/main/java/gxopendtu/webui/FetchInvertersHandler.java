package gxopendtu.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.opendtu.InverterInfo;
import gxopendtu.opendtu.OpenDTUClient;
import gxopendtu.opendtu.OpenDTUException;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /fetch-inverters?base_url=&amp;username=&amp;password= -- server-side
 * proxy to OpenDTU's discovery (no dedicated OpenDTU "list inverters"
 * endpoint exists, and calling straight from the browser would hit CORS).
 * Port of src/webui.py's _handle_fetch_inverters.
 */
final class FetchInvertersHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
            String baseUrl = query.getOrDefault("base_url", "").trim();
            if (baseUrl.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "URL OpenDTU manquante"));
                return;
            }
            String username = query.get("username");
            username = (username != null && !username.trim().isEmpty()) ? username.trim() : null;
            String password = query.get("password");
            password = (password != null && !password.isEmpty()) ? password : null;

            OpenDTUClient client = new OpenDTUClient(baseUrl, 5.0, username, password);
            List<InverterInfo> inverters;
            try {
                inverters = client.listInverters();
            } catch (OpenDTUException e) {
                sendJson(exchange, 502, Map.of("error", e.getMessage()));
                return;
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (InverterInfo inv : inverters) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("serial", inv.serial());
                entry.put("name", inv.name());
                entry.put("max_power_w", inv.maxPowerW());
                result.add(entry);
            }
            sendJson(exchange, 200, Map.of("inverters", result));
        } finally {
            exchange.close();
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

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
