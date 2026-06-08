package com.example.backend.service.group;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.ClientPosition;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.ClientPositionRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomValidationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final ClassroomGroupRepository groupRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final ClientPositionRepository positionRepository;
    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final ClassroomValidationService classroomValidationService;

    public Page<ClassroomGroup> findAll(int page, int size) {
        return groupRepository.findAll(PageRequest.of(page, size));
    }

    @Transactional
    public ClassroomGroup save(ClassroomGroup g) {
        classroomValidationService.validateGroupNameForCreate(g.getName());
        return groupRepository.save(g);
    }

    @Transactional
    public Optional<ClassroomGroup> update(Long id, ClassroomGroup g) {
        return groupRepository.findById(id).map(existing -> {
            classroomValidationService.validateGroupNameForUpdate(id, g.getName());
            existing.setName(g.getName());
            existing.setDescription(g.getDescription());
            return groupRepository.save(existing);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (groupRepository.findById(id).isEmpty()) {
            return false;
        }

        // 1) 클라이언트 좌표 그룹 해제
        positionRepository.findByGroupId(id).forEach(p -> {
            p.setGroupId(null);
            positionRepository.save(p);
        });

        // 2) 소속 PC 그룹 해제 + 미완료 명령 CANCELLED 처리
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(id);
        for (PcMaster pc : pcs) {
            pc.setGroupId(null);
            pcMasterRepository.save(pc);
        }

        Set<String> macAddresses = pcs.stream()
                .map(PcMaster::getMacAddress)
                .filter(mac -> mac != null && !mac.isBlank())
                .collect(Collectors.toSet());

        if (!macAddresses.isEmpty()) {
            List<String> activeStatuses = List.of("QUEUED", "DELIVERED", "ACKED");
            LocalDateTime cancelledAt = LocalDateTime.now();
            int cancelledCount = 0;
            for (String mac : macAddresses) {
                List<CommandQueue> activeCommands =
                        commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(mac, activeStatuses);
                for (CommandQueue cmd : activeCommands) {
                    cmd.setStatus("CANCELLED");
                    cmd.setCompletedAt(cancelledAt);
                    commandQueueRepository.save(cmd);
                    cancelledCount++;
                }
            }
            log.info("강의실 {} 삭제: {} 대의 PC, {} 건의 미완료 명령 CANCELLED 처리",
                    id, macAddresses.size(), cancelledCount);
        }

        // 3) 연관 IP 대역 / 교사 장치 정리
        ipRangeRepository.deleteByGroupId(id);
        teacherDeviceRepository.deleteByGroupId(id);

        groupRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
        return true;
    }

    @Transactional
    public boolean assignByIp(Long groupId, String ip) {
        Optional<PcMaster> pmOpt = pcMasterRepository.findByIpAddress(ip);
        if (pmOpt.isEmpty()) return false;

        PcMaster pm = pmOpt.get();
        pm.setGroupId(groupId);
        pcMasterRepository.save(pm);

        ClientPosition cp = positionRepository.findByMacAddress(pm.getMacAddress());
        if (cp == null) cp = positionRepository.findByIpAddress(ip);
        if (cp == null) {
            cp = new ClientPosition();
            cp.setMacAddress(pm.getMacAddress());
            cp.setIpAddress(ip);
        }
        cp.setGroupId(groupId);
        positionRepository.save(cp);
        return true;
    }

    @Transactional
    public boolean assignByMac(String mac, Long groupId) {
        Optional<PcMaster> pmOpt = pcMasterRepository.findById(mac);
        if (pmOpt.isEmpty()) return false;

        PcMaster pm = pmOpt.get();
        pm.setGroupId(groupId);
        pcMasterRepository.save(pm);

        ClientPosition cp = positionRepository.findByMacAddress(mac);
        if (cp == null) {
            cp = new ClientPosition();
            cp.setMacAddress(mac);
        }
        cp.setGroupId(groupId);
        positionRepository.save(cp);
        return true;
    }

    @Transactional
    public ClientPosition savePosition(Long groupId, ClientPosition pos) {
        pos.setGroupId(groupId);
        ClientPosition existing = null;
        if (pos.getMacAddress() != null) existing = positionRepository.findByMacAddress(pos.getMacAddress());
        if (existing == null && pos.getIpAddress() != null) existing = positionRepository.findByIpAddress(pos.getIpAddress());
        if (existing != null) {
            existing.setX(pos.getX());
            existing.setY(pos.getY());
            existing.setGroupId(groupId);
            return positionRepository.save(existing);
        }
        return positionRepository.save(pos);
    }

    public List<ClientPosition> findPositions(Long groupId) {
        return positionRepository.findByGroupId(groupId).stream().map(p -> {
            PcMaster pc = pcMasterRepository.findById(p.getMacAddress()).orElse(null);
            if (pc != null) p.setIpAddress(pc.getIpAddress());
            return p;
        }).collect(Collectors.toList());
    }
}
