package gxopendtu.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gxopendtu.BuildInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the internal-state debug page: a static resource
 * (webui/internal.html), with the same {@code __BUILD_TAG__} substitution as
 * DashboardHandler. No config-driven substitution needed here (unlike the
 * dashboard's chart height) -- this is a plain debug table, not a
 * configurable chart.
 */
final class InternalPageHandler implements HttpHandler {

    private static final String TEMPLATE = loadTemplate();

    private static String loadTemplate() {
        try (InputStream in = InternalPageHandler.class.getResourceAsStream("/webui/internal.html")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /webui/internal.html");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            byte[] body = TEMPLATE.replace("__BUILD_TAG__", buildTag()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
