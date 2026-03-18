package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.AutoGroupingService;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomResolutionService.PcRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final PcMasterRepository pcMasterRepository;
    private final AutoGroupingService autoGroupingService;
    private final ClassroomResolutionService classroomResolutionService;

    @Transactional
    public void processHeartbeat(HeartbeatDto heartbeat) {
        PcMaster pc = pcMasterRepository.findById(heartbeat.getMacAddress()).orElseGet(() -> {
            PcMaster newPc = new PcMaster();
            newPc.setMacAddress(heartbeat.getMacAddress());
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
        PcRole role = classroomResolutionService.resolveRole(pc.getGroupId(), heartbeat.getIpAddress(), heartbeat.getMacAddress());
        pc.setRole(role.name());

        pcMasterRepository.save(pc);

        // 트래픽 로그 처리 등 추가 로직
    }
}