package com.example.agent.heartbeat;

import com.example.agent.command.CommandExecutor;
import com.example.agent.command.PendingCommandResultStore;
import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.hardware.HardwareCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 서버에 하트비트를 전송하고 응답으로 받은 명령을 처리한다.
 */
public class HeartbeatSender {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSender.class);

    private static final String AGENT_VERSION = "1.0.0";

    private final AgentConfig config;
    private final AgentConfigLoader configLoader;
    private final HardwareCollector hardware;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final CommandExecutor commandExecutor;
    private final String agentSessionId;
    private final ExecutorService commandExecutorService;

    public HeartbeatSender(AgentConfig config, AgentConfigLoader configLoader,
                           HardwareCollector hardware, OkHttpClient httpClient,
                           ObjectMapper mapper) {
        this.config = config;
        this.configLoader = configLoader;
        this.hardware = hardware;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.agentSessionId = UUID.randomUUID().toString();
        PendingCommandResultStore pendingResultStore = new PendingCommandResultStore(
            configLoader.getConfigPath().getParent(), mapper);
        this.commandExecutor = new CommandExecutor(config, configLoader, httpClient, mapper, pendingResultStore);

        // CRITICAL FIX#2: Use bounded thread pool instead of creating new threads on demand
        this.commandExecutorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-command-executor");
            t.setDaemon(false);  // Allow graceful shutdown
            return t;
        });
    }

    /**
     * 서버가 살아 있는지 health check.
     * @return true이면 서버 정상
     */
    public boolean checkServerAlive() {
        try {
            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/actuator/health")
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                // 보안 정책으로 /actuator/health가 401/403일 수 있어도
                // HTTP 응답 자체가 오면 서버 도달은 가능한 상태로 본다.
                if (resp.code() >= 200 && resp.code() < 300) {
                    return true;
                }
                log.info("서버 health endpoint 응답 코드: {} (도달 성공으로 처리)", resp.code());
                return true;
            }
        } catch (Exception e) {
            log.warn("서버 health check 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 에이전트 최초 등록 (agentToken이 비어 있을 때 호출).
     * 성공 시 config.json에 토큰을 저장한다.
     */
    public boolean register() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("macAddress", hardware.getMacAddress());
            body.put("hostname", hardware.getHostname());
            body.put("ipAddress", hardware.getIpAddress());
            body.put("osInfo", hardware.getOsInfo());
            body.put("agentVersion", AGENT_VERSION);

            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/agent/register")
                    .header("X-Agent-Install-Key", config.getInstallKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.code() == 201 && resp.body() != null) {
                    JsonNode node = mapper.readTree(resp.body().string());
                    String token = node.get("agentToken").asText();
                    configLoader.saveToken(config, token);
                    log.info("에이전트 등록 완료. 토큰 저장됨.");
                    return true;
                } else {
                    log.error("에이전트 등록 실패: HTTP {}", resp.code());
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("에이전트 등록 예외: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 하트비트 전송 → 응답에서 명령 추출 → CommandExecutor에 위임.
     * 서버가 401을 반환하면 토큰 초기화 (재등록 유도).
     */
    public void send() {
        try {
            commandExecutor.flushPendingResults();

            double[] kbps = hardware.getDeltaKbps();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("macAddress", hardware.getMacAddress());
            body.put("hostname",   hardware.getHostname());
            body.put("ipAddress",  hardware.getIpAddress());
            body.put("agentVersion", AGENT_VERSION);
            body.put("agentSessionId", agentSessionId);
            body.put("inboundKbps",  kbps[0]);
            body.put("outboundKbps", kbps[1]);

            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/heartbeat")
                    .header("X-Agent-Token", config.getAgentToken())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.code() == 401 || resp.code() == 403) {
                    log.warn("토큰 거부됨 (HTTP {}). 재등록 필요.", resp.code());
                    configLoader.saveToken(config, ""); // 토큰 초기화
                    return;
                }
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonNode node = mapper.readTree(resp.body().string());
                    JsonNode cmd = node.get("command");
                    if (cmd != null && !cmd.isNull()) {
                        long taskId = cmd.has("taskId") ? cmd.get("taskId").asLong() : 0;
                        // CRITICAL FIX#2-2: Use executor service instead of creating new threads
                        // Note: Recursive send() call removed to prevent unbounded recursion and thread accumulation
                        commandExecutorService.submit(() -> {
                            commandExecutor.execute(cmd);
                            log.debug("명령 실행 완료 taskId={}", taskId);
                        });
                    }
                    log.debug("하트비트 전송 완료. 명령 수령: {}", cmd != null && !cmd.isNull());
                } else {
                    log.warn("하트비트 실패: HTTP {}", resp.code());
                }
            }
        } catch (Exception e) {
            log.error("하트비트 예외: {}", e.getMessage());
        }
    }

    /**
     * Gracefully shutdown the command executor service.
     * Waits for ongoing tasks to complete before forcing shutdown.
     */
    public void gracefulShutdown() {
        log.info("하트비트 송신자 종료 시작...");
        commandExecutorService.shutdown();
        try {
            if (!commandExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("명령 실행자 30초 내 종료 불가. 강제 종료 중...");
                commandExecutorService.shutdownNow();
            }
            log.info("하트비트 송신자 종료 완료.");
        } catch (InterruptedException e) {
            log.error("하트비트 송신자 종료 중 중단됨. 강제 종료 중...");
            commandExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
