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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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

@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final AuditService auditService;
    private final WebSocketService webSocketService;
    private final FileDistributionOrchestrator fileDistributionOrchestrator;
    private final OperationalMetricsService operationalMetricsService;

    @Value("${app.heartbeat.persist-interval-seconds:30}")
    private int heartbeatPersistIntervalSeconds;

    @Value("${app.traffic.log-min-interval-seconds:30}")
    private int trafficLogMinIntervalSeconds;

    @Value("${app.traffic.sample-interval-seconds:2}")
    private int trafficSampleIntervalSeconds;

    @Value("${app.command.max-retries:3}")
    private int maxCommandRetries;

    private final ConcurrentMap<String, LocalDateTime> lastPcPersistedAt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalDateTime> lastTrafficPersistedAt = new ConcurrentHashMap<>();

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

        pc.setPcName(heartbeat.getHostname());
        pc.setIpAddress(heartbeat.getIpAddress());
        pc.setStatus("ONLINE");
        pc.setLastAgentSessionId(normalizeSessionId(heartbeat.getAgentSessionId()));
        if (heartbeat.getAgentVersion() != null && !heartbeat.getAgentVersion().isBlank()) {
            pc.setAgentVersion(heartbeat.getAgentVersion());
        }

        // 자동 그룹핑 (IP 매칭 실패 시 기존 groupId를 해제)
        Long groupId = classroomResolutionService.resolveGroupIdByIp(heartbeat.getIpAddress());
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
            || !Objects.equals(previousAgentSessionId, pc.getLastAgentSessionId());
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

        handleAgentSessionChange(mac, previousAgentSessionId, pc.getLastAgentSessionId(), now);

        // 최신/진행중 배포 기준 버전과 다를 경우, 늦게 켜진 에이전트를 자동 캐치업 대상으로 추가한다.
        try {
            fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(pc);
        } catch (Exception ex) {
            log.warn("자동 캐치업 큐잉 실패 mac={} message={}", mac, ex.getMessage());
        }

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
                log.info("[SCMATE 복구 완료] {} - 명령 ID: {} (대기 시간: {}초)",
                        mac, cmd.getTaskId(),
                        java.time.temporal.ChronoUnit.SECONDS.between(cmd.getScmateResetRequestedAt(), now));
            }
        }

        persistTrafficSamples(mac, heartbeat, now);

        // 대기 명령 꺼내기: QUEUED -> DELIVERED (CAS — long-poll과 race condition 방지)
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
    @Transactional
    public Map<String, Object> dispatchNextCommand(String mac) {
        List<CommandQueue> candidates = commandQueueRepository
                .findNextDispatchCandidates(mac, PageRequest.of(0, 1));
        if (candidates.isEmpty()) return null;

        CommandQueue pending = candidates.get(0);
        LocalDateTime deliveredAt = LocalDateTime.now();

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
        return cmd;
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

    private void persistTrafficSamples(String mac, HeartbeatDto heartbeat, LocalDateTime now) {
        // 다중 샘플 지원: trafficSamples 리스트가 있으면 각각 저장
        if (heartbeat.getTrafficSamples() != null && !heartbeat.getTrafficSamples().isEmpty()) {
            List<double[]> samples = heartbeat.getTrafficSamples();
            int sampleCount = samples.size();
            int sampleInterval = Math.max(1, trafficSampleIntervalSeconds);
            List<TrafficLog> logs = new ArrayList<>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                double[] sample = samples.get(i);
                if (sample == null || sample.length < 2) {
                    continue;
                }
                TrafficLog tlog = new TrafficLog();
                tlog.setMacAddress(mac);
                tlog.setInboundKbps(toNonNegativeKbps(sample[0]));
                tlog.setOutboundKbps(toNonNegativeKbps(sample[1]));
                long ageSeconds = (long) (sampleCount - 1 - i) * sampleInterval;
                tlog.setTimestamp(now.minusSeconds(ageSeconds));
                logs.add(tlog);
            }
            if (!logs.isEmpty()) {
                trafficLogsRepository.saveAll(logs);
                lastTrafficPersistedAt.put(mac, now);
            }
            return;
        }

        // 폴백: 단일값 저장 (하위 호환성)
        long inboundKbps = resolveInboundKbps(heartbeat);
        long outboundKbps = resolveOutboundKbps(heartbeat);
        boolean shouldPersistTraffic = shouldPersistByInterval(mac, now, lastTrafficPersistedAt,
            trafficLogMinIntervalSeconds);
        if (shouldPersistTraffic) {
            TrafficLog tlog = new TrafficLog();
            tlog.setMacAddress(mac);
            tlog.setInboundKbps(inboundKbps);
            tlog.setOutboundKbps(outboundKbps);
            tlog.setTimestamp(now);
            trafficLogsRepository.save(tlog);
            lastTrafficPersistedAt.put(mac, now);
        }
    }

    private long toNonNegativeKbps(double value) {
        if (!Double.isFinite(value)) {
            return 0L;
        }
        return Math.max(0L, Math.round(value));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
