package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomResolutionService.PcRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final CommandQueueRepository commandQueueRepository;

    /**
     * 하트비트 처리 + 트래픽 로그 저장 + 대기 명령 조회를 하나의 트랜잭션으로 수행.
     * @return 전달할 명령 Map (없으면 null)
     */
    @Transactional
    public Map<String, Object> processHeartbeat(HeartbeatDto heartbeat) {
        String mac = heartbeat.getMacAddress();

        // PC 상태 갱신
        PcMaster pc = pcMasterRepository.findById(mac).orElseGet(() -> {
            PcMaster newPc = new PcMaster();
            newPc.setMacAddress(mac);
            return newPc;
        });

        pc.setPcName(heartbeat.getHostname());
        pc.setIpAddress(heartbeat.getIpAddress());
        pc.setLastSeen(LocalDateTime.now());
        pc.setStatus("ONLINE");

        // 자동 그룹핑
        Long groupId = classroomResolutionService.resolveGroupIdByIp(heartbeat.getIpAddress());
        if (groupId != null) {
            pc.setGroupId(groupId);
        }

        // 역할 판단
        PcRole role = classroomResolutionService.resolveRole(
                pc.getGroupId(), heartbeat.getIpAddress(), mac);
        pc.setRole(role.name());
        pcMasterRepository.save(pc);

        // 트래픽 로그 저장: Kbps 우선, 없으면 Mbps -> Kbps 변환
        long inboundKbps = resolveInboundKbps(heartbeat);
        long outboundKbps = resolveOutboundKbps(heartbeat);
        TrafficLog tlog = new TrafficLog();
        tlog.setMacAddress(mac);
        tlog.setInboundKbps(inboundKbps);
        tlog.setOutboundKbps(outboundKbps);
        tlog.setTimestamp(LocalDateTime.now());
        trafficLogsRepository.save(tlog);

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
}
