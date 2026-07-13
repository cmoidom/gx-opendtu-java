package gxopendtu.webui;

import com.sun.net.httpserver.HttpServer;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.stats.StatsStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Starts the built-in config editor ("/") and live dashboard ("/dashboard")
 * on config.web.port, using the JDK's own HttpServer (no external
 * dependency) -- the direct equivalent of Python's
 * http.server.ThreadingHTTPServer. HttpServer.start() spawns its own
 * listener thread and returns immediately, so no extra thread is needed here.
 *
 * Port of src/webui.py's start_webui_server.
 */
public final class WebUiServer {

    private final HttpServer server;

    private WebUiServer(HttpServer server) {
        this.server = server;
    }

    public static WebUiServer start(
            Path configPath,
            int port,
            LiveState liveState,
            HourlyEnergyHistory energyHistory,
            InverterEnergyHistory inverterEnergyHistory,
            ManualOverride manualOverride,
            InjectionModeOverride injectionMode,
            StatsStore statsStore) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "gx-opendtu-webui");
                thread.setDaemon(true);
                return thread;
            }));

            server.createContext(
                    "/", new ConfigPageHandler(configPath, liveState, energyHistory, inverterEnergyHistory, statsStore));
            server.createContext("/dashboard", new DashboardHandler());
            server.createContext(
                    "/status.json",
                    new StatusJsonHandler(
                            liveState, energyHistory, inverterEnergyHistory, manualOverride, injectionMode));
            server.createContext("/history.json", new HistoryJsonHandler(statsStore));
            server.createContext("/fetch-inverters", new FetchInvertersHandler());

            OverrideHandlers overrideHandlers = new OverrideHandlers(manualOverride, injectionMode, configPath);
            server.createContext("/override/pct", overrideHandlers.pctHandler());
            server.createContext("/override/pct/clear", overrideHandlers.pctClearHandler());
            server.createContext("/override/mode", overrideHandlers.modeHandler());

            server.start();
            return new WebUiServer(server);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start web UI server on port " + port, e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }
}
