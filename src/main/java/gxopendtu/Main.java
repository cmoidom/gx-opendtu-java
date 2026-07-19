package gxopendtu;

import gxopendtu.config.AppConfig;
import gxopendtu.config.ConfigLoader;
import gxopendtu.loop.ControlLoop;
import gxopendtu.opendtu.OpenDTUClient;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InternalStatus;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.state.StateStore;
import gxopendtu.stats.StatsStore;
import gxopendtu.sunspec.SunSpecPoller;
import gxopendtu.sunspec.SunSpecProxyState;
import gxopendtu.sunspec.SunSpecRegisterMap;
import gxopendtu.sunspec.SunSpecTcpServer;
import gxopendtu.webui.WebUiServer;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
        InternalStatus internalStatus = new InternalStatus();
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
        // path, not just the ones that go through the config page's /config/apply.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            statsStore.persistSnapshot(liveState, energyHistory, inverterEnergyHistory);
            statsStore.close();
        }, "stats-shutdown-flush"));

        // Detection spike (2026-07-19, disabled by default): a SunSpec Modbus
        // TCP server running alongside the existing control loop, so Venus
        // OS can be tested for whether it discovers this device at all --
        // read side wired to real OpenDTU production, write side (Venus OS's
        // WMaxLimPct/Conn) only observed on /internal, never forwarded. Zero
        // effect on ControlLoop.run below either way -- see gxopendtu.sunspec.
        // Deliberately never allowed to crash the process: a bind failure
        // (e.g. the default port 502 without CAP_NET_BIND_SERVICE, see
        // deploy/systemd/gx-opendtu-zero-export.service) must not take down
        // the real zero-export loop below, only disable this optional spike.
        SunSpecProxyState sunSpecProxyState = null;
        if (config.sunspecProxy().enabled()) {
            try {
                sunSpecProxyState = new SunSpecProxyState();
                SunSpecRegisterMap registerMap = new SunSpecRegisterMap(
                        config.sunspecProxy().manufacturer(),
                        config.sunspecProxy().model(),
                        config.sunspecProxy().serialNumber(),
                        config.totalNominalPowerW());
                OpenDTUClient sunSpecOpenDtuClient = new OpenDTUClient(
                        config.opendtu().baseUrl(), config.opendtu().username(), config.opendtu().password());
                List<String> allSerials = config.inverters().stream()
                        .map(AppConfig.InverterConfig::serial)
                        .collect(Collectors.toList());
                new SunSpecPoller(
                                sunSpecOpenDtuClient, allSerials, registerMap, sunSpecProxyState,
                                config.sunspecProxy().pollIntervalS())
                        .start();
                new SunSpecTcpServer(config.sunspecProxy().tcpPort(), registerMap, sunSpecProxyState).start();
            } catch (RuntimeException e) {
                LOG.severe("[spike SunSpec] demarrage echoue, spike desactive pour cette execution "
                        + "(la regulation zero-export n'est pas affectee): " + e.getMessage());
                sunSpecProxyState = null;
            }
        }

        WebUiServer.start(
                configPath, config.web().port(), liveState, internalStatus, energyHistory, inverterEnergyHistory,
                manualOverride, injectionMode, statsStore, sunSpecProxyState);
        LOG.info("tableau de bord disponible sur http://0.0.0.0:" + config.web().port() + "/ "
                + "(configuration sur http://0.0.0.0:" + config.web().port() + "/config, "
                + "etat interne sur http://0.0.0.0:" + config.web().port() + "/internal)");

        ControlLoop.run(
                config, dryRun, liveState, internalStatus, energyHistory, inverterEnergyHistory, configPath,
                manualOverride, injectionMode, statsStore);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar gx-opendtu-java.jar --config <path> [--dry-run]");
    }
}
