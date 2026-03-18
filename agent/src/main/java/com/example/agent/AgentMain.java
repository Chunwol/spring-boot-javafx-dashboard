package com.example.agent;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.hardware.HardwareCollector;
import com.example.agent.heartbeat.HeartbeatSender;
import com.example.agent.update.OtaUpdater;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PC 관제 에이전트 엔트리포인트.
 *
 * 실행 흐름:
 *   1. config.json 로드
 *   2. 서버 health check
 *   3. agentToken이 없으면 자동 등록 (POST /api/v1/agent/register)
 *   4. 하트비트 스케줄러 시작 (heartbeatIntervalSec ± jitter)
 *   5. 하트비트 응답에서 명령 수신 → CommandExecutor에 위임
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
    private static final Random RANDOM = new Random();

    public static void main(String[] args) throws InterruptedException {
        log.info("=== PC 관제 에이전트 v1.0.0 시작 ===");

        AgentConfigLoader configLoader = new AgentConfigLoader();
        AgentConfig config = configLoader.load();

        log.info("서버: {}", config.getServerUrl());
        log.info("config 경로: {}", configLoader.getConfigPath());

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HardwareCollector hardware = new HardwareCollector();
        HeartbeatSender heartbeat = new HeartbeatSender(config, configLoader, hardware, httpClient, mapper);

        // 서버 연결 확인 (최대 5회 재시도)
        boolean serverAlive = false;
        for (int i = 1; i <= 5; i++) {
            if (heartbeat.checkServerAlive()) {
                serverAlive = true;
                break;
            }
            log.warn("서버 연결 대기 중... ({}/5)", i);
            Thread.sleep(10_000);
        }
        if (!serverAlive) {
            log.error("서버에 연결할 수 없습니다. 에이전트를 종료합니다.");
            System.exit(1);
        }

            // OTA 자가 업데이트 체크 (서버 연결 확인 후)
            OtaUpdater ota = new OtaUpdater(httpClient, mapper,
                    config.getGithubOwner(), config.getGithubRepo());
            ota.checkAndUpdate(); // 업데이트가 있으면 내부에서 System.exit(0) 호출

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

        scheduleNextHeartbeat(scheduler, heartbeat, config, configLoader, mapper, httpClient, hardware);

        // 종료 훅
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("에이전트 종료.");
            scheduler.shutdownNow();
        }));

        log.info("하트비트 스케줄러 시작 (간격: {}s ±{}s)",
                config.getHeartbeatIntervalSec(), config.getJitterSec());

        // 메인 스레드 유지
        Thread.currentThread().join();
    }

    private static void scheduleNextHeartbeat(
            ScheduledExecutorService scheduler,
            HeartbeatSender heartbeat,
            AgentConfig config,
            AgentConfigLoader configLoader,
            ObjectMapper mapper,
            OkHttpClient httpClient,
            HardwareCollector hardware) {

        int jitter = RANDOM.nextInt(config.getJitterSec() * 2 + 1) - config.getJitterSec();
        int delaySeconds = Math.max(30, config.getHeartbeatIntervalSec() + jitter);

        scheduler.schedule(() -> {
            try {
                // 토큰이 초기화(재등록 필요) 되었는지 확인
                if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
                    log.info("토큰 초기화됨 → 재등록 시도...");
                    heartbeat.register();
                }
                if (!config.getAgentToken().isBlank()) {
                    heartbeat.send();
                }
            } catch (Exception e) {
                log.error("하트비트 오류: {}", e.getMessage());
            } finally {
                // 다음 하트비트 스케줄링 (재귀 방식으로 jitter 적용)
                scheduleNextHeartbeat(scheduler, heartbeat, config, configLoader, mapper, httpClient, hardware);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
