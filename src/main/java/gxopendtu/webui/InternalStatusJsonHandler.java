package gxopendtu.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.state.InternalStatus;
import gxopendtu.sunspec.SunSpecProxyState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /internal-status.json?since=&lt;epoch&gt; -- same incremental-fetch
 * shape as StatusJsonHandler, polled by the /internal debug page every 5s.
 */
final class InternalStatusJsonHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InternalStatus internalStatus;
    private final SunSpecProxyState sunSpecProxyState;

    InternalStatusJsonHandler(InternalStatus internalStatus, SunSpecProxyState sunSpecProxyState) {
        this.internalStatus = internalStatus;
        this.sunSpecProxyState = sunSpecProxyState;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            double since = parseSince(exchange.getRequestURI().getQuery());
            InternalStatus.Snapshot snapshot = internalStatus.snapshotSince(since);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("latest", snapshot.latest());
            payload.put("history", snapshot.history());
            payload.put("sunspec_proxy", sunSpecProxyState == null ? null : sunSpecProxyState.snapshot());

            byte[] body = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static double parseSince(String query) {
        if (query == null) {
            return 0.0;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.startsWith("since=")) {
                try {
                    return Double.parseDouble(pair.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
        }
        return 0.0;
    }
}
