package gxopendtu.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny on-disk persistence for the one piece of control-loop state that must
 * survive a restart: the battery-full hysteresis latch
 * (BatteryFullHysteresis.active).
 *
 * Everything else live (LiveState, HourlyEnergyHistory) is deliberately
 * ephemeral -- this is the one exception, because losing it silently changes
 * real regulation behaviour: without it, a restart while the battery is
 * already full resets the latch to "not yet full", which can leave injection
 * control stuck OFF (uncapped inverters) until SOC climbs all the way back
 * up. Written next to config.json; missing/corrupt is not an error, just
 * "unknown" (caller decides the safe default).
 *
 * Port of src/state_store.py.
 */
public final class StateStore {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StateStore() {}

    private static Path statePath(Path configPath) {
        Path directory = configPath.toAbsolutePath().getParent();
        return directory.resolve("state.json");
    }

    public static Boolean loadInjectionActive(Path configPath) {
        Path path = statePath(configPath);
        JsonNode node;
        try {
            node = MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            return null;
        }
        JsonNode value = node.path("injection_active");
        return value.isBoolean() ? value.asBoolean() : null;
    }

    public static void saveInjectionActive(Path configPath, boolean active) {
        Path path = statePath(configPath);
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, MAPPER.writeValueAsString(Map.of("injection_active", active)));
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "failed to persist injection_active state to " + path, e);
        }
    }
}
