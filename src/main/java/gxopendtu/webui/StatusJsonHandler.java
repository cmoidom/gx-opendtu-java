package gxopendtu.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /status.json?since=&lt;epoch&gt; -- incremental fetch (full history if
 * since is omitted/0, only newer samples otherwise), polled by the dashboard
 * every 2s. Port of the /status.json half of src/webui.py's ConfigHandler.
 */
final class StatusJsonHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LiveState liveState;
    private final HourlyEnergyHistory energyHistory;
    private final ManualOverride manualOverride;
    private final InjectionModeOverride injectionMode;

    StatusJsonHandler(
            LiveState liveState,
            HourlyEnergyHistory energyHistory,
            ManualOverride manualOverride,
            InjectionModeOverride injectionMode) {
        this.liveState = liveState;
        this.energyHistory = energyHistory;
        this.manualOverride = manualOverride;
        this.injectionMode = injectionMode;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            double since = parseSince(exchange.getRequestURI().getQuery());
            LiveState.Snapshot snapshot = liveState.snapshotSince(since);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("latest", snapshot.latest());
            payload.put("history", snapshot.history());
            payload.put("hourly_energy", energyHistory.snapshot());
            payload.put("manual_override", manualOverride.snapshot());
            payload.put("injection_mode", injectionMode.getMode().name());

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
