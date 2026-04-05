package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class AgentConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_noFile_returnsDefaults() {
        Path configPath = tempDir.resolve("config.json");
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = loader.load();

        assertThat(config.getServerUrl()).isEqualTo("http://localhost:8888");
        assertThat(config.getHeartbeatIntervalSec()).isEqualTo(30);
    }

    @Test
    void load_validFile_parsesCorrectly() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        String json = """
                {
                  "serverUrl": "https://prod:8888",
                  "installKey": "mykey",
                  "agentToken": "agt_12345",
                  "heartbeatIntervalSec": 60,
                  "jitterSec": 15
                }
                """;
        Files.writeString(configPath, json);
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = loader.load();

        assertThat(config.getServerUrl()).isEqualTo("https://prod:8888");
        assertThat(config.getInstallKey()).isEqualTo("mykey");
        assertThat(config.getAgentToken()).isEqualTo("agt_12345");
        assertThat(config.getHeartbeatIntervalSec()).isEqualTo(60);
        assertThat(config.getJitterSec()).isEqualTo(15);
    }

    @Test
    void load_malformedJson_returnsDefaults() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{ not valid json }}}");
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = loader.load();

        assertThat(config.getServerUrl()).isEqualTo("http://localhost:8888");
        assertThat(config.getJitterSec()).isEqualTo(5);
    }

    @Test
    void save_and_reload() {
        Path configPath = tempDir.resolve("config.json");
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = new AgentConfig();
        config.setServerUrl("https://saved:8888");
        config.setAgentToken("saved-token");
        loader.save(config);

        AgentConfig reloaded = loader.load();
        assertThat(reloaded.getServerUrl()).isEqualTo("https://saved:8888");
        assertThat(reloaded.getAgentToken()).isEqualTo("saved-token");
    }

    @Test
    void saveToken_updatesTokenAndPersists() {
        Path configPath = tempDir.resolve("config.json");
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = new AgentConfig();
        loader.saveToken(config, "new-token-value");

        assertThat(config.getAgentToken()).isEqualTo("new-token-value");

        AgentConfig reloaded = loader.load();
        assertThat(reloaded.getAgentToken()).isEqualTo("new-token-value");
    }

    @Test
    void getConfigPath_returnsExpected() {
        Path configPath = tempDir.resolve("config.json");
        AgentConfigLoader loader = new AgentConfigLoader(configPath);
        assertThat(loader.getConfigPath()).isEqualTo(configPath);
    }

    @Test
    void load_ignoresUnknownFields() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        String json = """
                {
                  "serverUrl": "https://test:8888",
                  "unknownField": "should be ignored",
                  "heartbeatIntervalSec": 90
                }
                """;
        Files.writeString(configPath, json);
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = loader.load();
        assertThat(config.getServerUrl()).isEqualTo("https://test:8888");
        assertThat(config.getHeartbeatIntervalSec()).isEqualTo(90);
    }
}
