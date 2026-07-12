package gxopendtu.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTest {

    @Test
    void loadReturnsNullWhenFileMissing(@TempDir Path tmpDir) {
        Path configPath = tmpDir.resolve("config.json");
        assertThat(StateStore.loadInjectionActive(configPath)).isNull();
    }

    @Test
    void saveThenLoadRoundtrips(@TempDir Path tmpDir) {
        Path configPath = tmpDir.resolve("config.json");
        StateStore.saveInjectionActive(configPath, true);
        assertThat(StateStore.loadInjectionActive(configPath)).isTrue();
        StateStore.saveInjectionActive(configPath, false);
        assertThat(StateStore.loadInjectionActive(configPath)).isFalse();
    }

    @Test
    void stateFileLivesNextToConfig(@TempDir Path tmpDir) {
        Path configPath = tmpDir.resolve("config.json");
        StateStore.saveInjectionActive(configPath, true);
        assertThat(Files.exists(tmpDir.resolve("state.json"))).isTrue();
    }

    @Test
    void loadReturnsNullOnCorruptJson(@TempDir Path tmpDir) throws Exception {
        Path configPath = tmpDir.resolve("config.json");
        Files.writeString(tmpDir.resolve("state.json"), "not valid json{{{");
        assertThat(StateStore.loadInjectionActive(configPath)).isNull();
    }

    @Test
    void loadReturnsNullWhenKeyMissingOrWrongType(@TempDir Path tmpDir) throws Exception {
        Path configPath = tmpDir.resolve("config.json");
        Path statePath = tmpDir.resolve("state.json");

        Files.writeString(statePath, "{\"injection_active\": \"yes\"}");
        assertThat(StateStore.loadInjectionActive(configPath)).isNull();

        Files.writeString(statePath, "{\"other_key\": true}");
        assertThat(StateStore.loadInjectionActive(configPath)).isNull();
    }
}
