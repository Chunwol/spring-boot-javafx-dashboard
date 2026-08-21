package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.dto.heartbeat.HeartbeatOfflineDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomResolutionService.PcRole;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.realtime.WebSocketService;
import com.example.backend.service.status.OperationalMetricsService;
import com.example.backend.service.traffic.TrafficLogFlushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 에이전트 하트비트 처리 서비스.
 * <p>
 * 하트비트 수신 시 다음 작업을 수행한다:
 * <ol>
 *   <li>PC 상태(IP, hostname, groupId, role, sessionId) 갱신 및 조건부 저장</li>
 *   <li>그룹 재배정 감지 → 감사(Audit) 로그 기록 (트랜잭션 커밋 후 afterCommit에서 실행)</li>
 *   <li>에이전트 세션 변경 감지 → 진행 중인 명령 재큐잉 또는 FAILED 처리</li>
 *   <li>자동 캐치업: 최신 배포 버전과 다른 에이전트를 FileDistributionOrchestrator에 전달</li>
 *   <li>SCMATE 초기화 복구 완료 자동 감지 (OFFLINE→ONLINE 전환 시)</li>
 *   <li>트래픽 샘플 저장 ({@code persistTrafficSamples})</li>
 *   <li>다음 QUEUED 명령 디스패치 ({@code dispatchNextCommand} — CAS로 race condition 방지)</li>
 * </ol>
 * </p>
 *
 * <b>성능 최적화:</b>
 * <ul>
 *   <li>PC 저장: 상태 변경 또는 {@code heartbeatPersistIntervalSeconds} 경과 시에만 DB 쓰기</li>
 *   <li>트래픽 로그: {@code trafficLogMinIntervalSeconds} 경과 시에만 단일값 저장
 *       (다중 샘플은 항상 저장)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final long BOOT_TIME_CHANGE_TOLERANCE_SECONDS = 5L;

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final AuditService auditService;
    private final WebSocketService webSocketService;
    private final FileDistributionOrchestrator fileDistributionOrchestrator;
    private final OperationalMetricsService operationalMetricsService;
    private final TrafficLogFlushService trafficLogFlushService;
    private final ObjectMapper objectMapper;

    @Value("${app.heartbeat.persist-interval-seconds:30}")
    private int heartbeatPersistIntervalSeconds;

    @Value("${app.traffic.log-min-interval-seconds:30}")
    private int trafficLogMinIntervalSeconds;

    @Value("${app.traffic.sample-interval-seconds:2}")
    private int trafficSampleIntervalSeconds;

    @Value("${app.command.max-retries:3}")
    private int maxCommandRetries;

    @Value("${app.command.queue-ttl-seconds:300}")
    private long commandQueueTtlSeconds;

    @Value("${app.command.dispatch-on-heartbeat:true}")
    private boolean dispatchCommandOnHeartbeat;

    @Value("${app.heartbeat.auto-catchup-check-interval-seconds:300}")
    private int autoCatchupCheckIntervalSeconds;

    @Value("${app.scmate.cli-username:}")
    private String scmateCliUsername;

    @Value("${app.scmate.cli-password:}")
    private String scmateCliPassword;

    private final ConcurrentMap<String, LocalDateTime> lastPcPersistedAt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalDateTime> lastTrafficPersistedAt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AutoCatchupCheckState> lastAutoCatchupCheckedAt = new ConcurrentHashMap<>();

    /**
     * 하트비트 처리 + 트래픽 로그 저장 + 대기 명령 조회를 하나의 트랜잭션으로 수행.
     * @return 전달할 명령 Map (없으면 null)
     */
    @Transactional
    public Map<String, Object> processHeartbeat(HeartbeatDto heartbeat) {
        String mac = heartbeat.getMacAddress();
        LocalDateTime now = LocalDateTime.now();

        // PC 상태 갱신
        Optional<PcMaster> existingPc = pcMasterRepository.findById(mac);
        PcMaster pc = existingPc.orElseGet(() -> {
            PcMaster newPc = new PcMaster();
            newPc.setMacAddress(mac);
            return newPc;
        });
        boolean isNewPc = existingPc.isEmpty();

        String previousPcName = pc.getPcName();
        String previousIp = pc.getIpAddress();
        String previousStatus = pc.getStatus();
        String previousRole = pc.getRole();
        Long previousGroupId = pc.getGroupId();
        String previousAgentSessionId = pc.getLastAgentSessionId();
        Long previousSystemBootTimeEpochSeconds = pc.getSystemBootTimeEpochSeconds();
        Boolean previousScmateAvailable = pc.getScmateAvailable();
        String previousScmateAvailabilityReason = pc.getScmateAvailabilityReason();
        String previousScmateMode = pc.getScmateMode();
        String previousScmateSchedule = pc.getScmateSchedule();
        String previousScmateSelectedPoint = pc.getScmateSelectedPoint();
        String previousScmateProductVariant = pc.getScmateProductVariant();
        String previousScmateStateSource = pc.getScmateStateSource();
        Boolean previousScmateTransitionPending = pc.getScmateTransitionPending();
        Long previousScmateRawState = pc.getScmateRawState();
        LocalDateTime previousScmateBaselineCreatedAt = pc.getScmateBaselineCreatedAt();
        LocalDateTime previousScmateSnap1CreatedAt = pc.getScmateSnap1CreatedAt();
        LocalDateTime previousScmateSnap2CreatedAt = pc.getScmateSnap2CreatedAt();
        String previousScmateError = pc.getScmateError();
        String previousScmateWorkflowStage = pc.getScmateWorkflowStage();
        LocalDateTime previousScmateObservedAt = pc.getScmateObservedAt();

        pc.setPcName(heartbeat.getHostname());
        pc.setIpAddress(heartbeat.getIpAddress());
        pc.setStatus("ONLINE");
        pc.setLastAgentSessionId(normalizeSessionId(heartbeat.getAgentSessionId()));
        if (heartbeat.getAgentVersion() != null && !heartbeat.getAgentVersion().isBlank()) {
            pc.setAgentVersion(heartbeat.getAgentVersion());
        }
        if (heartbeat.getBundleVersion() != null && !heartbeat.getBundleVersion().isBlank()) {
            pc.setBundleVersion(heartbeat.getBundleVersion().trim());
        }
        if (heartbeat.getSystemBootTimeEpochSeconds() != null) {
            pc.setSystemBootTimeEpochSeconds(heartbeat.getSystemBootTimeEpochSeconds());
        }
        if (heartbeat.getScmateAvailable() != null) {
            String incomingSchedule = normalizeScmateToken(heartbeat.getScmateSchedule());
            String incomingMode = normalizeScmateMode(heartbeat.getScmateMode(), heartbeat.getScmateSchedule());
            String incomingSelectedPoint = normalizeScmateToken(heartbeat.getScmateSelectedPoint());
            String incomingStateSource = normalizeScmateToken(heartbeat.getScmateStateSource());
            boolean preserveCliVerifiedMode = shouldPreserveCliVerifiedMode(
                    previousSystemBootTimeEpochSeconds, heartbeat.getSystemBootTimeEpochSeconds(),
                    previousScmateMode, previousScmateSchedule, previousScmateStateSource,
                    incomingMode, incomingSchedule, incomingStateSource);
            pc.setScmateAvailable(heartbeat.getScmateAvailable());
            pc.setScmateAvailabilityReason(normalizeScmateToken(heartbeat.getScmateAvailabilityReason()));
            if (!preserveCliVerifiedMode) {
                pc.setScmateSchedule(incomingSchedule);
                pc.setScmateMode(incomingMode);
                pc.setScmateSelectedPoint(incomingSelectedPoint);
                pc.setScmateStateSource(incomingStateSource);
                pc.setScmateRawState(heartbeat.getScmateRawState());
            }
            pc.setScmateProductVariant(trimToLength(heartbeat.getScmateProductVariant(), 80));
            pc.setScmateTransitionPending(Boolean.TRUE.equals(heartbeat.getScmateTransitionPending()));
            if (heartbeat.getScmateBaselineCreatedAt() != null) {
                pc.setScmateBaselineCreatedAt(heartbeat.getScmateBaselineCreatedAt());
            }
            if (heartbeat.getScmateSnap1CreatedAt() != null) {
                pc.setScmateSnap1CreatedAt(heartbeat.getScmateSnap1CreatedAt());
            }
            if (heartbeat.getScmateSnap2CreatedAt() != null) {
                pc.setScmateSnap2CreatedAt(heartbeat.getScmateSnap2CreatedAt());
            }
            String heartbeatScmateError = trimToLength(heartbeat.getScmateError(), 255);
            if (heartbeatScmateError != null || !"FAILED".equals(pc.getScmateWorkflowStage())) {
                pc.setScmateError(heartbeatScmateError);
            }
            advanceScmateWorkflow(pc);
            boolean scmateStateChanged = !Objects.equals(previousScmateAvailable, pc.getScmateAvailable())
                    || !Objects.equals(previousScmateAvailabilityReason, pc.getScmateAvailabilityReason())
                    || !Objects.equals(previousScmateMode, pc.getScmateMode())
                    || !Objects.equals(previousScmateSchedule, pc.getScmateSchedule())
                    || !Objects.equals(previousScmateSelectedPoint, pc.getScmateSelectedPoint())
                    || !Objects.equals(previousScmateProductVariant, pc.getScmateProductVariant())
                    || !Objects.equals(previousScmateStateSource, pc.getScmateStateSource())
                    || !Objects.equals(previousScmateTransitionPending, pc.getScmateTransitionPending())
                    || !Objects.equals(previousScmateRawState, pc.getScmateRawState())
                    || !Objects.equals(previousScmateBaselineCreatedAt, pc.getScmateBaselineCreatedAt())
                    || !Objects.equals(previousScmateSnap1CreatedAt, pc.getScmateSnap1CreatedAt())
                    || !Objects.equals(previousScmateSnap2CreatedAt, pc.getScmateSnap2CreatedAt())
                    || !Objects.equals(previousScmateError, pc.getScmateError())
                    || !Objects.equals(previousScmateWorkflowStage, pc.getScmateWorkflowStage());
            if (scmateStateChanged || previousScmateObservedAt == null
                    || Duration.between(previousScmateObservedAt, now).getSeconds() >= heartbeatPersistIntervalSeconds) {
                pc.setScmateObservedAt(now);
            }
        }

        // 자동 그룹핑 (IP 매칭 실패 시 기존 groupId를 해제)
        Long groupId = classroomResolutionService.resolveGroupId(heartbeat.getIpAddress(), mac);
        pc.setGroupId(groupId);

        if ((previousGroupId != null && !previousGroupId.equals(groupId))
            || (previousGroupId == null && groupId != null)) {
            String detailJson = "{\"previousGroupId\":" + (previousGroupId == null ? "null" : previousGroupId)
                + ",\"newGroupId\":" + (groupId == null ? "null" : groupId)
                + ",\"ip\":\"" + safeJson(heartbeat.getIpAddress()) + "\"}";
            registerGroupReassignmentAudit(mac, detailJson, heartbeat.getIpAddress());
        }

        // 역할 판단
        PcRole role = classroomResolutionService.resolveRole(
                pc.getGroupId(), heartbeat.getIpAddress(), mac);
        pc.setRole(role.name());

        boolean pcStateChanged = isNewPc
            || !Objects.equals(previousPcName, pc.getPcName())
            || !Objects.equals(previousIp, pc.getIpAddress())
            || !Objects.equals(previousStatus, pc.getStatus())
            || !Objects.equals(previousRole, pc.getRole())
            || !Objects.equals(previousGroupId, pc.getGroupId())
            || !Objects.equals(previousAgentSessionId, pc.getLastAgentSessionId())
            || !Objects.equals(previousSystemBootTimeEpochSeconds, pc.getSystemBootTimeEpochSeconds())
            || !Objects.equals(previousScmateAvailable, pc.getScmateAvailable())
            || !Objects.equals(previousScmateAvailabilityReason, pc.getScmateAvailabilityReason())
            || !Objects.equals(previousScmateMode, pc.getScmateMode())
            || !Objects.equals(previousScmateSchedule, pc.getScmateSchedule())
            || !Objects.equals(previousScmateSelectedPoint, pc.getScmateSelectedPoint())
            || !Objects.equals(previousScmateProductVariant, pc.getScmateProductVariant())
            || !Objects.equals(previousScmateStateSource, pc.getScmateStateSource())
            || !Objects.equals(previousScmateTransitionPending, pc.getScmateTransitionPending())
            || !Objects.equals(previousScmateRawState, pc.getScmateRawState())
            || !Objects.equals(previousScmateBaselineCreatedAt, pc.getScmateBaselineCreatedAt())
            || !Objects.equals(previousScmateSnap1CreatedAt, pc.getScmateSnap1CreatedAt())
            || !Objects.equals(previousScmateSnap2CreatedAt, pc.getScmateSnap2CreatedAt())
            || !Objects.equals(previousScmateError, pc.getScmateError())
            || !Objects.equals(previousScmateWorkflowStage, pc.getScmateWorkflowStage());
        boolean shouldPersistPc = pcStateChanged || shouldPersistByInterval(mac, now, lastPcPersistedAt,
            heartbeatPersistIntervalSeconds);
        if (shouldPersistPc) {
            pc.setLastSeen(now);
            pcMasterRepository.save(pc);
            lastPcPersistedAt.put(mac, now);

            // 실시간 WebSocket 알림: PC 상태 변경 시 대시보드 갱신 트리거
            if (pcStateChanged) {
                try {
                    webSocketService.sendDashboardUpdate(Map.of("eventType", "PC_STATUS"));
                } catch (Exception e) {
                    log.debug("WebSocket 알림 전송 실패 (무시): {}", e.getMessage());
                }
            }
        }

        handleScmateBootChange(mac, previousSystemBootTimeEpochSeconds,
                pc.getSystemBootTimeEpochSeconds(), now);
        handleAgentSessionChange(mac, previousAgentSessionId, pc.getLastAgentSessionId(), now);

        // 최신/진행중 배포 기준 버전과 다를 경우, 늦게 켜진 에이전트를 자동 캐치업 대상으로 추가한다.
        maybeAutoCatchUpOnHeartbeat(pc, now);

        // SCMATE 초기화 복구 완료 자동 감지
        // PC가 OFFLINE → ONLINE으로 복귀하면 SCMATE 복구 완료로 표시
        if ("OFFLINE".equals(previousStatus) && "ONLINE".equals(pc.getStatus())) {
            Optional<CommandQueue> pendingResetCmd = commandQueueRepository
                .findByMacAddressAndScmateResetStatus(mac, "PENDING").stream().findFirst();
            
            if (pendingResetCmd.isPresent()) {
                CommandQueue cmd = pendingResetCmd.get();
                cmd.setScmateResetStatus("COMPLETED");
                cmd.setScmateResetCompletedAt(now);
                commandQueueRepository.save(cmd);
                long waitSec = cmd.getScmateResetRequestedAt() != null
                        ? java.time.temporal.ChronoUnit.SECONDS.between(cmd.getScmateResetRequestedAt(), now) : 0;
                log.info("[SCMATE 복구 완료] {} - 명령 ID: {} (대기 시간: {}초)",
                        mac, cmd.getTaskId(), waitSec);
            }
        }

        persistTrafficSamples(mac, heartbeat, now);

        // 대기 명령 꺼내기: QUEUED -> DELIVERED (CAS — long-poll과 race condition 방지)
        if (!dispatchCommandOnHeartbeat) {
            return null;
        }
        return dispatchNextCommand(mac);
    }

    /**
     * 하트비트 없이 다음 QUEUED 명령만 즉시 디스패치한다.
     * 에이전트의 빠른 명령 폴링 엔드포인트(GET /agent/commands/next)에서 호출된다.
     */
    /**
     * 다음 QUEUED 명령을 DELIVERED로 전환하여 반환한다.
     * CAS UPDATE(markDeliveredIfQueued)를 사용하여 Long-poll과 하트비트가 동시에
     * 같은 명령을 가져가는 race condition을 방지한다.
     * 다른 스레드가 이미 가져간 경우 null 반환.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> dispatchNextCommand(String mac) {
        List<CommandQueue> candidates = commandQueueRepository
                .findNextDispatchCandidates(mac, PageRequest.of(0, 100));
        if (candidates.isEmpty()) return null;

        LocalDateTime deliveredAt = LocalDateTime.now();
        LocalDateTime validCreatedAt = deliveredAt.minusSeconds(Math.max(1L, commandQueueTtlSeconds));
        CommandQueue pending = candidates.stream()
                .filter(command -> command.getCreatedAt() != null
                        && !command.getCreatedAt().isBefore(validCreatedAt))
                .findFirst()
                .orElse(null);
        if (pending == null) {
            return null;
        }

        // CAS: QUEUED인 경우에만 DELIVERED로 전환. 0이면 다른 스레드가 먼저 가져감.
        int updated = commandQueueRepository.markDeliveredIfQueued(pending.getTaskId(), deliveredAt);
        if (updated == 0) return null;

        if (pending.getCreatedAt() != null) {
            long latencyMs = Duration.between(pending.getCreatedAt(), deliveredAt).toMillis();
            operationalMetricsService.recordCommandDispatchLatencyMillis(latencyMs);
        }

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("taskId",    pending.getTaskId());
        cmd.put("type",      pending.getCommandType());
        cmd.put("payload",   pending.getPayloadJson());
        cmd.put("scriptUrl", pending.getScriptUrl());
        if ("SCMATE_CONTROL".equalsIgnoreCase(pending.getCommandType())
                && scmateActionRequiresCredential(pending.getPayloadJson())) {
            cmd.put("scmateCredential", Map.of(
                    "username", scmateCliUsername == null ? "" : scmateCliUsername,
                    "password", scmateCliPassword == null ? "" : scmateCliPassword));
        }
        return cmd;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requeueDeliveredIfUnacked(Long taskId) {
        if (taskId != null) {
            commandQueueRepository.requeueDeliveredIfUnacked(taskId);
        }
    }

    private boolean scmateActionRequiresCredential(String payloadJson) {
        try {
            String action = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson)
                    .path("action").asText("");
            return !"STATUS".equalsIgnoreCase(action)
                    && !"INSTALL_RECOVERY_MODULE".equalsIgnoreCase(action);
        } catch (Exception ignored) {
            return true;
        }
    }

    @Transactional
    public void processGracefulOffline(HeartbeatOfflineDto offlineNotice) {
        if (offlineNotice == null || offlineNotice.getMacAddress() == null || offlineNotice.getMacAddress().isBlank()) {
            return;
        }

        String mac = offlineNotice.getMacAddress();
        LocalDateTime now = LocalDateTime.now();
        Optional<PcMaster> existingPc = pcMasterRepository.findById(mac);
        PcMaster pc = existingPc.orElseGet(() -> {
            PcMaster newPc = new PcMaster();
            newPc.setMacAddress(mac);
            return newPc;
        });

        String previousStatus = pc.getStatus();
        String previousPcName = pc.getPcName();
        String previousIp = pc.getIpAddress();
        String previousSessionId = pc.getLastAgentSessionId();

        if (hasText(offlineNotice.getHostname())) {
            pc.setPcName(offlineNotice.getHostname().trim());
        }
        if (hasText(offlineNotice.getIpAddress())) {
            pc.setIpAddress(offlineNotice.getIpAddress().trim());
        }
        if (hasText(offlineNotice.getAgentSessionId())) {
            pc.setLastAgentSessionId(offlineNotice.getAgentSessionId().trim());
        }
        if (hasText(offlineNotice.getAgentVersion())) {
            pc.setAgentVersion(offlineNotice.getAgentVersion().trim());
        }

        pc.setStatus("OFFLINE");
        pc.setLastSeen(now);
        pcMasterRepository.save(pc);
        lastPcPersistedAt.put(mac, now);

        boolean pcStateChanged = existingPc.isEmpty()
                || !Objects.equals(previousStatus, pc.getStatus())
                || !Objects.equals(previousPcName, pc.getPcName())
                || !Objects.equals(previousIp, pc.getIpAddress())
                || !Objects.equals(previousSessionId, pc.getLastAgentSessionId());
        if (pcStateChanged) {
            try {
                webSocketService.sendDashboardUpdate(Map.of("eventType", "PC_STATUS"));
            } catch (Exception e) {
                log.debug("WebSocket 오프라인 즉시 반영 알림 전송 실패 (무시): {}", e.getMessage());
            }
        }
    }

    private long resolveInboundKbps(HeartbeatDto heartbeat) {
        if (heartbeat.getInboundKbps() != null) {
            return Math.max(0L, Math.round(heartbeat.getInboundKbps()));
        }
        if (heartbeat.getInboundMbps() != null) {
            return Math.max(0L, Math.round(heartbeat.getInboundMbps() * 1000.0));
        }
        return 0L;
    }

    private long resolveOutboundKbps(HeartbeatDto heartbeat) {
        if (heartbeat.getOutboundKbps() != null) {
            return Math.max(0L, Math.round(heartbeat.getOutboundKbps()));
        }
        if (heartbeat.getOutboundMbps() != null) {
            return Math.max(0L, Math.round(heartbeat.getOutboundMbps() * 1000.0));
        }
        return 0L;
    }

    /**
     * 트래픽 샘플을 {@link TrafficLogFlushService} 비동기 버퍼에 적재한다.
     *
     * <p>직접 DB 저장을 제거하고 버퍼 적재만 수행하므로 하트비트 트랜잭션 내
     * DB 쓰기 횟수가 0으로 줄어든다. 실제 저장은 5초 주기 flush에서 배치로 처리된다.</p>
     *
     * <p><b>데이터 신뢰성:</b> 서버 비정상 종료 시 버퍼 내 로그는 유실될 수 있다.
     * 트래픽 로그는 모니터링 목적의 소프트 데이터이므로 허용 범위다.</p>
     */
    private void persistTrafficSamples(String mac, HeartbeatDto heartbeat, LocalDateTime now) {
        // 다중 샘플 모드: trafficSamples 리스트를 버퍼에 일괄 적재
        if (heartbeat.getTrafficSamples() != null && !heartbeat.getTrafficSamples().isEmpty()) {
            List<double[]> samples = heartbeat.getTrafficSamples();
            int sampleCount = samples.size();
            int sampleInterval = Math.max(1, trafficSampleIntervalSeconds);
            List<TrafficLog> logs = new ArrayList<>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                double[] sample = samples.get(i);
                if (sample == null || sample.length < 2) continue;
                TrafficLog tlog = new TrafficLog();
                tlog.setMacAddress(mac);
                tlog.setInboundKbps(toNonNegativeKbps(sample[0]));
                tlog.setOutboundKbps(toNonNegativeKbps(sample[1]));
                long ageSeconds = (long) (sampleCount - 1 - i) * sampleInterval;
                tlog.setTimestamp(now.minusSeconds(ageSeconds));
                logs.add(tlog);
            }
            if (!logs.isEmpty()) {
                trafficLogFlushService.enqueueAll(logs);  // 비동기 버퍼 — DB 쓰기 없음
                lastTrafficPersistedAt.put(mac, now);
            }
            return;
        }

        // 폴백: 단일값 — 30초 인터벌 후 버퍼에 적재 (하위 호환성 유지)
        boolean shouldPersistTraffic = shouldPersistByInterval(
                mac, now, lastTrafficPersistedAt, trafficLogMinIntervalSeconds);
        if (shouldPersistTraffic) {
            TrafficLog tlog = new TrafficLog();
            tlog.setMacAddress(mac);
            tlog.setInboundKbps(resolveInboundKbps(heartbeat));
            tlog.setOutboundKbps(resolveOutboundKbps(heartbeat));
            tlog.setTimestamp(now);
            trafficLogFlushService.enqueue(tlog);         // 비동기 버퍼 — DB 쓰기 없음
            lastTrafficPersistedAt.put(mac, now);
        }
    }

    private long toNonNegativeKbps(double value) {
        if (!Double.isFinite(value)) {
            return 0L;
        }
        return Math.max(0L, Math.round(value));
    }

    private void maybeAutoCatchUpOnHeartbeat(PcMaster pc, LocalDateTime now) {
        if (pc == null || pc.getMacAddress() == null || pc.getMacAddress().isBlank()) {
            return;
        }
        if (!shouldRunAutoCatchupCheck(pc, now)) {
            return;
        }

        String currentVersion = normalizeVersion(pc.getAgentVersion());
        lastAutoCatchupCheckedAt.put(pc.getMacAddress(), new AutoCatchupCheckState(now, currentVersion));

        try {
            fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(pc);
        } catch (Exception ex) {
            log.warn("자동 캐치업 큐잉 실패 mac={} message={}", pc.getMacAddress(), ex.getMessage());
        }
    }

    private boolean shouldRunAutoCatchupCheck(PcMaster pc, LocalDateTime now) {
        if (autoCatchupCheckIntervalSeconds <= 0) {
            return true;
        }

        AutoCatchupCheckState previous = lastAutoCatchupCheckedAt.get(pc.getMacAddress());
        if (previous == null) {
            return true;
        }

        String currentVersion = normalizeVersion(pc.getAgentVersion());
        if (!Objects.equals(previous.agentVersion(), currentVersion)) {
            return true;
        }

        return Duration.between(previous.checkedAt(), now).getSeconds() >= autoCatchupCheckIntervalSeconds;
    }

    private void registerGroupReassignmentAudit(String mac, String detailJson, String ipAddress) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            auditService.log(mac, "PC_GROUP_REASSIGNED", "PC", mac, detailJson, ipAddress);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                auditService.log(mac, "PC_GROUP_REASSIGNED", "PC", mac, detailJson, ipAddress);
            }
        });
    }

    private static String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String trimmed = sessionId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeVersion(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String normalizeScmateMode(String mode, String schedule) {
        if (mode != null) {
            String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT);
            if ("PROTECTED".equals(normalized) || "OPERATOR".equals(normalized)) {
                return normalized;
            }
        }
        String normalizedSchedule = normalizeScmateToken(schedule);
        if ("MANUAL".equals(normalizedSchedule)) return "OPERATOR";
        if ("RESTART".equals(normalizedSchedule)
                || "DAILY_FIRST_BOOT".equals(normalizedSchedule)
                || "WEEKLY".equals(normalizedSchedule)) {
            return "PROTECTED";
        }
        return "UNKNOWN";
    }

    static boolean shouldPreserveCliVerifiedMode(Long previousBootTime,
                                                 Long incomingBootTime,
                                                 String previousMode,
                                                 String previousSchedule,
                                                 String previousStateSource,
                                                 String incomingMode,
                                                 String incomingSchedule,
                                                 String incomingStateSource) {
        return Objects.equals(previousBootTime, incomingBootTime)
                && "PROTECTED".equalsIgnoreCase(previousMode)
                && "RESTART".equalsIgnoreCase(previousSchedule)
                && previousStateSource != null
                && previousStateSource.toUpperCase(java.util.Locale.ROOT).contains("SHD_CMD")
                && "OPERATOR".equalsIgnoreCase(incomingMode)
                && "MANUAL".equalsIgnoreCase(incomingSchedule)
                && (incomingStateSource == null
                || !incomingStateSource.toUpperCase(java.util.Locale.ROOT).contains("SHD_CMD"));
    }

    private static String normalizeScmateToken(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void advanceScmateWorkflow(PcMaster pc) {
        String stage = pc.getScmateWorkflowStage();
        if (stage == null) {
            return;
        }
        if ("OPERATOR_REQUESTED".equals(stage)
                && "PROTECT_DISABLED".equalsIgnoreCase(pc.getScmateAvailabilityReason())) {
            queueOperatorProtectionRecovery(pc);
            return;
        }
        if ("OPERATOR_ENABLE_PENDING".equals(stage)
                && Boolean.TRUE.equals(pc.getScmateAvailable())) {
            if ("OPERATOR".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("OPERATOR_READY");
                pc.setScmateError(null);
            } else if ("PROTECTED".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("FAILED");
                pc.setScmateError("SCMATE 운영자 모드 전환 후 복구 작업이 남아 있습니다.");
            }
            return;
        }
        if ("FAILED".equals(stage)
                && isAlreadyProtectedNoOpError(pc.getScmateError())
                && Boolean.TRUE.equals(pc.getScmateAvailable())) {
            pc.setScmateError(null);
            if ("OPERATOR".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("OPERATOR_READY");
                return;
            }
            if ("PROTECTED".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("COMPLETED");
                return;
            }
        }
        if (Boolean.TRUE.equals(pc.getScmateAvailable())
                && (pc.getScmateError() == null || pc.getScmateError().isBlank())
                && List.of("FAILED", "INSTALL_REQUESTED", "INSTALL_PENDING_REBOOT", "OPERATOR_READY",
                "PROTECTION_ENABLE_REQUESTED", "PROTECTION_ENABLE_PENDING")
                .contains(stage)) {
            if ("OPERATOR".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("OPERATOR_READY");
                return;
            }
            if ("PROTECTED".equals(pc.getScmateMode())) {
                pc.setScmateWorkflowStage("COMPLETED");
                return;
            }
        }
        if ("OPERATOR_REQUESTED".equals(stage)
                && !Boolean.TRUE.equals(pc.getScmateTransitionPending())
                && "OPERATOR".equals(pc.getScmateMode())) {
            pc.setScmateWorkflowStage("OPERATOR_READY");
            pc.setScmateError(null);
            return;
        }
        if (Boolean.TRUE.equals(pc.getScmateTransitionPending())) return;
        boolean bootChanged = isConfirmedBootChange(
                pc.getScmateWorkflowStartBootTimeEpochSeconds(),
                pc.getSystemBootTimeEpochSeconds());
        if ("PROTECTION_REQUESTED".equals(stage)
                && "PROTECTED".equals(pc.getScmateMode())) {
            pc.setScmateWorkflowStage("COMPLETED");
            pc.setScmateError(null);
            return;
        }
        if ("WAITING_PROTECTED_REBOOT".equals(stage)
                && bootChanged
                && "PROTECTED".equals(pc.getScmateMode())) {
            pc.setScmateWorkflowStage("COMPLETED");
            pc.setScmateError(null);
            return;
        }
        if ("RESTORE_REQUESTED".equals(stage)) {
            if (pc.getScmateError() != null && !pc.getScmateError().isBlank()) {
                pc.setScmateWorkflowStage("FAILED");
                return;
            }
            if (bootChanged && Boolean.TRUE.equals(pc.getScmateAvailable())
                    && ("PROTECTED".equals(pc.getScmateMode()) || "OPERATOR".equals(pc.getScmateMode()))) {
                pc.setScmateWorkflowStage("COMPLETED");
                pc.setScmateError(null);
            } else if (bootChanged && Boolean.FALSE.equals(pc.getScmateAvailable())) {
                pc.setScmateWorkflowStage("FAILED");
                pc.setScmateError("시스템 복구 후 SCMATE 상태를 확인하지 못했습니다.");
            }
        }
    }

    private void queueOperatorProtectionRecovery(PcMaster pc) {
        List<CommandQueue> active = commandQueueRepository
                .findByMacAddressAndStatusInOrderByCreatedAtAsc(
                        pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"));
        if (!active.isEmpty()) {
            return;
        }
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"ENABLE_PROTECTION\",\"recoveryPoint\":\"BASELINE\","
                + "\"resumeAction\":\"REQUEST_OPERATOR\"}");
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        command.setIsExecuted(false);
        commandQueueRepository.save(command);
        pc.setScmateWorkflowStage("OPERATOR_ENABLE_REQUESTED");
        pc.setScmateError(null);
    }

    private static boolean isAlreadyProtectedNoOpError(String error) {
        return error != null && error.startsWith("SCMATE_PROTECTION_NOT_DISABLED:");
    }

    private void handleScmateBootChange(String mac,
                                         Long previousBootTime,
                                         Long currentBootTime,
                                         LocalDateTime now) {
        if (!isConfirmedBootChange(previousBootTime, currentBootTime)) {
            return;
        }
        List<CommandQueue> inFlightCommands = commandQueueRepository
                .findByMacAddressAndStatusInOrderByCreatedAtAsc(mac, List.of("DELIVERED", "ACKED"));
        if (inFlightCommands.isEmpty()) return;

        PcMaster workflowPc = pcMasterRepository.findById(mac).orElse(null);
        for (CommandQueue cmd : inFlightCommands) {
            if ("SCMATE_CONTROL".equalsIgnoreCase(cmd.getCommandType())) {
                if (!"RESTORE_BASELINE".equals(scmateControlAction(cmd)) || workflowPc == null) continue;
                if ("COMPLETED".equals(workflowPc.getScmateWorkflowStage())) {
                    completeScmateRestartCommand(cmd, now,
                            "system boot time changed after saved-point restore");
                } else if ("FAILED".equals(workflowPc.getScmateWorkflowStage())) {
                    failScmateRestartCommand(cmd, now, workflowPc.getScmateError());
                }
                continue;
            }
            if (isScmateWorkflowReboot(cmd)) {
                completeScmateRestartCommand(cmd, now,
                        "system boot time changed after SCMATE workflow reboot");
            }
        }
    }

    static boolean isConfirmedBootChange(Long previousBootTime, Long currentBootTime) {
        return previousBootTime != null
                && currentBootTime != null
                && currentBootTime - previousBootTime > BOOT_TIME_CHANGE_TOLERANCE_SECONDS;
    }

    private void completeScmateRestartCommand(CommandQueue command, LocalDateTime now, String detail) {
        command.setStatus("COMPLETED");
        command.setExitCode(0);
        command.setCompletedAt(now);
        command.setIsExecuted(true);
        command.setStderr(detail);
        commandQueueRepository.save(command);
    }

    private void failScmateRestartCommand(CommandQueue command, LocalDateTime now, String detail) {
        command.setStatus("FAILED");
        command.setCompletedAt(now);
        command.setIsExecuted(true);
        command.setStderr(detail == null ? "SCMATE restart verification failed" : detail);
        commandQueueRepository.save(command);
    }

    private void handleAgentSessionChange(String mac,
                                          String previousSessionId,
                                          String currentSessionId,
                                          LocalDateTime now) {
        if (previousSessionId == null || currentSessionId == null || previousSessionId.equals(currentSessionId)) {
            return;
        }

        List<CommandQueue> inFlightCommands = commandQueueRepository
                .findByMacAddressAndStatusInOrderByCreatedAtAsc(mac, List.of("DELIVERED", "ACKED"));
        if (inFlightCommands.isEmpty()) {
            return;
        }

        for (CommandQueue cmd : inFlightCommands) {
            if ("SCMATE_CONTROL".equalsIgnoreCase(cmd.getCommandType()) || isScmateWorkflowReboot(cmd)) {
                continue;
            }
            int retryCount = cmd.getRetryCount() == null ? 0 : cmd.getRetryCount();
            if (retryCount >= maxCommandRetries) {
                cmd.setStatus("FAILED");
                cmd.setCompletedAt(now);
                cmd.setIsExecuted(true);
                cmd.setStderr("agent session changed before result reported");
            } else {
                cmd.setRetryCount(retryCount + 1);
                cmd.setStatus("QUEUED");
                cmd.setDeliveredAt(null);
                cmd.setAckedAt(null);
                cmd.setCompletedAt(null);
                cmd.setIsExecuted(false);
                cmd.setStderr("agent restarted before result reported; requeued");
            }
            commandQueueRepository.save(cmd);
        }

        log.warn("[CMD SESSION RESET] mac={} previousSessionId={} currentSessionId={} requeued={}",
                mac, previousSessionId, currentSessionId, inFlightCommands.size());
    }

    private boolean shouldPersistByInterval(String mac,
                                            LocalDateTime now,
                                            ConcurrentMap<String, LocalDateTime> lastPersistedAt,
                                            int intervalSeconds) {
        if (intervalSeconds <= 0) {
            return true;
        }
        LocalDateTime prev = lastPersistedAt.get(mac);
        if (prev == null) {
            return true;
        }
        return Duration.between(prev, now).getSeconds() >= intervalSeconds;
    }

    private boolean isScmateWorkflowReboot(CommandQueue command) {
        if (command == null || !"REBOOT".equalsIgnoreCase(command.getCommandType())) {
            return false;
        }
        try {
            return objectMapper.readTree(command.getPayloadJson() == null ? "{}" : command.getPayloadJson())
                    .hasNonNull("scmateWorkflowId");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String scmateControlAction(CommandQueue command) {
        if (command == null || !"SCMATE_CONTROL".equalsIgnoreCase(command.getCommandType())) {
            return "";
        }
        try {
            return objectMapper.readTree(command.getPayloadJson() == null ? "{}" : command.getPayloadJson())
                    .path("action").asText("").trim().toUpperCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record AutoCatchupCheckState(LocalDateTime checkedAt, String agentVersion) {
    }
}
