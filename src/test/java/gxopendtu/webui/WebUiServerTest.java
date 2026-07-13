package gxopendtu.webui;

import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.state.StateStore;
import gxopendtu.stats.StatsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebUiServerTest {

    private WebUiServer server;
    private StatsStore statsStore;
    private HttpClient http;
    private String baseUrl;
    private Path configPath;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws IOException {
        configPath = tmpDir.resolve("config.json");
        Files.writeString(
                configPath,
                """
                {
                  "opendtu": { "base_url": "http://192.168.1.50" },
                  "grid": { "modbus": { "host": "192.168.1.10" } },
                  "inverters": [ { "serial": "111", "nominal_power_w": 600 } ]
                }
                """);
        statsStore = new StatsStore(tmpDir.resolve("stats.db"));
        server = WebUiServer.start(
                configPath,
                0,
                new LiveState(),
                new HourlyEnergyHistory(),
                new InverterEnergyHistory(),
                new ManualOverride(),
                new InjectionModeOverride(),
                statsStore);
        http = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        statsStore.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void configPageServesForm() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("GX-DTU Injection Controller - configuration");
        assertThat(response.body()).contains("192.168.1.50");
        assertThat(response.body()).contains("name=\"stats.high_res_retention_days\" value=\"30\"");
    }

    @Test
    void savingStatsHighResRetentionDaysRoundTripsAcrossSaves() throws Exception {
        HttpResponse<String> saveResponse = post(
                "/save",
                "opendtu.base_url=http://192.168.1.50&grid.modbus.host=192.168.1.10"
                        + "&stats.high_res_retention_days=7&inverter_serial=111&inverter_nominal_power_w=600&inverter_name=");
        assertThat(saveResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> reloaded = get("/");
        assertThat(reloaded.body()).contains("name=\"stats.high_res_retention_days\" value=\"7\"");
    }

    @Test
    void configPageShowsStatsDbSizeAndRowCount() throws Exception {
        LiveState liveState = new LiveState();
        liveState.recordGrid(10.0, 9.0);
        statsStore.persistSnapshot(liveState, new HourlyEnergyHistory(), new InverterEnergyHistory());

        HttpResponse<String> response = get("/");

        assertThat(response.body()).contains("stats.db : ");
        assertThat(response.body()).contains("1 lignes");
    }

    @Test
    void unknownPathReturns404() throws Exception {
        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void dashboardServesStaticPage() throws Exception {
        HttpResponse<String> response = get("/dashboard");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Tableau de bord");
    }

    @Test
    void dashboardUsesDefaultChartHeightWhenNotConfigured() throws Exception {
        HttpResponse<String> response = get("/dashboard");
        assertThat(response.body()).contains("height: 200px");
    }

    @Test
    void dashboardReflectsSavedChartHeightWithoutRestart() throws Exception {
        HttpResponse<String> saveResponse = post(
                "/save",
                "opendtu.base_url=http://192.168.1.50&grid.modbus.host=192.168.1.10"
                        + "&web.chart_height_px=350&inverter_serial=111&inverter_nominal_power_w=600&inverter_name=");
        assertThat(saveResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> response = get("/dashboard");
        assertThat(response.body()).contains("height: 350px");
    }

    @Test
    void statusJsonReturnsExpectedShape() throws Exception {
        HttpResponse<String> response = get("/status.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"history\"")
                .contains("\"hourly_energy\"")
                .contains("\"hourly_inverter_energy\"")
                .contains("\"injection_mode\":\"AUTO\"");
    }

    @Test
    void historyJsonServesStatsDbRange() throws Exception {
        double now = System.currentTimeMillis() / 1000.0;
        LiveState liveState = new LiveState();
        liveState.recordGrid(42.0, 40.0);
        statsStore.persistSnapshot(liveState, new HourlyEnergyHistory(), new InverterEnergyHistory());

        HttpResponse<String> response = get("/history.json?since=" + (now - 5) + "&until=" + (now + 5));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"history\"").contains("\"grid_raw_w\":42.0");
    }

    @Test
    void historyJsonMissingParamsReturnsEmptyHistory() throws Exception {
        HttpResponse<String> response = get("/history.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"history\":[]}");
    }

    @Test
    void hourlyEnergyJsonServesStatsDbRangeForBothCharts() throws Exception {
        statsStore.upsertHourlyEnergy(List.of(Map.of("hour", 3600.0, "from_kwh", 1.0, "to_kwh", 0.5)));
        statsStore.upsertInverterHourlyEnergy(List.of(Map.of("hour", 3600.0, "111", 250.0)));

        HttpResponse<String> response = get("/hourly-energy.json?since=0&until=7200");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"hourly_energy\"").contains("\"from_kwh\":1.0")
                .contains("\"hourly_inverter_energy\"").contains("\"111\":250.0");
    }

    @Test
    void hourlyEnergyJsonMissingParamsReturnsEmptyLists() throws Exception {
        HttpResponse<String> response = get("/hourly-energy.json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"hourly_energy\":[],\"hourly_inverter_energy\":[]}");
    }

    @Test
    void overrideModeChangesReflectInStatusJson() throws Exception {
        assertThat(post("/override/mode", "mode=ON").statusCode()).isEqualTo(200);
        assertThat(get("/status.json").body()).contains("\"injection_mode\":\"ON\"");
    }

    @Test
    void overrideModeSurvivesRestart() throws Exception {
        // Regression test for the mode silently reverting to AUTO on every
        // restart: /override/mode must persist to state.json, not just update
        // the in-memory InjectionModeOverride.
        assertThat(post("/override/mode", "mode=OFF").statusCode()).isEqualTo(200);
        assertThat(StateStore.loadInjectionMode(configPath)).isEqualTo(InjectionModeOverride.Mode.OFF);
    }

    @Test
    void overrideModeRejectsInvalidValue() throws Exception {
        assertThat(post("/override/mode", "mode=BOGUS").statusCode()).isEqualTo(400);
    }

    @Test
    void overridePctRejectsInvalidValue() throws Exception {
        assertThat(post("/override/pct", "pct=42").statusCode()).isEqualTo(400);
    }

    @Test
    void overridePctSetsAndClears() throws Exception {
        assertThat(post("/override/pct", "pct=50").statusCode()).isEqualTo(200);
        assertThat(get("/status.json").body()).contains("\"pct\":50.0");
        assertThat(post("/override/pct/clear", "").statusCode()).isEqualTo(200);
        assertThat(get("/status.json").body()).contains("\"manual_override\":null");
    }

    @Test
    void saveWritesConfigJsonWithoutRestarting() throws Exception {
        String form = "opendtu.base_url=http://192.168.1.99&grid.modbus.host=192.168.1.20"
                + "&inverter_serial=222&inverter_nominal_power_w=400";
        HttpResponse<String> response = post("/save", form);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Configuration enregistree");
    }

    @Test
    void saveRejectsInvalidConfigWith400() throws Exception {
        // no inverters at all -> ConfigLoader.parseConfig rejects it
        String form = "opendtu.base_url=http://192.168.1.99&grid.modbus.host=192.168.1.20";
        HttpResponse<String> response = post("/save", form);
        assertThat(response.statusCode()).isEqualTo(400);
    }
}
