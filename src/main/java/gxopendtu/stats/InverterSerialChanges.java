package gxopendtu.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Inverter changes made on the config page that the stats database must
 * follow (user decision 2026-10-18): a serial edited in its row is the same
 * inverter replaced -- its history continues under the new serial; a removed
 * row is an inverter that is gone -- its history is purged.
 *
 * Recorded when the config is saved, applied at the next startup (before the
 * dashboard history is loaded): until that restart the running process keeps
 * writing samples under the old serial, which the change then covers too.
 * Pending changes live in {@code inverter_changes.json} next to config.json.
 */
public final class InverterSerialChanges {

    private static final Logger LOG = Logger.getLogger(InverterSerialChanges.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "inverter_changes.json";

    /** {@code toSerial} is null for a purge. */
    public record Change(String fromSerial, String toSerial) {
        public static Change rename(String fromSerial, String toSerial) {
            return new Change(fromSerial, toSerial);
        }

        public static Change purge(String serial) {
            return new Change(serial, null);
        }

        public boolean isPurge() {
            return toSerial == null;
        }
    }

    private InverterSerialChanges() {}

    /** Appends to whatever is already pending (several saves before one restart). */
    public static void recordPending(Path configPath, List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<Change> all = new ArrayList<>(loadPending(configPath));
        all.addAll(changes);
        ArrayNode array = MAPPER.createArrayNode();
        for (Change change : all) {
            ObjectNode node = array.addObject();
            node.put("from", change.fromSerial());
            if (change.isPurge()) {
                node.putNull("to");
            } else {
                node.put("to", change.toSerial());
            }
        }
        try {
            Files.writeString(file(configPath), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to record inverter changes next to " + configPath, e);
        }
        for (Change change : changes) {
            LOG.warning(change.isPurge()
                    ? "onduleur " + change.fromSerial() + " supprime: son historique sera purge au prochain redemarrage"
                    : "onduleur " + change.fromSerial() + " remplace par " + change.toSerial()
                            + ": son historique continuera sous le nouveau numero au prochain redemarrage");
        }
    }

    static List<Change> loadPending(Path configPath) {
        Path path = file(configPath);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<Change> changes = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(Files.readString(path))) {
                String to = node.path("to").isTextual() ? node.path("to").asText() : null;
                changes.add(new Change(node.path("from").asText(), to));
            }
            return changes;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + path, e);
        }
    }

    /** Called once at startup; a no-op when nothing is pending. */
    public static void applyPending(Path configPath, StatsStore statsStore) {
        List<Change> changes = loadPending(configPath);
        try {
            for (Change change : changes) {
                if (change.isPurge()) {
                    statsStore.purgeInverter(change.fromSerial());
                    LOG.warning("historique de l'onduleur " + change.fromSerial() + " purge");
                } else {
                    statsStore.renameInverter(change.fromSerial(), change.toSerial());
                    LOG.warning("historique de l'onduleur " + change.fromSerial() + " repris sous " + change.toSerial());
                }
            }
        } catch (StatsStoreException e) {
            // Kept for the next startup: both operations are idempotent, so
            // re-running the ones that already went through is harmless.
            LOG.severe("changements d'onduleurs non appliques a la base, nouvel essai au prochain demarrage: "
                    + e.getMessage());
            return;
        }
        try {
            Files.deleteIfExists(file(configPath));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete " + file(configPath), e);
        }
    }

    private static Path file(Path configPath) {
        return configPath.resolveSibling(FILE_NAME);
    }
}
