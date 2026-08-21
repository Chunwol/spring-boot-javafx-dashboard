package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;

/**
 * config.json 로드 / 저장 유틸리티.
 *
 * Windows 설치형 에이전트는 C:\ProgramData\PCAgent\config.json 을 우선 사용하고,
 * 포터블/레거시 실행은 실행 위치 또는 %APPDATA%\PCAgent\config.json 을 계속 지원한다.
 * Linux/macOS 는 ~/.pcagent/config.json 을 사용한다.
 */
public class AgentConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String WINDOWS_PROGRAM_DATA_DIR = "C:\\ProgramData\\PCAgent";

    private final Path configPath;

    // CRITICAL FIX#4: Synchronize config file access to prevent race conditions
    private final Object configLock = new Object();

    public AgentConfigLoader() {
        // -Dpcagent.config.dir=<path> 또는 PCAGENT_CONFIG_DIR 환경변수로 경로 재정의 가능
        // (Task Scheduler로 SYSTEM 계정 실행 시 %APPDATA% 경로 문제 방지)
        String configDirProp = firstNonBlank(
                System.getProperty("pcagent.config.dir"),
                System.getenv("PCAGENT_CONFIG_DIR"),
                System.getenv("AGENT_CONFIG_DIR"));
        Path baseDir;
        if (configDirProp != null && !configDirProp.isBlank()) {
            baseDir = Path.of(configDirProp);
            log.info("config 경로 재정의 사용: {}", baseDir);
        } else {
            Path workingDir = Path.of(System.getProperty("user.dir", "."));
            if (Files.exists(workingDir.resolve(CONFIG_FILE_NAME))) {
                // 개발/휴대 실행 편의를 위해 실행 위치의 config.json을 우선 사용한다.
                baseDir = workingDir;
                log.info("실행 위치 config.json 감지: {}", workingDir.resolve(CONFIG_FILE_NAME));
            } else {
                Path launchDir = resolveLaunchDirectory();
                if (launchDir != null && Files.exists(launchDir.resolve(CONFIG_FILE_NAME))) {
                    // jar/exe가 있는 설치 폴더에서 직접 실행된 경우를 지원한다.
                    baseDir = launchDir;
                    log.info("실행 파일 위치 config.json 감지: {}", launchDir.resolve(CONFIG_FILE_NAME));
                } else {
                    Path programDataDir = resolveProgramDataDirectory();
                    Path appDataDir = resolveAppDataDirectory();
                    Path homeDir = Path.of(System.getProperty("user.home", "."));
                    baseDir = resolveDefaultBaseDir(workingDir, launchDir, programDataDir, appDataDir, homeDir);
                }
            }
        }
        this.configPath = baseDir.resolve(CONFIG_FILE_NAME);
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
        String migratedServerUrl = migrateLegacyProductionServerUrl(config.getServerUrl());
        if (!java.util.Objects.equals(config.getServerUrl(), migratedServerUrl)) {
            config.setServerUrl(migratedServerUrl);
            save(config);
            log.info("구형 운영 서버 주소를 HTTPS 주소로 마이그레이션했습니다.");
        }
        applyEnvOverrides(config);
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
                // 원자적 쓰기: 임시 파일에 먼저 쓴 후 이동 (읽기 중 손상 방지)
                Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
                MAPPER.writeValue(tmp.toFile(), config);
                try {
                    Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("config.json 저장 실패: {}", e.getMessage());
            }
        }
    }

    public Path getConfigPath() { return configPath; }

    static Path resolveDefaultBaseDir(
            Path workingDir,
            Path launchDir,
            Path programDataDir,
            Path appDataDir,
            Path homeDir) {

        if (hasConfig(workingDir)) {
            return workingDir;
        }
        if (hasConfig(launchDir)) {
            return launchDir;
        }
        if (hasConfig(programDataDir)) {
            return programDataDir;
        }
        if (hasConfig(appDataDir)) {
            return appDataDir;
        }
        return homeDir != null ? homeDir.resolve(".pcagent") : Path.of(".pcagent");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasConfig(Path baseDir) {
        return baseDir != null && Files.exists(baseDir.resolve(CONFIG_FILE_NAME));
    }

    static String migrateLegacyProductionServerUrl(String serverUrl) {
        if (serverUrl == null) return null;
        String normalized = serverUrl.trim().replaceAll("/+$", "");
        if (normalized.equalsIgnoreCase("http://223.194.166.251:8080")
                || normalized.equalsIgnoreCase("http://pelmon.kro.kr")
                || normalized.equalsIgnoreCase("http://pelmon.kro.kr:80")) {
            return "https://pelmon.kro.kr";
        }
        return serverUrl;
    }

    private Path resolveProgramDataDirectory() {
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "PCAgent");
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return Path.of(WINDOWS_PROGRAM_DATA_DIR);
        }
        return null;
    }

    private Path resolveAppDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "PCAgent");
        }
        return null;
    }

    private void applyEnvOverrides(AgentConfig config) {
        config.setServerUrl(readEnv("AGENT_SERVER_URL", readEnv("BACKEND_URL", config.getServerUrl())));
        config.setInstallKey(readEnv("AGENT_INSTALL_KEY", config.getInstallKey()));
        config.setAgentToken(readEnv("AGENT_TOKEN", config.getAgentToken()));
        config.setTlsPinSha256(readEnv("AGENT_TLS_PIN_SHA256", config.getTlsPinSha256()));
        config.setHeartbeatIntervalSec(parseInt(readEnv("AGENT_HEARTBEAT_INTERVAL_SEC", null), config.getHeartbeatIntervalSec()));
        config.setJitterSec(parseInt(readEnv("AGENT_JITTER_SEC", null), config.getJitterSec()));
        config.setUpdatePeerPort(parseInt(readEnv("AGENT_UPDATE_PEER_PORT", null), config.getUpdatePeerPort()));
        // Overrides for cloned-image scenarios
        config.setOverrideMacAddress(readEnv("AGENT_OVERRIDE_MAC", config.getOverrideMacAddress()));
        config.setOverrideHostname(readEnv("AGENT_OVERRIDE_HOSTNAME", config.getOverrideHostname()));
        config.setOverrideIpAddress(readEnv("AGENT_OVERRIDE_IP", config.getOverrideIpAddress()));
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

    private Path resolveLaunchDirectory() {
        try {
            Path codeSourcePath = Path.of(
                    AgentConfigLoader.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
            return Files.isDirectory(codeSourcePath)
                    ? codeSourcePath
                    : codeSourcePath.getParent();
        } catch (Exception ex) {
            return null;
        }
    }
}
