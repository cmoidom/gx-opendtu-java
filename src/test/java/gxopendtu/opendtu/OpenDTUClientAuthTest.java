package gxopendtu.opendtu;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDTUClientAuthTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServerCapturingAuthHeader(AtomicReference<String> captured, String responseBody)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void getSendsBasicAuthHeaderWhenCredentialsSet() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServerCapturingAuthHeader(captured, "{\"inverters\": []}");
        new OpenDTUClient(baseUrl, "admin", "secret").getLivePowerW(List.of("123"));
        assertThat(captured.get()).isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    @Test
    void getSendsNoAuthHeaderByDefault() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServerCapturingAuthHeader(captured, "{\"inverters\": []}");
        new OpenDTUClient(baseUrl).getLivePowerW(List.of("123"));
        assertThat(captured.get()).isNull();
    }

    @Test
    void postAlsoSendsBasicAuthHeader() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServerCapturingAuthHeader(captured, "{}");
        new OpenDTUClient(baseUrl, "admin", "secret").setRelativeLimitPct("123", 50);
        assertThat(captured.get()).isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    @Test
    void usernameWithoutPasswordStillAuthenticates() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServerCapturingAuthHeader(captured, "{\"inverters\": []}");
        new OpenDTUClient(baseUrl, "admin", null).getLivePowerW(List.of("123"));
        assertThat(captured.get()).isEqualTo("Basic YWRtaW46");
    }
}
