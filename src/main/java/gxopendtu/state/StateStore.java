package gxopendtu.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny on-disk persistence for the pieces of control-loop state that must
 * survive a restart: the battery-full hysteresis latch
 * (BatteryFullHysteresis.active) and the sticky AUTO/ON/OFF regulation mode
 * (InjectionModeOverride).
 *
 * Everything else live (LiveState, HourlyEnergyHistory) is deliberately
 * ephemeral -- these two are the exception, because losing either silently
 * changes real regulation behaviour: without the latch, a restart while the
 * battery is already full resets it to "not yet full"; without the mode, a
 * restart silently reverts the dashboard's mode selector to AUTO even if the
 * user had explicitly forced ON or OFF, and in AUTO the very next decision
 * cycle's SOC-driven hysteresis update can then flip the latch itself with
 * no visible indication anything changed.
 *
 * Both fields live in the same state.json (next to config.json), so saving
 * one must never clobber the other -- every write reads the current file
 * first and merges the new field into it. Missing/corrupt is not an error,
 * just "unknown" (caller decides the safe default).
 *
 * Port of src/state_store.py -- extended with injection_mode, which the
 * Python original's README documents as persisted but never actually is
 * (state_store.py there only ever handles injection_active).
 */
public final class StateStore {

    private static final Logger LOG = Logger.getLogger("gx-opendtu-zero-export");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StateStore() {}

    private static Path statePath(Path configPath) {
        Path directory = configPath.toAbsolutePath().getParent();
        return directory.resolve("state.json");
    }

    private static ObjectNode loadRaw(Path configPath) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(statePath(configPath)));
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (IOException ignored) {
            // missing/corrupt state.json -- treated as "nothing persisted yet"
        }
        return MAPPER.createObjectNode();
    }

    private static void writeRaw(Path configPath, ObjectNode raw) {
        Path path = statePath(configPath);
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, MAPPER.writeValueAsString(raw));
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "failed to persist state to " + path, e);
        }
    }

    public static Boolean loadInjectionActive(Path configPath) {
        JsonNode value = loadRaw(configPath).path("injection_active");
        return value.isBoolean() ? value.asBoolean() : null;
    }

    public static void saveInjectionActive(Path configPath, boolean active) {
        ObjectNode raw = loadRaw(configPath);
        raw.put("injection_active", active);
        writeRaw(configPath, raw);
    }

    public static InjectionModeOverride.Mode loadInjectionMode(Path configPath) {
        JsonNode value = loadRaw(configPath).path("injection_mode");
        if (!value.isTextual()) {
            return null;
        }
        try {
            return InjectionModeOverride.Mode.valueOf(value.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void saveInjectionMode(Path configPath, InjectionModeOverride.Mode mode) {
        ObjectNode raw = loadRaw(configPath);
        raw.put("injection_mode", mode.name());
        writeRaw(configPath, raw);
    }
}
