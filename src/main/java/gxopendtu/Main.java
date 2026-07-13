package gxopendtu;

import gxopendtu.config.AppConfig;
import gxopendtu.config.ConfigLoader;
import gxopendtu.loop.ControlLoop;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.state.StateStore;
import gxopendtu.stats.StatsStore;
import gxopendtu.webui.WebUiServer;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Entry point: wires the grid meter, OpenDTU client and control loop
 * together. Port of src/main.py's main(). Unlike the Python reference
 * project, this port always runs off-device (Linux VM), so there is no
 * D-Bus/Venus OS deployment path -- Modbus TCP is the only grid/battery
 * reading mode.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");
    private static final String DEFAULT_CONFIG_PATH = "/etc/gx-opendtu/config.json";

    private Main() {}

    public static void main(String[] args) {
        LoggingSetup.configure();

        String configPathArg = DEFAULT_CONFIG_PATH;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--config requires a value");
                        System.exit(2);
                    }
                    configPathArg = args[++i];
                }
                case "--dry-run" -> dryRun = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        Path configPath = Path.of(configPathArg);
        AppConfig config = ConfigLoader.loadConfig(configPath);
        LiveState liveState = new LiveState();
        HourlyEnergyHistory energyHistory = new HourlyEnergyHistory();
        InverterEnergyHistory inverterEnergyHistory = new InverterEnergyHistory();
        ManualOverride manualOverride = new ManualOverride();
        InjectionModeOverride injectionMode = new InjectionModeOverride();
        // Restore the sticky AUTO/ON/OFF mode across restarts, same as the
        // hysteresis latch itself (see ControlLoop.run) -- otherwise the
        // dashboard's mode selector silently reverts to AUTO on every
        // restart even if the user had explicitly forced ON or OFF.
        InjectionModeOverride.Mode persistedMode = StateStore.loadInjectionMode(configPath);
        if (persistedMode != null) {
            injectionMode.setMode(persistedMode);
        }
        StatsStore statsStore = new StatsStore(configPath.resolveSibling("stats.db"));
        // Seed the live dashboard's in-memory ring buffer from the persisted
        // long-term stats DB, otherwise every restart (update.sh, systemctl
        // restart, a crash) shows completely empty charts until enough new
        // samples accumulate live -- see LiveState.seedHistory's javadoc.
        liveState.seedHistory(statsStore.loadRecentSamples(LiveState.DEFAULT_MAX_SAMPLES));
        double energyHistoryCutoff =
                System.currentTimeMillis() / 1000.0 - HourlyEnergyHistory.DEFAULT_RETAIN_HOURS * 3600.0;
        energyHistory.seedBuckets(statsStore.loadHourlyEnergy(energyHistoryCutoff));
        inverterEnergyHistory.seedBuckets(statsStore.loadInverterHourlyEnergy(energyHistoryCutoff));
        // Flush the latest known state on SIGTERM (systemctl stop/restart,
        // update.sh, VM reboot) so a restart never loses more than the
        // in-memory samples since the last periodic write, rather than up to
        // a full stats.interval_s (default 5 min) -- covers every restart
        // path, not just the ones that go through the config page's /apply.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            statsStore.persistSnapshot(liveState, energyHistory, inverterEnergyHistory);
            statsStore.close();
        }, "stats-shutdown-flush"));

        WebUiServer.start(
                configPath, config.web().port(), liveState, energyHistory, inverterEnergyHistory, manualOverride,
                injectionMode, statsStore);
        LOG.info("page de configuration disponible sur http://0.0.0.0:" + config.web().port() + "/");

        ControlLoop.run(
                config, dryRun, liveState, energyHistory, inverterEnergyHistory, configPath, manualOverride,
                injectionMode, statsStore);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar gx-opendtu-java.jar --config <path> [--dry-run]");
    }
}
