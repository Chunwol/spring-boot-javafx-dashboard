package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;

/**
 * config.json 로드 / 저장 유틸리티
 * 경로: %APPDATA%\PCAgent\config.json (Windows)
 *       ~/.pcagent/config.json       (Linux/macOS)
 */
public class AgentConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configPath;

    // CRITICAL FIX#4: Synchronize config file access to prevent race conditions
    private final Object configLock = new Object();
    private final AgentConfigValidator validator = new AgentConfigValidator();

    public AgentConfigLoader() {
        // -Dpcagent.config.dir=<path> 시스템 속성으로 경로 재정의 가능
        // (Task Scheduler로 SYSTEM 계정 실행 시 %APPDATA% 경로 문제 방지)
        String configDirProp = System.getProperty("pcagent.config.dir");
        Path baseDir;
        if (configDirProp != null && !configDirProp.isBlank()) {
            baseDir = Path.of(configDirProp);
        } else {
            String appData = System.getenv("APPDATA");
            baseDir = appData != null
                    ? Path.of(appData, "PCAgent")
                    : Path.of(System.getProperty("user.home"), ".pcagent");
        }
        this.configPath = baseDir.resolve("config.json");
    }

    /** 개발/테스트용: 경로 직접 지정 */
    AgentConfigLoader(Path configPath) {
        this.configPath = configPath;
    }

    public AgentConfig load() {
        AgentConfig config;
        if (!Files.exists(configPath)) {
            log.warn("config.json 없음 → 기본 설정 사용: {}", configPath);
            config = new AgentConfig();
        } else {
            try {
                config = MAPPER.readValue(configPath.toFile(), AgentConfig.class);
            } catch (IOException e) {
                log.error("config.json 파싱 실패: {}", e.getMessage());
                config = new AgentConfig();
            }
        }
        applyEnvOverrides(config);

        // Validate configuration
        validator.validateAndLog(config);

        return config;
    }

    /** agentToken 갱신 후 저장 */
    public void saveToken(AgentConfig config, String token) {
        synchronized (configLock) {
            config.setAgentToken(token);
            save(config);
        }
    }

    public void save(AgentConfig config) {
        synchronized (configLock) {
            try {
                Files.createDirectories(configPath.getParent());
                MAPPER.writeValue(configPath.toFile(), config);
            } catch (IOException e) {
                log.error("config.json 저장 실패: {}", e.getMessage());
            }
        }
    }

    public Path getConfigPath() { return configPath; }

    private void applyEnvOverrides(AgentConfig config) {
        config.setServerUrl(readEnv("AGENT_SERVER_URL", readEnv("BACKEND_URL", config.getServerUrl())));
        config.setInstallKey(readEnv("AGENT_INSTALL_KEY", config.getInstallKey()));
        config.setAgentToken(readEnv("AGENT_TOKEN", config.getAgentToken()));
        config.setTlsPinSha256(readEnv("AGENT_TLS_PIN_SHA256", config.getTlsPinSha256()));
        config.setHeartbeatIntervalSec(parseInt(readEnv("AGENT_HEARTBEAT_INTERVAL_SEC", null), config.getHeartbeatIntervalSec()));
        config.setJitterSec(parseInt(readEnv("AGENT_JITTER_SEC", null), config.getJitterSec()));
    }

    private String readEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
