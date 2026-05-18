package com.example.agent.heartbeat;

import com.example.agent.command.CommandExecutor;
import com.example.agent.command.PendingCommandResultStore;
import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.hardware.HardwareCollector;
import com.example.agent.update.UpdateCacheServer;
import com.example.agent.util.SensitiveDataMasker;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 서버에 하트비트를 전송하고 응답으로 받은 명령을 처리한다.
 */
public class HeartbeatSender {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSender.class);
    private static final int TRAFFIC_SAMPLE_INTERVAL_SECONDS = 2;
    private static final int LEGACY_TRAFFIC_PROBE_HEARTBEATS = 30;
    private static final int ERROR_BODY_LOG_MAX = 300;

    private final AgentConfig config;
    private final AgentConfigLoader configLoader;
    private final HardwareCollector hardware;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final CommandExecutor commandExecutor;
    private final String agentSessionId;
    private final String agentVersion;
    private final ExecutorService commandExecutorService;
    private final ScheduledExecutorService trafficSamplerService;
    private volatile boolean pollerRunning = true;
    private Thread commandPollerThread;
    private final TrafficSampleAggregator trafficSampleAggregator = new TrafficSampleAggregator();
    private volatile boolean forceLegacyTrafficPayload = false;
    private int legacyProbeCountdown = 0;

    public HeartbeatSender(AgentConfig config, AgentConfigLoader configLoader,
                           HardwareCollector hardware, OkHttpClient httpClient,
                           ObjectMapper mapper) {
        this(config, configLoader, hardware, httpClient, mapper, null);
    }

    public HeartbeatSender(AgentConfig config, AgentConfigLoader configLoader,
                           HardwareCollector hardware, OkHttpClient httpClient,
                           ObjectMapper mapper, UpdateCacheServer updateCacheServer) {
        this.config = config;
        this.configLoader = configLoader;
        this.hardware = hardware;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.agentSessionId = UUID.randomUUID().toString();
        this.agentVersion = resolveAgentVersion();
        PendingCommandResultStore pendingResultStore = new PendingCommandResultStore(
            configLoader.getConfigPath().getParent(), mapper);
        this.commandExecutor = new CommandExecutor(config, configLoader, httpClient, mapper, pendingResultStore, updateCacheServer);

        // 파일 전송은 별도 스레드에서 처리 (일반 명령이 블로킹되지 않도록)
        this.commandExecutorService = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "agent-command-executor");
            t.setDaemon(false);
            return t;
        });

        this.trafficSamplerService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-traffic-sampler");
            t.setDaemon(true);
            return t;
        });
        bootstrapTrafficMonitoring();
        bootstrapCommandPolling();
    }

    /**
     * 서버가 살아 있는지 health check.
     * @return true이면 서버 정상
     */
    public boolean checkServerAlive() {
        String primaryUrl = normalizeBaseUrl(config.getServerUrl());
        String fallbackUrl = swapHttpHttps(primaryUrl);
        String primaryError = null;

        try {
            if (requestHealth(primaryUrl)) {
                return true;
            }
        } catch (Exception e) {
            primaryError = e.getMessage();
        }

        if (fallbackUrl != null && !fallbackUrl.equals(primaryUrl)) {
            try {
                if (requestHealth(fallbackUrl)) {
                    log.warn("서버 URL 프로토콜 자동 전환: {} -> {}", primaryUrl, fallbackUrl);
                    config.setServerUrl(fallbackUrl);
                    configLoader.save(config);
                    return true;
                }
            } catch (Exception ignored) {
                // primary error를 유지해 원인 로그를 단순화한다.
            }
        }

        log.warn("서버 health check 실패: {}", primaryError == null ? "unknown" : primaryError);
        String message = primaryError == null ? "" : primaryError.toLowerCase();
        if (primaryUrl.startsWith("https://")
                && (message.contains("ssl") || message.contains("handshake") || message.contains("unrecognized"))) {
            log.warn("프로토콜 불일치 가능성: 현재 serverUrl={} 이지만 백엔드는 HTTP로 실행 중일 수 있습니다.", primaryUrl);
        }
        return false;
    }

    private boolean requestHealth(String baseUrl) throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/actuator/health")
                .get()
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            // HTTP 응답 자체가 오면 서버 도달 가능한 상태로 본다.
            // (보안 정책으로 /actuator/health가 401/403일 수 있음)
            log.debug("서버 health endpoint 응답 코드({}): {}", baseUrl, resp.code());
            return true;
        }
    }

    private String normalizeBaseUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return "http://localhost:8888";
        }
        String trimmed = serverUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String swapHttpHttps(String baseUrl) {
        if (baseUrl.startsWith("https://")) {
            return "http://" + baseUrl.substring("https://".length());
        }
        if (baseUrl.startsWith("http://")) {
            return "https://" + baseUrl.substring("http://".length());
        }
        return null;
    }

    /**
     * 에이전트 최초 등록 (agentToken이 비어 있을 때 호출).
     * 성공 시 config.json에 토큰을 저장한다.
     */
    public boolean register() {
        try {
            String effectiveMac = (config.getOverrideMacAddress() != null && !config.getOverrideMacAddress().isBlank())
                ? config.getOverrideMacAddress().trim()
                : hardware.getMacAddress();
            String effectiveHostname = (config.getOverrideHostname() != null && !config.getOverrideHostname().isBlank())
                ? config.getOverrideHostname().trim()
                : hardware.getHostname();
            String effectiveIp = (config.getOverrideIpAddress() != null && !config.getOverrideIpAddress().isBlank())
                ? config.getOverrideIpAddress().trim()
                : hardware.getIpAddress();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("macAddress", effectiveMac);
            body.put("hostname", effectiveHostname);
            body.put("ipAddress", effectiveIp);
            body.put("osInfo", hardware.getOsInfo());
            body.put("agentVersion", agentVersion);

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
                    JsonNode tokenNode = node.get("agentToken");
                    if (tokenNode == null || tokenNode.isNull()) {
                        log.error("에이전트 등록 응답에 agentToken 필드 없음");
                        return false;
                    }
                    String token = tokenNode.asText();
                    configLoader.saveToken(config, token);
                    // MEDIUM FIX#9: Mask token in logs
                    log.info("에이전트 등록 완료. 토큰 저장됨. (토큰: {})", SensitiveDataMasker.maskToken(token));
                    return true;
                } else {
                    log.error("에이전트 등록 실패: HTTP {}", resp.code());
                    return false;
                }
            }
        } catch (Exception e) {
            // MEDIUM FIX#9: Mask sensitive data in exception messages
            String maskedMsg = SensitiveDataMasker.maskExceptionMessage(e.getMessage());
            log.error("에이전트 등록 예외: {}", maskedMsg);
            return false;
        }
    }

    /**
     * 하트비트 전송 → 응답에서 명령 추출 → CommandExecutor에 위임.
     * 트래픽 샘플들은 수집한 모든 샘플을 배열로 전송해 서버에서 개별 저장한다.
     * 서버가 401을 반환하면 토큰 초기화 (재등록 유도).
     */
    public void send() {
        try {
            commandExecutor.flushPendingResults();

            java.util.List<double[]> trafficSamples = trafficSampleAggregator.consumeSamples();
            String effectiveMac = (config.getOverrideMacAddress() != null && !config.getOverrideMacAddress().isBlank())
                ? config.getOverrideMacAddress().trim()
                : hardware.getMacAddress();
            String effectiveHostname = (config.getOverrideHostname() != null && !config.getOverrideHostname().isBlank())
                ? config.getOverrideHostname().trim()
                : hardware.getHostname();
            String effectiveIp = (config.getOverrideIpAddress() != null && !config.getOverrideIpAddress().isBlank())
                ? config.getOverrideIpAddress().trim()
                : hardware.getIpAddress();
            boolean includeTrafficSamples = shouldUseTrafficSamplesPayload();
            HeartbeatHttpResponse heartbeatResponse = postHeartbeat(buildHeartbeatPayload(
                    effectiveMac, effectiveHostname, effectiveIp, trafficSamples, includeTrafficSamples));

            if ((heartbeatResponse.code == 401 || heartbeatResponse.code == 403)) {
                log.warn("토큰 거부됨 (HTTP {}). 재등록 필요.", heartbeatResponse.code);
                configLoader.saveToken(config, ""); // 토큰 초기화
                return;
            }

            if (heartbeatResponse.code == 400 && includeTrafficSamples) {
                log.warn("하트비트 payload(trafficSamples) 거부됨(HTTP 400). 레거시 payload로 재시도합니다. body={}",
                        truncateForLog(heartbeatResponse.body));
                forceLegacyTrafficPayload = true;
                legacyProbeCountdown = LEGACY_TRAFFIC_PROBE_HEARTBEATS;
                heartbeatResponse = postHeartbeat(buildHeartbeatPayload(
                        effectiveMac, effectiveHostname, effectiveIp, trafficSamples, false));
            } else if (heartbeatResponse.isSuccessful() && includeTrafficSamples && forceLegacyTrafficPayload) {
                // probe가 성공하면 다시 multi-sample payload를 사용한다.
                forceLegacyTrafficPayload = false;
                legacyProbeCountdown = 0;
            }

            if (heartbeatResponse.code == 401 || heartbeatResponse.code == 403) {
                log.warn("토큰 거부됨 (HTTP {}). 재등록 필요.", heartbeatResponse.code);
                configLoader.saveToken(config, "");
                return;
            }

            if (heartbeatResponse.isSuccessful()) {
                JsonNode node = heartbeatResponse.body.isBlank()
                        ? mapper.createObjectNode()
                        : mapper.readTree(heartbeatResponse.body);
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
                log.warn("하트비트 실패: HTTP {} body={}",
                        heartbeatResponse.code, truncateForLog(heartbeatResponse.body));
            }
        } catch (Exception e) {
            log.error("하트비트 예외: {}", SensitiveDataMasker.maskExceptionMessage(e.getMessage()));
        }
    }

    private Map<String, Object> buildHeartbeatPayload(String macAddress,
                                                      String hostname,
                                                      String ipAddress,
                                                      java.util.List<double[]> trafficSamples,
                                                      boolean includeTrafficSamples) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("macAddress", macAddress);
        body.put("hostname", hostname);
        body.put("ipAddress", ipAddress);
        body.put("agentVersion", agentVersion);
        body.put("agentSessionId", agentSessionId);

        double[] latest = trafficSamples.isEmpty()
                ? new double[]{0.0, 0.0}
                : trafficSamples.get(trafficSamples.size() - 1);
        body.put("inboundKbps", latest[0]);
        body.put("outboundKbps", latest[1]);
        if (includeTrafficSamples) {
            body.put("trafficSamples", trafficSamples);
        }
        return body;
    }

    private HeartbeatHttpResponse postHeartbeat(Map<String, Object> body) throws Exception {
        Request req = new Request.Builder()
                .url(config.getServerUrl() + "/api/v1/heartbeat")
                .header("X-Agent-Token", config.getAgentToken())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            return new HeartbeatHttpResponse(resp.code(), resp.isSuccessful(), respBody);
        }
    }

    private boolean shouldUseTrafficSamplesPayload() {
        if (!forceLegacyTrafficPayload) {
            return true;
        }
        if (legacyProbeCountdown > 0) {
            legacyProbeCountdown--;
            return false;
        }
        return true;
    }

    private String truncateForLog(String body) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String trimmed = body.trim();
        return trimmed.length() <= ERROR_BODY_LOG_MAX
                ? trimmed
                : trimmed.substring(0, ERROR_BODY_LOG_MAX) + "...";
    }

    private record HeartbeatHttpResponse(int code, boolean success, String body) {
        private boolean isSuccessful() {
            return success;
        }
    }

    /**
     * 에이전트 종료/재부팅 직전에 오프라인 선신고를 전송한다.
     * 강제 전원 차단/프로세스 강제 종료에서는 전송이 보장되지 않는다.
     */
    public void sendOfflineNotice(String reason) {
        String token = config.getAgentToken();
        if (token == null || token.isBlank()) {
            return;
        }

        try {
            String effectiveMac = (config.getOverrideMacAddress() != null && !config.getOverrideMacAddress().isBlank())
                ? config.getOverrideMacAddress().trim()
                : hardware.getMacAddress();
            String effectiveHostname = (config.getOverrideHostname() != null && !config.getOverrideHostname().isBlank())
                ? config.getOverrideHostname().trim()
                : hardware.getHostname();
            String effectiveIp = (config.getOverrideIpAddress() != null && !config.getOverrideIpAddress().isBlank())
                ? config.getOverrideIpAddress().trim()
                : hardware.getIpAddress();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("macAddress", effectiveMac);
            body.put("hostname", effectiveHostname);
            body.put("ipAddress", effectiveIp);
            body.put("agentSessionId", agentSessionId);
            body.put("agentVersion", agentVersion);
            body.put("reason", (reason == null || reason.isBlank()) ? "AGENT_SHUTDOWN" : reason.trim());

            OkHttpClient shutdownClient = httpClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build();

            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/heartbeat/offline")
                    .header("X-Agent-Token", token)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                            mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .build();

            try (Response resp = shutdownClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.debug("오프라인 선신고 실패: HTTP {}", resp.code());
                } else {
                    log.debug("오프라인 선신고 전송 완료.");
                }
            }
        } catch (Exception e) {
            log.debug("오프라인 선신고 예외 (무시): {}", e.getMessage());
        }
    }

    /**
     * Gracefully shutdown the command executor service.
     * Waits for ongoing tasks to complete before forcing shutdown.
     */
    public void gracefulShutdown() {
        log.info("하트비트 송신자 종료 시작...");
        pollerRunning = false;
        if (commandPollerThread != null) {
            commandPollerThread.interrupt();
        }
        trafficSamplerService.shutdown();
        try {
            if (!trafficSamplerService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("트래픽 샘플러 5초 내 종료 불가. 강제 종료 중...");
                trafficSamplerService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("트래픽 샘플러 종료 중 중단됨. 강제 종료 중...");
            trafficSamplerService.shutdownNow();
            Thread.currentThread().interrupt();
        }

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

    private String resolveAgentVersion() {
        try {
            Package pkg = HeartbeatSender.class.getPackage();
            if (pkg != null) {
                String implementationVersion = pkg.getImplementationVersion();
                if (implementationVersion != null && !implementationVersion.isBlank()) {
                    return implementationVersion.trim();
                }
            }
        } catch (Exception ignored) {
        }

        String systemProp = System.getProperty("pcagent.version");
        if (systemProp != null && !systemProp.isBlank()) {
            return systemProp.trim();
        }

        String envVersion = System.getenv("AGENT_VERSION");
        if (envVersion != null && !envVersion.isBlank()) {
            return envVersion.trim();
        }

        return "dev";
    }

    /**
     * Long-poll 루프: 서버에 연결 유지(최대 12초), 명령 수령 즉시 처리 후 재연결.
     * - 명령 있음 → 즉시 응답 수령 → execute → 재연결
     * - 명령 없음 → 서버가 10초 후 204 반환 → 재연결
     * - 오류 → 3초 대기 후 재연결
     *
     * 800대 기준 10s 타임아웃: ~80 req/s (기존 1s 폴링 800 req/s 대비 10배 감소)
     */
    private void bootstrapCommandPolling() {
        // Long-poll용 전용 클라이언트: 서버 hold 10초 + 여유 2초 = 12초 readTimeout
        OkHttpClient longPollClient = httpClient.newBuilder()
                .readTimeout(12, TimeUnit.SECONDS)
                .build();

        commandPollerThread = new Thread(() -> {
            // 최초 등록 후 2초 대기 (하트비트 먼저 보내고 시작)
            try { Thread.sleep(2_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (pollerRunning && !Thread.currentThread().isInterrupted()) {
                String token = config.getAgentToken();
                if (token == null || token.isBlank()) {
                    // 미등록 상태 — 3초 대기 후 재시도
                    try { Thread.sleep(3_000); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    Request req = new Request.Builder()
                            .url(config.getServerUrl() + "/api/v1/agent/commands/next")
                            .header("X-Agent-Token", token)
                            .get()
                            .build();
                    try (Response resp = longPollClient.newCall(req).execute()) {
                        if (resp.code() == 401 || resp.code() == 403) {
                            log.debug("명령 long-poll 토큰 거부 (HTTP {}). 3초 대기.", resp.code());
                            Thread.sleep(3_000);
                            continue;
                        }
                        if (resp.isSuccessful() && resp.body() != null) {
                            String body = resp.body().string();
                            if (!body.isBlank()) {
                                JsonNode node = mapper.readTree(body);
                                JsonNode cmd = node.get("command");
                                if (cmd != null && !cmd.isNull()) {
                                    long taskId = cmd.has("taskId") ? cmd.get("taskId").asLong() : 0;
                                    log.debug("long-poll 명령 수령 taskId={}", taskId);
                                    commandExecutorService.submit(() -> {
                                        commandExecutor.execute(cmd);
                                        log.debug("long-poll 명령 실행 완료 taskId={}", taskId);
                                    });
                                }
                            }
                        }
                        // 204 또는 빈 응답 → 즉시 재연결 (서버가 10초 잡고 있었음)
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("명령 long-poll 예외, 3초 후 재연결: {}", e.getMessage());
                    try { Thread.sleep(3_000); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "agent-command-poller");
        commandPollerThread.setDaemon(true);
        commandPollerThread.start();
    }

    private void bootstrapTrafficMonitoring() {
        captureTrafficSample();
        trafficSamplerService.scheduleAtFixedRate(
                this::captureTrafficSample,
                TRAFFIC_SAMPLE_INTERVAL_SECONDS,
                TRAFFIC_SAMPLE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void captureTrafficSample() {
        try {
            trafficSampleAggregator.record(hardware.getDeltaKbps());
        } catch (Exception e) {
            log.debug("트래픽 샘플링 실패: {}", e.getMessage());
        }
    }
}
