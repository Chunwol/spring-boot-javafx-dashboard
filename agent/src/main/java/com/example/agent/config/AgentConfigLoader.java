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

    public AgentConfigLoader() {
        String appData = System.getenv("APPDATA");
        Path baseDir = appData != null
                ? Path.of(appData, "PCAgent")
                : Path.of(System.getProperty("user.home"), ".pcagent");
        this.configPath = baseDir.resolve("config.json");
    }

    /** 개발/테스트용: 경로 직접 지정 */
    AgentConfigLoader(Path configPath) {
        this.configPath = configPath;
    }

    public AgentConfig load() {
        if (!Files.exists(configPath)) {
            log.warn("config.json 없음 → 기본 설정 사용: {}", configPath);
            return new AgentConfig();
        }
        try {
            return MAPPER.readValue(configPath.toFile(), AgentConfig.class);
        } catch (IOException e) {
            log.error("config.json 파싱 실패: {}", e.getMessage());
            return new AgentConfig();
        }
    }

    /** agentToken 갱신 후 저장 */
    public void saveToken(AgentConfig config, String token) {
        config.setAgentToken(token);
        save(config);
    }

    public void save(AgentConfig config) {
        try {
            Files.createDirectories(configPath.getParent());
            MAPPER.writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            log.error("config.json 저장 실패: {}", e.getMessage());
        }
    }

    public Path getConfigPath() { return configPath; }
}
