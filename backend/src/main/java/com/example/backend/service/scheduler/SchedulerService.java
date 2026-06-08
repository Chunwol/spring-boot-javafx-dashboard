package com.example.backend.service.scheduler;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.alert.OperationalAlertRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.status.OperationalMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/**
 * 주기적 스캐너 서비스.
 * <p>
 * 담당 업무:
 * <ul>
 *   <li>1분마다 최근 5분 트래픽 이상(임계치 초과) PC 감지 → AlertEvent 발행</li>
 *   <li>1분마다 ONLINE 상태인데 last_seen이 timeout 초과된 PC → OFFLINE 전환 + AlertEvent</li>
 *   <li>1분마다 DELIVERED/ACKED 상태에서 타임아웃된 명령 재시도(retryStaleCommands)</li>
 *   <li>1분마다 시스템 부하 지표 모니터링(monitorSystemPressure)</li>
 *   <li>2분마다 SCMATE 초기화 여부 감지(monitorScmateInitialization)</li>
 * </ul>
 * </p>
 *
 * <b>AtomicBoolean 사용 주의:</b>
 * 각 스케줄러 메서드는 {@code @Transactional} 없이 AtomicBoolean으로 중복 실행을 방지한다.
 * {@code @Transactional} + {@code finally { atomicBoolean.set(false) }} 를 함께 쓰면,
 * finally 블록이 트랜잭션 커밋 전에 실행되어 다른 스레드가 조기 진입할 수 있는
 * race condition이 발생한다. JPA repository 각 호출은 개별 트랜잭션으로 처리된다.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TrafficLogsRepository trafficRepo;
    private final PcMasterRepository pcRepo;
    private final CommandQueueRepository commandQueueRepository;
    private final OperationalAlertRepository operationalAlertRepository;
    private final OperationalMetricsService operationalMetricsService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** application.yml: app.traffic.alert-threshold-mbps (기본 100) */
    @Value("${app.traffic.alert-threshold-mbps:100}")
    private long alertThresholdMbps;

    /** application.yml: app.heartbeat.timeout-minutes (기본 5) */
    @Value("${app.heartbeat.timeout-minutes:5}")
    private long heartbeatTimeoutMinutes;

    /** 전달/실행 타임아웃 후 재큐잉까지 대기시간(초) */
    @Value("${app.command.timeout-seconds:45}")
    private long commandTimeoutSeconds;

    /** 명령 최대 재시도 횟수 */
    @Value("${app.command.max-retries:3}")
    private int maxCommandRetries;

    @Value("${app.command.verify-offline-timeout-seconds:120}")
    private long commandVerifyOfflineTimeoutSeconds;

    @Value("${app.command.verify-offline-silence-seconds:40}")
    private long commandVerifyOfflineSilenceSeconds;

    @Value("${app.command.verify-online-timeout-seconds:240}")
    private long commandVerifyOnlineTimeoutSeconds;

    @Value("${app.metrics.alert.command-failure-rate:0.25}")
    private double metricsCommandFailureRateThreshold;

    @Value("${app.metrics.alert.transfer-failure-rate:0.25}")
    private double metricsTransferFailureRateThreshold;

    @Value("${app.metrics.alert.request-error-rate:0.15}")
    private double metricsRequestErrorRateThreshold;

    @Value("${app.metrics.alert.forbidden-rate:0.40}")
    private double metricsForbiddenRateThreshold;

    @Value("${app.metrics.alert.ack-p95-ms:20000}")
    private double metricsAckP95ThresholdMs;

    @Value("${app.metrics.alert.result-p95-ms:45000}")
    private double metricsResultP95ThresholdMs;

    @Value("${app.metrics.alert.online-ratio-min:0.70}")
    private double minOnlineRatioThreshold;

    @Value("${app.metrics.alert.window-seconds:300}")
    private int metricsAlertWindowSeconds;

    @Value("${app.metrics.alert.min-command-samples:10}")
    private long metricsMinCommandSamples;

    @Value("${app.metrics.alert.min-transfer-samples:10}")
    private long metricsMinTransferSamples;

    @Value("${app.metrics.alert.min-request-samples:20}")
    private long metricsMinRequestSamples;

    /** 중복 알림 방지: key(MAC 또는 SYSTEM:rule) → 마지막 알림 시각 */
    private final Map<String, LocalDateTime> lastAlertTime = new ConcurrentHashMap<>();
    private final AtomicBoolean trafficScanRunning = new AtomicBoolean(false);
    private final AtomicBoolean offlineScanRunning = new AtomicBoolean(false);
    private final AtomicBoolean staleCommandRetryRunning = new AtomicBoolean(false);
    private final AtomicBoolean systemPressureMonitorRunning = new AtomicBoolean(false);
    private final AtomicBoolean scmateMonitorRunning = new AtomicBoolean(false);
    private final AtomicBoolean onlineRatioAlertActive = new AtomicBoolean(false);

    public SchedulerService(TrafficLogsRepository trafficRepo,
                            PcMasterRepository pcRepo,
                            CommandQueueRepository commandQueueRepository,
                            OperationalAlertRepository operationalAlertRepository,
                            OperationalMetricsService operationalMetricsService,
                            ApplicationEventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.trafficRepo = trafficRepo;
        this.pcRepo = pcRepo;
        this.commandQueueRepository = commandQueueRepository;
        this.operationalAlertRepository = operationalAlertRepository;
        this.operationalMetricsService = operationalMetricsService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // ─── 트래픽 이상 감지 (1분 주기) ────────────────────────────────────
    @Scheduled(fixedRateString = "${scheduler.fixed-rate:60000}",
               initialDelayString = "${scheduler.initial-delay:5000}")
    public void scanForTrafficAnomalies() {
        if (!trafficScanRunning.compareAndSet(false, true)) {
            log.debug("Skip scanForTrafficAnomalies: previous run still in progress");
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(5);
            long thresholdKbps = alertThresholdMbps * 1000L; // Mbps → Kbps

            List<TrafficLog> recentLogs = trafficRepo.findByTimestampBetween(since, LocalDateTime.now());
            for (TrafficLog tlog : recentLogs) {
                long inKbps = tlog.getInboundKbps() != null ? tlog.getInboundKbps() : 0L;
                if (inKbps > thresholdKbps) {
                    String mac = tlog.getMacAddress();
                    if (isAlertCooldownPassed(mac)) {
                        String msg = String.format(
                                "[트래픽 경고] MAC=%s 인바운드 %.1f Mbps (임계치: %d Mbps) at %s",
                                mac, inKbps / 1000.0, alertThresholdMbps, tlog.getTimestamp());
                        log.warn(msg);
                        eventPublisher.publishEvent(new AlertEvent(this, mac, msg, "WARNING", "TRAFFIC_HIGH"));
                        lastAlertTime.put(mac, LocalDateTime.now());
                    }
                }
            }
        } finally {
            trafficScanRunning.set(false);
        }
    }

    // ─── 오프라인 감지 (기본 1분 주기, 환경변수로 조절 가능) ───────────────
    //
    // WHY: @Transactional을 제거한 이유 —
    // @Transactional + finally { atomicBoolean.set(false) } 조합은 finally가 트랜잭션 커밋
    // 전에 실행되어 다른 스레드가 락을 조기에 획득할 수 있다.
    // 실제 DB 쓰기는 pcRepo.save()의 자체 트랜잭션으로 각각 처리된다.
    /**
     * ONLINE 상태인데 last_seen이 heartbeatTimeoutMinutes를 초과한 PC를 OFFLINE으로 전환한다.
     * 동일 PC에 대해 10분 내 중복 알림은 억제한다.
     */
    @Scheduled(
            fixedRateString = "${app.heartbeat.offline-scan-fixed-rate-ms:60000}",
            initialDelayString = "${app.heartbeat.offline-scan-initial-delay-ms:30000}")
    public void scanForOfflinePcs() {
        if (!offlineScanRunning.compareAndSet(false, true)) {
            log.debug("Skip scanForOfflinePcs: previous run still in progress");
            return;
        }
        try {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(heartbeatTimeoutMinutes);

            // 1) 알림 발행을 위해 대상 PC 목록 먼저 조회 (읽기 전용)
            List<PcMaster> timedOutPcs = pcRepo.findOnlinePcsLastSeenBefore(threshold);
            if (timedOutPcs.isEmpty()) return;

            // 2) N × save() 대신 단일 UPDATE 쿼리로 일괄 전환
            //    WHY: 800대 환경에서 네트워크 장애 직후 수백 건이 동시에 OFFLINE 전환될 때
            //         개별 save()는 커넥션 풀 포화와 DB 스파이크를 유발한다.
            int updated = pcRepo.markOfflineByLastSeenBefore(threshold);
            log.info("[OFFLINE 일괄 전환] {}대 OFFLINE 처리 완료", updated);

            // 3) AlertEvent는 미리 읽어둔 목록으로 발행 (DB 재조회 없음)
            for (PcMaster pc : timedOutPcs) {
                log.info("[OFFLINE 감지] {} ({}) - 마지막 하트비트: {}",
                        pc.getPcName(), pc.getMacAddress(), pc.getLastSeen());
                if (isAlertCooldownPassed(pc.getMacAddress())) {
                    String msg = String.format(
                            "[PC 오프라인] %s (%s) - 마지막 접속: %s",
                            pc.getPcName(), pc.getMacAddress(), pc.getLastSeen());
                    eventPublisher.publishEvent(new AlertEvent(this, pc.getMacAddress(), msg, "WARNING", "PC_OFFLINE"));
                    lastAlertTime.put(pc.getMacAddress(), LocalDateTime.now());
                }
            }
        } finally {
            offlineScanRunning.set(false);
        }
    }

    // ─── 명령 타임아웃 재시도 (1분 주기) ───────────────────────────────────
    //
    // WHY: @Transactional 제거 — AtomicBoolean과의 race condition 방지.
    // 실제 DB 접근(reconcileStaleCommandsNow)은 자체 @Transactional로 처리된다.
    /**
     * DELIVERED/ACKED 상태에서 commandTimeoutSeconds를 초과한 명령을 재큐잉하거나 FAILED 처리한다.
     * 최대 재시도 횟수(maxCommandRetries)에 도달하면 FAILED로 확정한다.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 20_000)
    public void retryStaleCommands() {
        if (!staleCommandRetryRunning.compareAndSet(false, true)) {
            log.debug("Skip retryStaleCommands: previous run still in progress");
            return;
        }
        try {
            reconcileStaleCommandsNow();
        } finally {
            staleCommandRetryRunning.set(false);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void reconcileStaleCommandsNow() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(commandTimeoutSeconds);

        // DELIVERED 상태에서 ACK 누락된 명령
        List<CommandQueue> staleDelivered = commandQueueRepository
            .findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc("DELIVERED", threshold);
        staleDelivered.forEach(this::handleRetryOrFail);

        // ACKED 상태에서 RESULT 누락된 명령
        List<CommandQueue> staleAcked = commandQueueRepository
            .findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc("ACKED", threshold);
        staleAcked.forEach(this::handleRetryOrFail);

        reconcilePowerStateVerificationNow();
    }

    @org.springframework.transaction.annotation.Transactional
    public void reconcilePowerStateVerificationNow() {
        LocalDateTime now = LocalDateTime.now();
        List<CommandQueue> pendingVerifications = commandQueueRepository
                .findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                        List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_REBOOT_CONFIRM"));

        for (CommandQueue cmd : pendingVerifications) {
            PcMaster pc = pcRepo.findById(cmd.getMacAddress()).orElse(null);
            if ("PENDING_OFFLINE".equals(cmd.getVerificationStatus())) {
                if (isOfflineObserved(pc, now)) {
                    cmd.setVerificationObservedOfflineAt(now);
                    if ("REBOOT".equalsIgnoreCase(cmd.getCommandType())) {
                        cmd.setVerificationStatus("PENDING_ONLINE");
                        cmd.setVerificationDeadlineAt(now.plusSeconds(commandVerifyOnlineTimeoutSeconds));
                    } else {
                        cmd.setVerificationStatus("CONFIRMED");
                        cmd.setVerificationCompletedAt(now);
                    }
                    commandQueueRepository.save(cmd);
                    continue;
                }
                if (isDeadlineExceeded(cmd, now)) {
                    markVerificationFailed(cmd, now,
                            "power-state verification failed: device did not go offline");
                }
                continue;
            }

            if ("PENDING_REBOOT_CONFIRM".equals(cmd.getVerificationStatus())) {
                if (isAgentUpdateRebootConfirmed(pc, cmd)) {
                    cmd.setVerificationStatus("CONFIRMED");
                    cmd.setVerificationCompletedAt(now);
                    commandQueueRepository.save(cmd);
                    operationalAlertRepository.acknowledgeUnreadBySourceAndType(
                            cmd.getMacAddress(),
                            "UPDATE_REBOOT_REQUIRED",
                            now,
                            "system-auto");
                    continue;
                }
                if (isDeadlineExceeded(cmd, now)) {
                    cmd.setVerificationStatus("FAILED");
                    cmd.setVerificationCompletedAt(now);
                    cmd.setStderr(appendMessage(cmd.getStderr(),
                            "update reboot/version verification failed: confirmation deadline exceeded"));
                    commandQueueRepository.save(cmd);
                }
                continue;
            }

            if ("PENDING_ONLINE".equals(cmd.getVerificationStatus())) {
                if (isOnlineObserved(pc, cmd, now)) {
                    cmd.setVerificationStatus("CONFIRMED");
                    cmd.setVerificationCompletedAt(now);
                    commandQueueRepository.save(cmd);
                    continue;
                }
                if (isDeadlineExceeded(cmd, now)) {
                    markVerificationFailed(cmd, now,
                            "power-state verification failed: device did not come back online after reboot");
                }
            }
        }
    }

    public void armPostExecutionVerification(CommandQueue cmd, LocalDateTime now) {
        clearVerification(cmd);
        if (!isPowerStateCommand(cmd)) {
            return;
        }
        if (!"COMPLETED".equalsIgnoreCase(cmd.getStatus()) || cmd.getExitCode() == null || cmd.getExitCode() != 0) {
            return;
        }
        cmd.setVerificationStatus("PENDING_OFFLINE");
        cmd.setVerificationStartedAt(now);
        cmd.setVerificationDeadlineAt(now.plusSeconds(commandVerifyOfflineTimeoutSeconds));
    }

    // ─── 실시간 부하/문제 신호 모니터 (1분 주기) ───────────────────────────
    @Scheduled(fixedRate = 60_000, initialDelay = 15_000)
    public void monitorSystemPressure() {
        if (!systemPressureMonitorRunning.compareAndSet(false, true)) {
            log.debug("Skip monitorSystemPressure: previous run still in progress");
            return;
        }
        try {
            long queued = commandQueueRepository.countByStatus("QUEUED");
            long staleDelivered = commandQueueRepository.countByStatusAndDeliveredAtBefore(
                    "DELIVERED", LocalDateTime.now().minusSeconds(commandTimeoutSeconds));
            long staleAcked = commandQueueRepository.countByStatusAndAckedAtBefore(
                    "ACKED", LocalDateTime.now().minusSeconds(commandTimeoutSeconds));

            if (queued > 200) {
                log.warn("[PRESSURE] command queue backlog HIGH: queued={}", queued);
            } else if (queued > 80) {
                log.info("[PRESSURE] command queue backlog MEDIUM: queued={}", queued);
            }

            long staleTotal = staleDelivered + staleAcked;
            if (staleTotal > 30) {
                log.warn("[PRESSURE] stale command HIGH: staleDelivered={}, staleAcked={}", staleDelivered, staleAcked);
            } else if (staleTotal > 10) {
                log.info("[PRESSURE] stale command MEDIUM: staleDelivered={}, staleAcked={}", staleDelivered, staleAcked);
            }

            publishOperationalMetricsAlerts();
            publishOnlineRatioAlert();
        } finally {
            systemPressureMonitorRunning.set(false);
        }
    }

    // ─── SCMATE 초기화 감시 (2분 주기) ────────────────────────────
    //
    // WHY: @Transactional 제거 — AtomicBoolean과의 race condition 방지.
    // commandQueueRepository.save() 호출이 각 루프 내에서 자체 트랜잭션으로 처리된다.
    /**
     * 관리 작업(파일 설치, 에이전트 업데이트) ACK 완료 후 PC가 갑자기 OFFLINE이 된 경우
     * SCMATE 초기화를 의심하고 AlertEvent를 발행한다.
     * ACK 후 2분 이내에 OFFLINE된 경우만 감지 대상으로 삼는다.
     */
    @Scheduled(fixedRate = 120_000, initialDelay = 30_000)
    public void monitorScmateInitialization() {
        if (!scmateMonitorRunning.compareAndSet(false, true)) {
            log.debug("Skip monitorScmateInitialization: previous run still in progress");
            return;
        }
        try {
            // 관리 작업(파일 설치, 에이전트 업데이트) 명령이 ACK까지 받았는데
            // PC가 갑자기 OFFLINE이 되면 = SCMATE 초기화 의심
            LocalDateTime recentThreshold = LocalDateTime.now().minusMinutes(5);

            // ACK 받은 관리 작업 명령들 조사 (완료되지 않은 것)
            List<CommandQueue> adminCommandsAcked = commandQueueRepository
                    .findByStatusAndIsAdminOperationAndAckedAtAfter("ACKED", true, recentThreshold);

            for (CommandQueue cmd : adminCommandsAcked) {
                // 이미 SCMATE 복구 중이면 skip
                if ("PENDING".equals(cmd.getScmateResetStatus())) {
                    continue;
                }

                String mac = cmd.getMacAddress();
                Optional<PcMaster> pcOpt = pcRepo.findById(mac);

                if (pcOpt.isPresent()) {
                    PcMaster pc = pcOpt.get();
                    // ACK는 받았는데 PC가 OFFLINE 상태 = SCMATE 초기화 의심
                    if ("OFFLINE".equalsIgnoreCase(pc.getStatus())) {
                        long ackedSeconds = java.time.temporal.ChronoUnit.SECONDS
                                .between(cmd.getAckedAt(), LocalDateTime.now());

                        // ACK 받은지 2분 이내에 OFFLINE된 경우만 감지
                        if (ackedSeconds < 120) {
                            log.warn("[SCMATE 초기화 감지] {} ({}) - 관리 작업 완료 직후 연결 끊김 ({}초)",
                                    pc.getPcName(), mac, ackedSeconds);

                            // SCMATE 복구 상태 설정
                            cmd.setScmateResetStatus("PENDING");
                            cmd.setScmateResetRequestedAt(LocalDateTime.now());
                            commandQueueRepository.save(cmd);

                            if (isAlertCooldownPassed(mac)) {
                                String scmateMsg = String.format(
                                        "[SCMATE 초기화 감지] %s (%s) - 에이전트 업데이트/파일 설치 완료 직후 연결 끊김. "
                                                + "PC의 SCMATE 스냅샷을 재설정하고 PC를 재부팅하세요. (명령 ID: %d)",
                                        pc.getPcName(), mac, cmd.getTaskId());
                                eventPublisher.publishEvent(new AlertEvent(this, mac, scmateMsg, "WARNING", "SCMATE"));
                                lastAlertTime.put(mac, LocalDateTime.now());
                            }
                        }
                    }
                }
            }
        } finally {
            scmateMonitorRunning.set(false);
        }
    }

    private void handleRetryOrFail(CommandQueue cmd) {
        // ACKED 상태(에이전트가 이미 실행 시작)에서 timeout된 전원 제어 명령은
        // 에이전트가 실행 직후 종료되어 결과를 보고할 수 없으므로 power-state 검증으로 전환
        if ("ACKED".equals(cmd.getStatus()) && isPowerCommand(cmd.getCommandType())) {
            LocalDateTime now = LocalDateTime.now();
            log.info("[CMD POWER-VERIFY] taskId={} mac={} type={} → power-state 검증으로 전환",
                    cmd.getTaskId(), cmd.getMacAddress(), cmd.getCommandType());
            armPostExecutionVerification(cmd, now);
            commandQueueRepository.save(cmd);
            return;
        }

        int retryCount = cmd.getRetryCount() == null ? 0 : cmd.getRetryCount();
        if (retryCount >= maxCommandRetries) {
            cmd.setStatus("FAILED");
            cmd.setIsExecuted(true);
            cmd.setCompletedAt(LocalDateTime.now());
            cmd.setStderr("command timeout exceeded max retries");
            commandQueueRepository.save(cmd);
            log.warn("[CMD FAIL] taskId={} mac={} retries={} (max)",
                    cmd.getTaskId(), cmd.getMacAddress(), retryCount);
            return;
        }

        cmd.setRetryCount(retryCount + 1);
        cmd.setStatus("QUEUED");
        cmd.setDeliveredAt(null);
        cmd.setAckedAt(null);
        cmd.setCompletedAt(null);
        cmd.setIsExecuted(false);
        clearVerification(cmd);
        commandQueueRepository.save(cmd);
        log.info("[CMD RETRY] taskId={} mac={} retryCount={}",
                cmd.getTaskId(), cmd.getMacAddress(), cmd.getRetryCount());
    }

    /** 에이전트가 실행 즉시 종료되어 결과 보고가 불가능한 전원 명령 여부. */
    private boolean isPowerCommand(String type) {
        if (type == null) return false;
        return switch (type.toUpperCase()) {
            case "SHUTDOWN", "REBOOT", "RESTART", "PCON", "LOGOFF", "SLEEP", "HIBERNATE" -> true;
            default -> false;
        };
    }

    private boolean isPowerStateCommand(CommandQueue cmd) {
        if (cmd == null || cmd.getCommandType() == null) {
            return false;
        }
        String type = cmd.getCommandType().trim().toUpperCase(java.util.Locale.ROOT);
        return "SHUTDOWN".equals(type) || "REBOOT".equals(type);
    }

    private boolean isOfflineObserved(PcMaster pc, LocalDateTime now) {
        if (pc == null) {
            return true;
        }
        if ("OFFLINE".equalsIgnoreCase(pc.getStatus())) {
            return true;
        }
        if (pc.getLastSeen() == null) {
            return true;
        }
        return pc.getLastSeen().isBefore(now.minusSeconds(commandVerifyOfflineSilenceSeconds));
    }

    private boolean isOnlineObserved(PcMaster pc, CommandQueue cmd, LocalDateTime now) {
        if (pc == null || !"ONLINE".equalsIgnoreCase(pc.getStatus()) || pc.getLastSeen() == null) {
            return false;
        }
        LocalDateTime threshold = cmd.getVerificationObservedOfflineAt() != null
                ? cmd.getVerificationObservedOfflineAt()
                : now.minusSeconds(commandVerifyOfflineSilenceSeconds);
        return !pc.getLastSeen().isBefore(threshold);
    }

    private boolean isAgentUpdateRebootConfirmed(PcMaster pc, CommandQueue cmd) {
        if (pc == null || cmd == null) {
            return false;
        }

        String targetVersion = extractPayloadText(cmd.getPayloadJson(), "targetVersion");
        if (targetVersion != null && !targetVersion.isBlank()) {
            String currentVersion = pc.getAgentVersion();
            if (currentVersion == null || !targetVersion.equalsIgnoreCase(currentVersion.trim())) {
                return false;
            }
        }

        String previousSessionId = extractPayloadText(cmd.getPayloadJson(), "preUpdateSessionId");
        if (previousSessionId != null && !previousSessionId.isBlank()) {
            String currentSessionId = pc.getLastAgentSessionId();
            return currentSessionId != null && !currentSessionId.isBlank()
                    && !previousSessionId.equals(currentSessionId);
        }

        if (pc.getLastSeen() == null) {
            return false;
        }
        LocalDateTime baseline = cmd.getCompletedAt() != null
                ? cmd.getCompletedAt()
                : cmd.getVerificationStartedAt();
        return baseline == null || !pc.getLastSeen().isBefore(baseline);
    }

    private boolean isDeadlineExceeded(CommandQueue cmd, LocalDateTime now) {
        return cmd.getVerificationDeadlineAt() != null && now.isAfter(cmd.getVerificationDeadlineAt());
    }

    private String extractPayloadText(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node == null || !node.has(field) || node.get(field).isNull()) {
                return null;
            }
            String value = node.get(field).asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private void markVerificationFailed(CommandQueue cmd, LocalDateTime now, String message) {
        cmd.setStatus("FAILED");
        cmd.setIsExecuted(true);
        cmd.setVerificationStatus("FAILED");
        cmd.setVerificationCompletedAt(now);
        cmd.setCompletedAt(now);
        cmd.setStderr(appendMessage(cmd.getStderr(), message));
        commandQueueRepository.save(cmd);
        log.warn("[CMD VERIFY FAIL] taskId={} mac={} message={}", cmd.getTaskId(), cmd.getMacAddress(), message);
    }

    private String appendMessage(String current, String message) {
        if (current == null || current.isBlank()) {
            return message;
        }
        if (current.contains(message)) {
            return current;
        }
        return current + System.lineSeparator() + message;
    }

    private void clearVerification(CommandQueue cmd) {
        cmd.setVerificationStatus(null);
        cmd.setVerificationStartedAt(null);
        cmd.setVerificationDeadlineAt(null);
        cmd.setVerificationObservedOfflineAt(null);
        cmd.setVerificationCompletedAt(null);
    }

    /** 동일 MAC에 대해 10분 이내 중복 알림 방지 */
    private boolean isAlertCooldownPassed(String mac) {
        LocalDateTime last = lastAlertTime.get(mac);
        return last == null || last.isBefore(LocalDateTime.now().minusMinutes(10));
    }

    @SuppressWarnings("unchecked")
    private void publishOperationalMetricsAlerts() {
        Map<String, Object> snapshot = operationalMetricsService.snapshotRecent(metricsAlertWindowSeconds);
        Map<String, Object> latency = toMap(snapshot.get("latency"));
        Map<String, Object> transfer = toMap(snapshot.get("transfer"));
        Map<String, Object> request = toMap(snapshot.get("request"));
        int windowSeconds = (int) toLong(snapshot.get("windowSeconds"));

        double commandFailureRate = toDouble(latency.get("failureRate"));
        double ackP95Ms = toDouble(latency.get("ackP95Ms"));
        double resultP95Ms = toDouble(latency.get("resultP95Ms"));
        double transferFailureRate = toDouble(transfer.get("failureRate"));
        double requestErrorRate = toDouble(request.get("errorRate"));
        double forbiddenRate = toDouble(request.get("forbiddenRate"));
        long commandSampleCount = toLong(latency.get("totalCount"));
        long ackSampleCount = toLong(latency.get("ackSampleCount"));
        long resultSampleCount = toLong(latency.get("resultSampleCount"));
        long transferSampleCount = toLong(transfer.get("totalRequests"));
        long requestSampleCount = toLong(request.get("totalRequests"));

        if (commandSampleCount >= metricsMinCommandSamples && commandFailureRate >= metricsCommandFailureRateThreshold) {
            publishSystemAlert(
                    "metrics-command-failure",
                    String.format("[운영 메트릭] 최근 %ds 명령 실패율 급증 %.1f%% (임계치 %.1f%%, 샘플 %d건)",
                            windowSeconds, commandFailureRate * 100.0, metricsCommandFailureRateThreshold * 100.0, commandSampleCount),
                    "CRITICAL",
                    "METRICS_COMMAND_FAILURE_RATE");
        }

        if (transferSampleCount >= metricsMinTransferSamples
                && transferFailureRate >= metricsTransferFailureRateThreshold) {
            publishSystemAlert(
                    "metrics-transfer-failure",
                    String.format("[운영 메트릭] 최근 %ds 파일 전송 실패율 급증 %.1f%% (임계치 %.1f%%, 샘플 %d건)",
                            windowSeconds, transferFailureRate * 100.0, metricsTransferFailureRateThreshold * 100.0, transferSampleCount),
                    "CRITICAL",
                    "METRICS_TRANSFER_FAILURE_RATE");
        }

        if (ackSampleCount >= metricsMinCommandSamples && ackP95Ms >= metricsAckP95ThresholdMs) {
            publishSystemAlert(
                    "metrics-ack-latency",
                    String.format("[운영 메트릭] 최근 %ds ACK 지연 P95 %.0fms (임계치 %.0fms, 샘플 %d건)",
                            windowSeconds, ackP95Ms, metricsAckP95ThresholdMs, ackSampleCount),
                    "WARNING",
                    "METRICS_ACK_LATENCY");
        }

        if (resultSampleCount >= metricsMinCommandSamples && resultP95Ms >= metricsResultP95ThresholdMs) {
            publishSystemAlert(
                    "metrics-result-latency",
                    String.format("[운영 메트릭] 최근 %ds 결과 지연 P95 %.0fms (임계치 %.0fms, 샘플 %d건)",
                            windowSeconds, resultP95Ms, metricsResultP95ThresholdMs, resultSampleCount),
                    "WARNING",
                    "METRICS_RESULT_LATENCY");
        }

        if (requestSampleCount >= metricsMinRequestSamples && requestErrorRate >= metricsRequestErrorRateThreshold) {
            publishSystemAlert(
                    "metrics-request-error",
                    String.format("[운영 메트릭] 최근 %ds API 에러율 급증 %.1f%% (임계치 %.1f%%, 샘플 %d건)",
                            windowSeconds, requestErrorRate * 100.0, metricsRequestErrorRateThreshold * 100.0, requestSampleCount),
                    "WARNING",
                    "METRICS_REQUEST_ERROR_RATE");
        }

        if (requestSampleCount >= metricsMinRequestSamples && forbiddenRate >= metricsForbiddenRateThreshold) {
            publishSystemAlert(
                    "metrics-forbidden-rate",
                    String.format("[운영 메트릭] 최근 %ds 권한 거부(403) 비율 상승 %.1f%% (임계치 %.1f%%, 샘플 %d건)",
                            windowSeconds, forbiddenRate * 100.0, metricsForbiddenRateThreshold * 100.0, requestSampleCount),
                    "INFO",
                    "METRICS_FORBIDDEN_RATE");
        }
    }

    private void publishOnlineRatioAlert() {
        long total = pcRepo.count();
        long online = pcRepo.countByStatus("ONLINE");
        if (total <= 0) {
            onlineRatioAlertActive.set(false);
            return;
        }

        double onlineRatio = (double) online / (double) total;
        if (onlineRatio >= minOnlineRatioThreshold) {
            onlineRatioAlertActive.set(false);
            return;
        }
        if (!onlineRatioAlertActive.compareAndSet(false, true)) {
            return;
        }

        publishSystemAlert(
                "metrics-online-ratio",
                String.format("[운영 메트릭] 온라인 에이전트 비율 저하 %.1f%% (%d/%d, 임계치 %.1f%%)",
                        onlineRatio * 100.0, online, total, minOnlineRatioThreshold * 100.0),
                "CRITICAL",
                "METRICS_ONLINE_RATIO_DROP");
    }

    private void publishSystemAlert(String dedupeKey,
                                    String message,
                                    String severity,
                                    String alertType) {
        String key = "SYSTEM:" + dedupeKey;
        if (!isAlertCooldownPassed(key)) {
            return;
        }

        eventPublisher.publishEvent(new AlertEvent(this, "SYSTEM", message, severity, alertType));
        lastAlertTime.put(key, LocalDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
