package gxopendtu;

import gxopendtu.config.AppConfig;
import gxopendtu.config.ConfigLoader;
import gxopendtu.loop.ControlLoop;
import gxopendtu.opendtu.OpenDTUClient;
import gxopendtu.opendtu.OpenDTUException;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.InternalStatus;
import gxopendtu.state.InverterEnergyHistory;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
import gxopendtu.state.StateStore;
import gxopendtu.stats.StatsStore;
import gxopendtu.sunspec.SunSpecForwarder;
import gxopendtu.sunspec.SunSpecPoller;
import gxopendtu.sunspec.SunSpecProxyState;
import gxopendtu.sunspec.SunSpecRegisterMap;
import gxopendtu.sunspec.SunSpecTcpServer;
import gxopendtu.webui.WebUiServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

        // SunSpec Modbus TCP proxy (2026-07-19), for Venus OS's zero-feed-in/
        // dynamic ESS PV inverter integration: runs alongside the existing
        // control loop -- read side wired to real OpenDTU production, write
        // side (Venus OS's WMaxLimPct/Conn) always observed on /internal;
        // additionally acted on for real only when forwardToOpendtu() is true
        // (see effectiveDryRun below, which then suppresses ControlLoop's own
        // OpenDTU writes so the two never both command the same inverters).
        // Deliberately never allowed to crash the process: a bind failure
        // (e.g. the default port 502 without CAP_NET_BIND_SERVICE, see
        // deploy/systemd/gx-opendtu-zero-export.service) must not take down
        // the real zero-export loop below, only disable this proxy.
        SunSpecProxyState sunSpecProxyState = null;
        try {
            sunSpecProxyState = new SunSpecProxyState();
            SunSpecRegisterMap registerMap = new SunSpecRegisterMap(
                    config.sunspecProxy().manufacturer(),
                    config.sunspecProxy().model(),
                    config.sunspecProxy().serialNumber(),
                    config.totalNominalPowerW());
            OpenDTUClient sunSpecOpenDtuClient = new OpenDTUClient(
                    config.opendtu().baseUrl(), config.opendtu().username(), config.opendtu().password());
            // Own try/catch, separate from the outer one: a failed fetch here
            // (e.g. OpenDTU unreachable yet at boot) must not prevent the
            // poller/TCP server below from starting -- Vr just stays at its
            // "unknown" placeholder until a later restart succeeds.
            try {
                registerMap.setFirmwareVersion(sunSpecOpenDtuClient.getFirmwareVersion());
            } catch (OpenDTUException e) {
                LOG.warning("[SunSpec] lecture version OpenDTU echouee, Vr reste \"unknown\": " + e.getMessage());
            }
            List<String> allSerials = config.inverters().stream()
                    .map(AppConfig.InverterConfig::serial)
                    .collect(Collectors.toList());
            new SunSpecPoller(
                            sunSpecOpenDtuClient, allSerials, registerMap, sunSpecProxyState,
                            config.sunspecProxy().pollIntervalS())
                    .start();
            new SunSpecTcpServer(config.sunspecProxy().tcpPort(), registerMap, sunSpecProxyState).start();

            if (config.sunspecProxy().forwardToOpendtu()) {
                Map<String, Double> nominalPowerW = config.inverters().stream()
                        .collect(Collectors.toMap(AppConfig.InverterConfig::serial, AppConfig.InverterConfig::nominalPowerW));
                List<String> controllableSerials = config.inverters().stream()
                        .filter(AppConfig.InverterConfig::controllable)
                        .map(AppConfig.InverterConfig::serial)
                        .toList();
                new SunSpecForwarder(
                                sunSpecOpenDtuClient,
                                registerMap,
                                allSerials,
                                controllableSerials,
                                nominalPowerW,
                                config.capacityProbe().stepW(),
                                config.control().minInverterPct(),
                                config.control().decisionIntervalS(),
                                config.capacityProbe().intervalS(),
                                config.control().minChangeW())
                        .start();
            }
        } catch (RuntimeException e) {
            LOG.severe("[SunSpec] demarrage du proxy echoue, desactive pour cette execution "
                    + "(la regulation zero-export n'est pas affectee): " + e.getMessage());
            sunSpecProxyState = null;
        }
        // Suppresses ControlLoop's own real OpenDTU writes for exactly as long
        // as the SunSpec forwarder is actively commanding the same inverters --
        // it still computes/displays everything normally, same as --dry-run.
        boolean effectiveDryRun = dryRun || config.sunspecProxy().forwardToOpendtu();

        WebUiServer.start(
                configPath, config.web().port(), liveState, internalStatus, energyHistory, inverterEnergyHistory,
                manualOverride, injectionMode, statsStore, sunSpecProxyState);
        LOG.info("tableau de bord disponible sur http://0.0.0.0:" + config.web().port() + "/ "
                + "(configuration sur http://0.0.0.0:" + config.web().port() + "/config, "
                + "etat interne sur http://0.0.0.0:" + config.web().port() + "/internal)");

        ControlLoop.run(
                config, effectiveDryRun, liveState, internalStatus, energyHistory, inverterEnergyHistory, configPath,
                manualOverride, injectionMode, statsStore);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar gx-opendtu-java.jar --config <path> [--dry-run]");
    }
}
