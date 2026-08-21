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

    @Test
    void defaultLoader_prefersWorkingDirectoryConfig() throws Exception {
        Path localConfig = tempDir.resolve("config.json");
        Files.writeString(localConfig, "{}\n");

        String originalUserDir = System.getProperty("user.dir");
        String originalConfigDir = System.getProperty("pcagent.config.dir");
        try {
            System.clearProperty("pcagent.config.dir");
            System.setProperty("user.dir", tempDir.toString());

            AgentConfigLoader loader = new AgentConfigLoader();
            assertThat(loader.getConfigPath()).isEqualTo(localConfig);
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            } else {
                System.clearProperty("user.dir");
            }

            if (originalConfigDir != null) {
                System.setProperty("pcagent.config.dir", originalConfigDir);
            } else {
                System.clearProperty("pcagent.config.dir");
            }
        }
    }

    @Test
    void defaultLoader_configDirPropertyOverridesWorkingDirectory() throws Exception {
        Path localConfig = tempDir.resolve("config.json");
        Files.writeString(localConfig, "{}\n");

        Path overrideDir = tempDir.resolve("override");
        Files.createDirectories(overrideDir);

        String originalUserDir = System.getProperty("user.dir");
        String originalConfigDir = System.getProperty("pcagent.config.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            System.setProperty("pcagent.config.dir", overrideDir.toString());

            AgentConfigLoader loader = new AgentConfigLoader();
            assertThat(loader.getConfigPath()).isEqualTo(overrideDir.resolve("config.json"));
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            } else {
                System.clearProperty("user.dir");
            }

            if (originalConfigDir != null) {
                System.setProperty("pcagent.config.dir", originalConfigDir);
            } else {
                System.clearProperty("pcagent.config.dir");
            }
        }
    }

    @Test
    void load_legacyProductionHttpUrl_migratesAndPersistsHttpsUrl() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, """
                {
                  "serverUrl": "http://223.194.166.251:8080/",
                  "agentToken": "preserved-token"
                }
                """);
        AgentConfigLoader loader = new AgentConfigLoader(configPath);

        AgentConfig config = loader.load();
        AgentConfig persisted = new ObjectMapper().readValue(configPath.toFile(), AgentConfig.class);

        assertThat(config.getServerUrl()).isEqualTo("https://pelmon.kro.kr");
        assertThat(persisted.getServerUrl()).isEqualTo("https://pelmon.kro.kr");
        assertThat(persisted.getAgentToken()).isEqualTo("preserved-token");
    }

    @Test
    void migrateLegacyProductionServerUrl_doesNotRewriteUnrelatedHttpServer() {
        assertThat(AgentConfigLoader.migrateLegacyProductionServerUrl("http://localhost:8888"))
                .isEqualTo("http://localhost:8888");
    }

    @Test
    void resolveDefaultBaseDir_prefersProgramDataOverLegacyAppData() throws Exception {
        Path workingDir = tempDir.resolve("working");
        Path launchDir = tempDir.resolve("launch");
        Path programDataDir = tempDir.resolve("ProgramData").resolve("PCAgent");
        Path appDataDir = tempDir.resolve("AppData").resolve("PCAgent");
        Path homeDir = tempDir.resolve("home");

        Files.createDirectories(programDataDir);
        Files.writeString(programDataDir.resolve("config.json"), "{}\n");
        Files.createDirectories(appDataDir);
        Files.writeString(appDataDir.resolve("config.json"), "{}\n");

        Path resolved = AgentConfigLoader.resolveDefaultBaseDir(
                workingDir, launchDir, programDataDir, appDataDir, homeDir);

        assertThat(resolved).isEqualTo(programDataDir);
    }

    @Test
    void resolveDefaultBaseDir_usesLegacyAppDataWhenProgramDataMissing() throws Exception {
        Path workingDir = tempDir.resolve("working");
        Path launchDir = tempDir.resolve("launch");
        Path appDataDir = tempDir.resolve("AppData").resolve("PCAgent");
        Path homeDir = tempDir.resolve("home");

        Files.createDirectories(appDataDir);
        Files.writeString(appDataDir.resolve("config.json"), "{}\n");

        Path resolved = AgentConfigLoader.resolveDefaultBaseDir(
                workingDir, launchDir, null, appDataDir, homeDir);

        assertThat(resolved).isEqualTo(appDataDir);
    }
}
