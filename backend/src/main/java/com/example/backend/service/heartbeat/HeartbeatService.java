package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomResolutionService.PcRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final AuditService auditService;

    @Value("${app.heartbeat.persist-interval-seconds:30}")
    private int heartbeatPersistIntervalSeconds;

    @Value("${app.traffic.log-min-interval-seconds:30}")
    private int trafficLogMinIntervalSeconds;

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

        pc.setPcName(heartbeat.getHostname());
        pc.setIpAddress(heartbeat.getIpAddress());
        pc.setStatus("ONLINE");

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
            || !Objects.equals(previousGroupId, pc.getGroupId());
        boolean shouldPersistPc = pcStateChanged || shouldPersistByInterval(mac, now, lastPcPersistedAt,
            heartbeatPersistIntervalSeconds);
        if (shouldPersistPc) {
            pc.setLastSeen(now);
            pcMasterRepository.save(pc);
            lastPcPersistedAt.put(mac, now);
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
                org.slf4j.LoggerFactory.getLogger(HeartbeatService.class)
                    .info("[SCMATE 복구 완료] {} - 명령 ID: {} (대기 시간: {}초)",
                        mac, cmd.getTaskId(),
                        java.time.temporal.ChronoUnit.SECONDS.between(cmd.getScmateResetRequestedAt(), now));
            }
        }

        // 트래픽 로그 저장: Kbps 우선, 없으면 Mbps -> Kbps 변환
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

        // 대기 명령 꺼내기: QUEUED -> DELIVERED
        CommandQueue pending = commandQueueRepository
                .findFirstByMacAddressAndStatusOrderByTaskIdAsc(mac, "QUEUED");

        if (pending == null) return null;

        pending.setStatus("DELIVERED");
        pending.setDeliveredAt(LocalDateTime.now());
        pending.setIsExecuted(false); // DELIVERED 상태에서는 미실행
        commandQueueRepository.save(pending);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("taskId",    pending.getTaskId());
        cmd.put("type",      pending.getCommandType());
        cmd.put("payload",   pending.getPayloadJson());
        cmd.put("scriptUrl", pending.getScriptUrl());
        return cmd;
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
}
