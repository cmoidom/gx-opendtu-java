package gxopendtu.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Serves the live dashboard: a static resource (webui/dashboard.html, copied
 * near-verbatim from the Python reference project's _render_dashboard_page,
 * which is already 100% client-side -- it polls /status.json and draws
 * hand-rolled &lt;canvas&gt; charts, no server-side templating needed).
 */
final class DashboardHandler implements HttpHandler {

    private static final byte[] PAGE = loadPage();

    private static byte[] loadPage() {
        try (InputStream in = DashboardHandler.class.getResourceAsStream("/webui/dashboard.html")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /webui/dashboard.html");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, PAGE.length);
            exchange.getResponseBody().write(PAGE);
        } finally {
            exchange.close();
        }
    }
}
