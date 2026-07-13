package gxopendtu.opendtu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the OpenDTU REST API. Port of src/opendtu_client.py, using
 * java.net.http.HttpClient (JDK, no external dependency) instead of Python's
 * urllib -- that choice was Venus OS-specific (minimize deps on a flash-
 * constrained device), which no longer applies to this VM-only Java port.
 */
public final class OpenDTUClient implements OpenDTUApi {

    /**
     * limit_type values, see OpenDTU's ActivePowerControlCommand.h. Persistent
     * variants write to inverter flash and are deliberately not exposed here --
     * this client only ever sends non-persistent limits.
     */
    public static final int LIMIT_TYPE_ABSOLUTE_NONPERSISTENT = 0;

    public static final int LIMIT_TYPE_RELATIVE_NONPERSISTENT = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final String authHeader;

    public OpenDTUClient(String baseUrl, double timeoutS, String username, String password) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(Math.round(timeoutS * 1000));
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        // OpenDTU's write endpoints (/api/limit/config) require HTTP Basic Auth
        // by default even when the read-only API isn't protected -- sent on
        // every request regardless of path, since OpenDTU simply ignores it on
        // endpoints that don't require it.
        if (username != null && !username.isEmpty()) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + (password != null ? password : ""))
                            .getBytes(StandardCharsets.UTF_8));
            this.authHeader = "Basic " + token;
        } else {
            this.authHeader = null;
        }
    }

    public OpenDTUClient(String baseUrl, String username, String password) {
        this(baseUrl, 5.0, username, password);
    }

    public OpenDTUClient(String baseUrl) {
        this(baseUrl, 5.0, null, null);
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(timeout);
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return builder;
    }

    private JsonNode get(String path) {
        try {
            HttpRequest request = requestBuilder(path).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new OpenDTUException("GET " + baseUrl + path + " failed: HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new OpenDTUException("GET " + baseUrl + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenDTUException("GET " + baseUrl + path + " interrupted", e);
        }
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            String form = "data=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
            HttpRequest request = requestBuilder(path)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new OpenDTUException("POST " + baseUrl + path + " failed: HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new OpenDTUException("POST " + baseUrl + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenDTUException("POST " + baseUrl + path + " interrupted", e);
        }
    }

    /**
     * Returns {serial: current_ac_power_w} for the given inverters.
     *
     * The bare GET /api/livedata/status (no query string) deliberately omits
     * per-inverter AC/DC channel data -- it only ever returns
     * serial/name/reachability/limit summaries plus a system-wide total,
     * confirmed against OpenDTU's own docs and a live install (a serial
     * genuinely producing 700+W read back as 0 through the bare endpoint).
     * Only /api/livedata/status?inv=<serial> includes the "AC" breakdown this
     * needs, hence one request per inverter here.
     */
    @Override
    public Map<String, Double> getLivePowerW(Collection<String> serials) {
        Map<String, Double> result = new HashMap<>();
        for (String serial : serials) {
            JsonNode data = get("/api/livedata/status?inv=" + URLEncoder.encode(serial, StandardCharsets.UTF_8));
            for (JsonNode inv : data.path("inverters")) {
                if (!serial.equals(inv.path("serial").asText())) {
                    continue;
                }
                JsonNode ac = inv.path("AC");
                JsonNode channel0 = ac.has("0") ? ac.path("0") : ac;
                double power = channel0.isObject() ? JsonValues.extractValue(channel0.path("Power")) : 0.0;
                result.put(serial, power);
            }
        }
        return result;
    }

    /**
     * Returns {serial: today's cumulative AC yield in Wh} for the given
     * inverters -- read from the same per-inverter endpoint getLivePowerW
     * uses (INV.0.YieldDay), one request per inverter for the same reason
     * documented there. Confirmed against a live install: INV.0.YieldDay is
     * already the whole-inverter total (sum of every DC/MPPT channel's own
     * YieldDay), not one channel's share, so no further combination is
     * needed here.
     */
    @Override
    public Map<String, Double> getYieldDayWh(Collection<String> serials) {
        Map<String, Double> result = new HashMap<>();
        for (String serial : serials) {
            JsonNode data = get("/api/livedata/status?inv=" + URLEncoder.encode(serial, StandardCharsets.UTF_8));
            for (JsonNode inv : data.path("inverters")) {
                if (!serial.equals(inv.path("serial").asText())) {
                    continue;
                }
                result.put(serial, JsonValues.extractValue(inv.path("INV").path("0").path("YieldDay")));
            }
        }
        return result;
    }

    /**
     * All inverters OpenDTU currently knows about, with their rated power --
     * used by the config web UI to let a user pick which ones to manage
     * instead of typing serial/power by hand.
     *
     * There is no dedicated "/api/inverter/list" endpoint in OpenDTU:
     * serial/name come from /api/livedata/status, rated power (max_power)
     * from /api/limit/status.
     */
    public List<InverterInfo> listInverters() {
        JsonNode livedata = get("/api/livedata/status");
        Map<String, LimitStatus> limitStatus = getLimitStatus();
        List<InverterInfo> result = new ArrayList<>();
        for (JsonNode inv : livedata.path("inverters")) {
            String serial = inv.path("serial").asText();
            LimitStatus status = limitStatus.get(serial);
            result.add(new InverterInfo(serial, inv.path("name").asText(""), status != null ? status.maxPower() : 0.0));
        }
        return result;
    }

    @Override
    public Map<String, LimitStatus> getLimitStatus() {
        JsonNode data = get("/api/limit/status");
        Map<String, LimitStatus> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode status = entry.getValue();
            result.put(
                    entry.getKey(),
                    new LimitStatus(
                            status.path("limit_relative").asDouble(0.0),
                            status.path("max_power").asDouble(0.0),
                            status.path("limit_set_status").asText("Unknown")));
        }
        return result;
    }

    @Override
    public void setAbsoluteLimitW(String serial, double watts) {
        post(
                "/api/limit/config",
                Map.of("serial", serial, "limit_type", LIMIT_TYPE_ABSOLUTE_NONPERSISTENT, "limit_value", Math.round(watts)));
    }

    @Override
    public void setRelativeLimitPct(String serial, double percent) {
        post(
                "/api/limit/config",
                Map.of("serial", serial, "limit_type", LIMIT_TYPE_RELATIVE_NONPERSISTENT, "limit_value", Math.round(percent)));
    }
}
