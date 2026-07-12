package gxopendtu.opendtu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDTUClientTest {

    private HttpServer server;
    private final List<String> requestedPaths = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().toString());
            handler.handle(exchange);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respondJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void getLivePowerWIssuesOneRequestPerSerialWithInvQueryParam() throws IOException {
        String baseUrl = startServer((exchange) -> {
            String query = exchange.getRequestURI().getQuery();
            String serial = query.substring(query.indexOf('=') + 1);
            String json = "{\"inverters\": [{\"serial\": \"" + serial + "\", \"AC\": {\"0\": {\"Power\": {\"v\": 321.5}}}}]}";
            respondJson(exchange, json);
        });

        Map<String, Double> result = new OpenDTUClient(baseUrl).getLivePowerW(List.of("111", "222"));

        assertThat(requestedPaths).containsExactlyInAnyOrder(
                "/api/livedata/status?inv=111", "/api/livedata/status?inv=222");
        assertThat(result).containsEntry("111", 321.5).containsEntry("222", 321.5);
    }

    @Test
    void getLivePowerWHandlesBareNumberPowerNode() throws IOException {
        String baseUrl = startServer((exchange) ->
                respondJson(exchange, "{\"inverters\": [{\"serial\": \"111\", \"AC\": {\"0\": {\"Power\": 42}}}]}"));

        Map<String, Double> result = new OpenDTUClient(baseUrl).getLivePowerW(List.of("111"));

        assertThat(result).containsEntry("111", 42.0);
    }

    @Test
    void getLivePowerWDefaultsToZeroWhenAcMissing() throws IOException {
        String baseUrl = startServer((exchange) -> respondJson(exchange, "{\"inverters\": [{\"serial\": \"111\"}]}"));

        Map<String, Double> result = new OpenDTUClient(baseUrl).getLivePowerW(List.of("111"));

        assertThat(result).containsEntry("111", 0.0);
    }

    @Test
    void getLimitStatusParsesAcknowledgedFromLimitSetStatus() throws IOException {
        String baseUrl = startServer((exchange) -> respondJson(
                exchange,
                "{\"111\": {\"limit_relative\": 50, \"max_power\": 600, \"limit_set_status\": \"Ok\"},"
                        + "\"222\": {\"limit_relative\": 80, \"max_power\": 380, \"limit_set_status\": \"Pending\"}}"));

        Map<String, LimitStatus> status = new OpenDTUClient(baseUrl).getLimitStatus();

        assertThat(status.get("111").acknowledged()).isTrue();
        assertThat(status.get("222").acknowledged()).isFalse();
        assertThat(status.get("111").maxPower()).isEqualTo(600.0);
    }

    @Test
    void listInvertersCombinesLivedataAndLimitStatus() throws IOException {
        String baseUrl = startServer((exchange) -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/livedata/status")) {
                respondJson(exchange, "{\"inverters\": [{\"serial\": \"111\", \"name\": \"Toit Sud\"}]}");
            } else {
                respondJson(exchange, "{\"111\": {\"limit_relative\": 100, \"max_power\": 600, \"limit_set_status\": \"Ok\"}}");
            }
        });

        List<InverterInfo> inverters = new OpenDTUClient(baseUrl).listInverters();

        assertThat(inverters).hasSize(1);
        assertThat(inverters.get(0).serial()).isEqualTo("111");
        assertThat(inverters.get(0).name()).isEqualTo("Toit Sud");
        assertThat(inverters.get(0).maxPowerW()).isEqualTo(600.0);
    }

    @Test
    void setAbsoluteLimitWSendsNonPersistentLimitType() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String baseUrl = startServer((exchange) -> {
            capturedBody.set(readBody(exchange));
            respondJson(exchange, "{}");
        });

        new OpenDTUClient(baseUrl).setAbsoluteLimitW("111", 250);

        String decoded = URLDecoder.decode(capturedBody.get(), StandardCharsets.UTF_8);
        assertThat(decoded).contains("\"limit_type\":0").contains("\"limit_value\":250").contains("\"serial\":\"111\"");
    }

    @Test
    void setRelativeLimitPctSendsNonPersistentLimitType() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String baseUrl = startServer((exchange) -> {
            capturedBody.set(readBody(exchange));
            respondJson(exchange, "{}");
        });

        new OpenDTUClient(baseUrl).setRelativeLimitPct("111", 50);

        String decoded = URLDecoder.decode(capturedBody.get(), StandardCharsets.UTF_8);
        assertThat(decoded).contains("\"limit_type\":1").contains("\"limit_value\":50");
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
