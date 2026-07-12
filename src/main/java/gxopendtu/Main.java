package gxopendtu;

import gxopendtu.config.AppConfig;
import gxopendtu.config.ConfigLoader;
import gxopendtu.loop.ControlLoop;
import gxopendtu.state.HourlyEnergyHistory;
import gxopendtu.state.InjectionModeOverride;
import gxopendtu.state.LiveState;
import gxopendtu.state.ManualOverride;
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
        ManualOverride manualOverride = new ManualOverride();
        InjectionModeOverride injectionMode = new InjectionModeOverride();

        if (config.web().enabled()) {
            WebUiServer.start(configPath, config.web().port(), liveState, energyHistory, manualOverride, injectionMode);
            LOG.info("page de configuration disponible sur http://0.0.0.0:" + config.web().port() + "/");
        }

        ControlLoop.run(config, dryRun, liveState, energyHistory, configPath, manualOverride, injectionMode);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar gx-opendtu-java.jar --config <path> [--dry-run]");
    }
}
