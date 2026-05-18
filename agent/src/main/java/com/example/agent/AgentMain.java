package com.example.agent;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.config.AgentConfigValidator;
import com.example.agent.hardware.HardwareCollector;
import com.example.agent.heartbeat.HeartbeatSender;
import com.example.agent.update.OtaUpdater;
import com.example.agent.update.UpdateCacheServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * PC 관제 에이전트 엔트리포인트.
 *
 * 실행 흐름:
 *   1. config.json 로드
 *   2. 인터넷 연결 확인
 *   3. 서버 health check
 *   3. agentToken이 없으면 자동 등록 (POST /api/v1/agent/register)
 *   4. 하트비트 스케줄러 시작 (heartbeatIntervalSec ± jitter)
 *   5. 하트비트 응답에서 명령 수신 → CommandExecutor에 위임
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
    private static final int SERVER_INITIAL_RETRY = 5;
    private static final long SERVER_RETRY_SLEEP_MS = 10_000L;
    private static final long SERVER_BACKOFF_MS = 10 * 60 * 1000L;
    private static final long INTERNET_RETRY_MS = 30_000L;
    private static AgentConfig agentConfig; // OTA, 인터넷 체크에서 사용

    public static void main(String[] args) throws InterruptedException {
        log.info("=== PC 관제 에이전트 v1.0.0 시작 ===");

        AgentConfigLoader configLoader = new AgentConfigLoader();
        AgentConfig config = configLoader.load();
            AgentConfigValidator configValidator = new AgentConfigValidator();
            if (!configValidator.validateAndLog(config)) {
                log.error("설정 검증 실패로 에이전트를 시작할 수 없습니다. config.json 또는 환경변수를 확인하세요.");
                System.exit(2);
                return;
            }
        agentConfig = config;

        log.info("서버: {}", config.getServerUrl());
        log.info("config 경로: {}", configLoader.getConfigPath());

        // 시작 시 24시간 이상 된 백그라운드 스크립트 임시 파일 정리
        cleanupOldBgScripts();

        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        // TLS 고정(pin): AGENT_TLS_PIN_SHA256=sha256/xxxxxxxx... 형식
        if (config.getTlsPinSha256() != null && !config.getTlsPinSha256().isBlank()) {
            try {
                URI uri = URI.create(config.getServerUrl());
                String host = uri.getHost();
                if (host != null && !host.isBlank()) {
                    CertificatePinner pinner = new CertificatePinner.Builder()
                            .add(host, config.getTlsPinSha256().trim())
                            .build();
                    httpBuilder.certificatePinner(pinner);
                    log.info("TLS pinning enabled for host={}", host);
                }
            } catch (Exception e) {
                log.warn("TLS pinning 설정 무시: {}", e.getMessage());
            }
        }

        OkHttpClient httpClient = httpBuilder.build();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HardwareCollector hardware = new HardwareCollector();
        UpdateCacheServer cacheServer = null;
        try {
            Path updateCacheDir = configLoader.getConfigPath().getParent().resolve("update-cache");
            cacheServer = new UpdateCacheServer(updateCacheDir, Math.max(1, config.getUpdatePeerPort()));
            cacheServer.start();
        } catch (Exception ex) {
            log.warn("업데이트 P2P 캐시 서버 시작 실패 (계속 진행): {}", ex.getMessage());
        }
        final UpdateCacheServer updateCacheServer = cacheServer;
        HeartbeatSender heartbeat = new HeartbeatSender(config, configLoader, hardware, httpClient, mapper, updateCacheServer);

        // OTA 업데이터 초기화
        OtaUpdater otaUpdater = new OtaUpdater(httpClient, mapper,
                config.getGithubOwner(), config.getGithubRepo());

        waitUntilServerReachable(heartbeat);

        // 토큰이 없으면 자동 등록
        if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
            log.info("에이전트 토큰 없음 → 자동 등록 시도...");
            boolean registered = false;
            for (int i = 1; i <= 3; i++) {
                if (heartbeat.register()) {
                    registered = true;
                    break;
                }
                log.warn("등록 실패 {}/3. 30초 후 재시도...", i);
                Thread.sleep(30_000);
            }
            if (!registered) {
                log.error("에이전트 등록 실패. 설치키를 확인하세요.");
                System.exit(2);
            }
        }

        // 하트비트 스케줄러
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-scheduler");
            t.setDaemon(false);
            return t;
        });

        // 시작 직후 첫 하트비트를 즉시 보내 ONLINE 상태를 빠르게 반영한다.
        try {
            heartbeat.send();
            log.info("초기 하트비트 전송 완료.");
        } catch (Exception e) {
            log.warn("초기 하트비트 전송 실패: {}", e.getMessage());
        }

        scheduleNextHeartbeat(scheduler, heartbeat, config, otaUpdater, new int[]{0});

        // 종료 훅
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("에이전트 종료 시작...");
            heartbeat.sendOfflineNotice("AGENT_SHUTDOWN");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("스케줄러 10초 내 종료 불가. 강제 종료 중...");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("스케줄러 종료 중 중단됨");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // MEDIUM FIX: Graceful shutdown of command executor
            heartbeat.gracefulShutdown();
            if (updateCacheServer != null) {
                updateCacheServer.stop();
            }
            log.info("에이전트 종료 완료.");
        }));

        log.info("하트비트 스케줄러 시작 (간격: {}s ±{}s)",
                config.getHeartbeatIntervalSec(), config.getJitterSec());

        // 메인 스레드 유지
        Thread.currentThread().join();
    }

    private static void waitUntilServerReachable(HeartbeatSender heartbeat) throws InterruptedException {
        while (true) {
            waitForInternetConnection();

            boolean serverAlive = false;
            for (int i = 1; i <= SERVER_INITIAL_RETRY; i++) {
                if (heartbeat.checkServerAlive()) {
                    serverAlive = true;
                    break;
                }
                log.warn("서버 연결 대기 중... ({}/{})", i, SERVER_INITIAL_RETRY);
                Thread.sleep(SERVER_RETRY_SLEEP_MS);
            }

            if (serverAlive) {
                log.info("서버 연결 확인 완료.");
                return;
            }

            log.warn("서버 연결 5회 실패. 10분 후 다시 확인합니다.");
            Thread.sleep(SERVER_BACKOFF_MS);
        }
    }

    private static void waitForInternetConnection() throws InterruptedException {
        while (!isInternetAvailable()) {
            log.warn("인터넷 연결 없음. 30초 후 재확인합니다.");
            Thread.sleep(INTERNET_RETRY_MS);
        }
    }

    /**
     * TCP 소켓으로 설정된 호스트:포트 연결을 시도한다.
     * config.json의 internetCheckHost/internetCheckPort로 대상 변경 가능.
     */
    private static boolean isInternetAvailable() {
        String host = agentConfig != null ? agentConfig.getInternetCheckHost() : "1.1.1.1";
        int port = agentConfig != null ? agentConfig.getInternetCheckPort() : 53;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * pcagent-bg-scripts 임시 디렉토리에서 24시간 이상 된 파일을 삭제한다.
     * 백그라운드 PS1 실행 후 남겨진 파일이 무한히 누적되지 않도록 한다.
     */
    private static void cleanupOldBgScripts() {
        try {
            Path bgDir = Path.of(System.getProperty("java.io.tmpdir"), "pcagent-bg-scripts");
            if (!Files.exists(bgDir)) return;
            long cutoffMs = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            try (var stream = Files.list(bgDir)) {
                stream.forEach(f -> {
                    try {
                        if (Files.getLastModifiedTime(f).toMillis() < cutoffMs) {
                            Files.deleteIfExists(f);
                        }
                    } catch (IOException e) {
                        log.debug("임시 스크립트 파일 삭제 실패: {}", f);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("임시 스크립트 정리 실패: {}", e.getMessage());
        }
    }

    private static void scheduleNextHeartbeat(
            ScheduledExecutorService scheduler,
            HeartbeatSender heartbeat,
            AgentConfig config,
            OtaUpdater otaUpdater,
            int[] heartbeatCount) {

        int jitter = config.getJitterSec() > 0
                ? ThreadLocalRandom.current().nextInt(-config.getJitterSec(), config.getJitterSec() + 1)
                : 0;
        int delaySeconds = Math.max(10, config.getHeartbeatIntervalSec() + jitter);

        scheduler.schedule(() -> {
            try {
                // 토큰이 초기화(재등록 필요) 되었는지 확인
                if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
                    log.info("토큰 초기화됨 → 재등록 시도 (최대 3회)...");
                    boolean reRegistered = false;
                    for (int i = 1; i <= 3; i++) {
                        if (heartbeat.register()) {
                            reRegistered = true;
                            log.info("재등록 성공.");
                            break;
                        }
                        log.warn("재등록 실패 {}/3", i);
                        if (i < 3) Thread.sleep(10_000);
                    }
                    if (!reRegistered) {
                        log.error("재등록 3회 모두 실패. 다음 하트비트까지 대기.");
                        return; // 토큰 없이 하트비트 전송 시도하지 않음
                    }
                }
                heartbeat.send();

                // 10번째 하트비트마다 OTA 업데이트 체크
                heartbeatCount[0]++;
                if (heartbeatCount[0] % 10 == 0) {
                    try {
                        otaUpdater.checkAndUpdate();
                    } catch (Exception e) {
                        log.debug("OTA 체크 실패 (무시): {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("하트비트 오류: {}", e.getMessage());
            } finally {
                // 다음 하트비트 스케줄링 (재귀 방식으로 jitter 적용)
                scheduleNextHeartbeat(scheduler, heartbeat, config, otaUpdater, heartbeatCount);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
