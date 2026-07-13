package gxopendtu.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.BuildInfo;
import gxopendtu.config.ConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the live dashboard: a static resource (webui/dashboard.html, copied
 * near-verbatim from the Python reference project's _render_dashboard_page,
 * which is already 100% client-side -- it polls /status.json and draws
 * hand-rolled &lt;canvas&gt; charts) -- with two small server-side
 * substitutions: {@code __CHART_HEIGHT_PX__} in the page's CSS is replaced
 * with the current config.web.chart_height_px on every request, read fresh
 * from disk (like webui.ConfigPageHandler's own loadRaw) so a value saved
 * via "Enregistrer" applies on the next page load without a service restart;
 * {@code __BUILD_TAG__} is replaced with the same build timestamp shown on
 * the config page (see BuildInfo), or nothing if unavailable.
 */
final class DashboardHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMPLATE = loadTemplate();

    private final Path configPath;

    DashboardHandler(Path configPath) {
        this.configPath = configPath;
    }

    private static String loadTemplate() {
        try (InputStream in = DashboardHandler.class.getResourceAsStream("/webui/dashboard.html")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /webui/dashboard.html");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int chartHeightPx() {
        try {
            JsonNode raw = MAPPER.readTree(Files.readString(configPath));
            int value = raw.path("web").path("chart_height_px").asInt(ConfigLoader.Defaults.CHART_HEIGHT_PX);
            if (value < ConfigLoader.Defaults.CHART_HEIGHT_PX_MIN || value > ConfigLoader.Defaults.CHART_HEIGHT_PX_MAX) {
                return ConfigLoader.Defaults.CHART_HEIGHT_PX;
            }
            return value;
        } catch (IOException | RuntimeException e) {
            // A transient read/parse hiccup must never break the dashboard --
            // fall back to the default height rather than failing the request.
            return ConfigLoader.Defaults.CHART_HEIGHT_PX;
        }
    }

    private static String buildTag() {
        String timestamp = BuildInfo.timestamp();
        return timestamp != null ? " <span class=\"build-tag\">(build " + timestamp + ")</span>" : "";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = TEMPLATE.replace("__CHART_HEIGHT_PX__", String.valueOf(chartHeightPx()))
                    .replace("__BUILD_TAG__", buildTag())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
