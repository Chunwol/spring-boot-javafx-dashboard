package com.example.backend.service.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.common.exception.ConflictException;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.util.AgentVersionUtil;
import com.example.backend.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service for PC status management including broken PC tracking and updates.
 */
@Service
@RequiredArgsConstructor
public class PcStatusService {

    private static final Logger logger = LoggerFactory.getLogger(PcStatusService.class);
    private static final String BROKEN_PLACEHOLDER_PREFIX = "BROKEN-IP::";

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final ClassroomGroupRepository classroomGroupRepository;

    /**
     * Get all PC statuses for a classroom
     */
    public List<PcStatusDto> getPcStatuses(Long groupId) {
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(groupId);
        return pcs.stream().map(this::toPcStatusDto).toList();
    }

    /**
     * Get paginated PC statuses for a classroom
     */
    public Page<PcStatusDto> getPcStatusesPaged(Long groupId, Pageable pageable) {
        List<PcStatusDto> statuses = getPcStatuses(groupId);
        return PaginationUtil.paginate(statuses, pageable);
    }

    /**
     * Mark a PC as broken (supports both MAC and IP addresses)
     */
    public PcStatusDto markPcAsBroken(String macAddress, String ipAddress, String note) {
        return markPcAsBroken(macAddress, ipAddress, note, null, null);
    }

    public PcStatusDto markPcAsBroken(String macAddress, String ipAddress, String note, Integer seatNumber) {
        return markPcAsBroken(macAddress, ipAddress, note, seatNumber, null);
    }

    public PcStatusDto markPcAsBroken(
            String macAddress, String ipAddress, String note, Integer seatNumber, Long groupId) {
        PcMaster pc;
        if (macAddress != null && !macAddress.isBlank()) {
            pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                    new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        } else if (ipAddress != null && !ipAddress.isBlank()) {
            pc = pcMasterRepository.findByIpAddress(ipAddress)
                    .orElseGet(() -> createBrokenPlaceholderPc(ipAddress, note, seatNumber, groupId));
        } else {
            throw new IllegalArgumentException("macAddress 또는 ipAddress가 필요합니다.");
        }

        if (macAddress == null && seatNumber != null && !isBrokenPlaceholder(pc)) {
            throw new ConflictException("이미 등록된 PC가 사용하는 IP입니다: " + ipAddress);
        }

        if (isBrokenPlaceholder(pc) && seatNumber != null) {
            if (seatNumber <= 0) throw new IllegalArgumentException("좌석 번호는 1 이상이어야 합니다.");
            pc.setPcName("미사용 PC " + seatNumber);
        }
        if (isBrokenPlaceholder(pc) && groupId != null) {
            validateGroup(groupId);
            pc.setGroupId(groupId);
            pc.setRole(classroomResolutionService.resolveRole(groupId, pc.getIpAddress(), null).name());
        }
        pc.setStatus("BROKEN");
        pc.setBrokenNote(note == null || note.isBlank() ? "broken" : note.trim());
        pcMasterRepository.save(pc);
        return toPcStatusDto(pc);
    }

    /**
     * Get all broken PC statuses
     */
    public List<PcStatusDto> getBrokenPcStatuses() {
        return pcMasterRepository.findByStatusIn(List.of("BROKEN", "FAILED"))
                .stream()
                .map(this::toPcStatusDto)
                .toList();
    }

    /**
     * Clear broken status from a PC
     */
    public PcStatusDto clearBrokenPc(String macAddress) {
        PcMaster pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        PcStatusDto dto = toPcStatusDto(pc);
        if (isBrokenPlaceholder(pc)) {
            pcMasterRepository.delete(pc);
            return dto;
        }
        if ("BROKEN".equalsIgnoreCase(pc.getStatus()) || "FAILED".equalsIgnoreCase(pc.getStatus())) {
            pc.setStatus("OFFLINE");
            pc.setBrokenNote(null);
            pcMasterRepository.save(pc);
        }
        return toPcStatusDto(pc);
    }

    /**
     * Update the broken note for a PC
     */
    public PcStatusDto updateBrokenPcNote(String macAddress, String note) {
        PcMaster pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        pc.setBrokenNote(note == null || note.isBlank() ? "broken" : note.trim());
        pcMasterRepository.save(pc);
        return toPcStatusDto(pc);
    }

    /**
     * Create a placeholder PC entry for broken IP addresses
     */
    private PcMaster createBrokenPlaceholderPc(
            String ipAddress, String note, Integer seatNumber, Long requestedGroupId) {
        Long groupId = requestedGroupId != null
                ? requestedGroupId
                : classroomResolutionService.resolveGroupIdByIp(ipAddress);
        if (groupId == null) {
            throw new IllegalArgumentException("해당 IP에 매핑된 강의실이 없습니다: " + ipAddress);
        }
        validateGroup(groupId);

        PcMaster pc = new PcMaster();
        pc.setMacAddress(BROKEN_PLACEHOLDER_PREFIX + ipAddress.replace('.', '-'));
        pc.setPcName(seatNumber == null ? "BROKEN " + ipAddress : "미사용 PC " + seatNumber);
        pc.setIpAddress(ipAddress);
        pc.setGroupId(groupId);
        pc.setRole(classroomResolutionService.resolveRole(groupId, ipAddress, null).name());
        pc.setStatus("BROKEN");
        pc.setBrokenNote(note == null || note.isBlank() ? "broken" : note.trim());
        pc.setLastSeen(null);
        return pc;
    }

    private void validateGroup(Long groupId) {
        if (!classroomGroupRepository.existsById(groupId)) {
            throw new IllegalArgumentException("강의실이 존재하지 않습니다: " + groupId);
        }
    }

    /**
     * Check if a PC is a broken placeholder entry
     */
    private boolean isBrokenPlaceholder(PcMaster pc) {
        return pc.getMacAddress() != null && pc.getMacAddress().startsWith(BROKEN_PLACEHOLDER_PREFIX);
    }

    // ── 상태 조회 (StatusController 위임용) ───────────────────────────────────

    public List<PcStatusDto> findAll() {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findAll().stream()
                .map(p -> toPcStatusDtoWithGroup(p, groupNames))
                .toList();
    }

    public Optional<PcStatusDto> findByMac(String mac) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findById(mac)
                .map(p -> toPcStatusDtoWithGroup(p, groupNames));
    }

    public List<PcStatusDto> findByRole(String role) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findAll().stream()
                .filter(p -> role.equalsIgnoreCase(p.getRole()))
                .map(p -> toPcStatusDtoWithGroup(p, groupNames))
                .toList();
    }

    public List<PcStatusDto> findByGroupId(Long groupId) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findByGroupId(groupId).stream()
                .map(p -> toPcStatusDtoWithGroup(p, groupNames))
                .toList();
    }

    private Map<Long, String> loadGroupNameMap() {
        return classroomGroupRepository.findAll().stream()
                .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName, (a, b) -> a));
    }

    private PcStatusDto toPcStatusDtoWithGroup(PcMaster pc, Map<Long, String> groupNames) {
        PcStatusDto dto = toPcStatusDto(pc);
        dto.setGroupName(pc.getGroupId() != null ? groupNames.getOrDefault(pc.getGroupId(), "-") : "-");
        dto.setOnline("ONLINE".equals(pc.getStatus()));
        return dto;
    }

    /**
     * Convert PcMaster to PcStatusDto
     */
    private PcStatusDto toPcStatusDto(PcMaster pc) {
        PcStatusDto dto = new PcStatusDto();
        dto.setMacAddress(pc.getMacAddress());
        dto.setPcName(pc.getPcName());
        dto.setIpAddress(pc.getIpAddress());
        dto.setGroupId(pc.getGroupId());
        dto.setStatus(pc.getStatus());
        dto.setOnline("ONLINE".equalsIgnoreCase(pc.getStatus()) || "IDLE".equalsIgnoreCase(pc.getStatus()));
        dto.setRole(pc.getRole());
        dto.setAgentVersion(pc.getAgentVersion());
        dto.setBundleVersion(pc.getBundleVersion());
        dto.setSystemBootTimeEpochSeconds(pc.getSystemBootTimeEpochSeconds());
        dto.setBrokenNote(pc.getBrokenNote());
        dto.setLastSeen(pc.getLastSeen());
        dto.setScmateAvailable(pc.getScmateAvailable());
        dto.setScmateAvailabilityReason(pc.getScmateAvailabilityReason());
        dto.setScmateMode(pc.getScmateMode());
        dto.setScmateSchedule(pc.getScmateSchedule());
        dto.setScmateSelectedPoint(pc.getScmateSelectedPoint());
        dto.setScmateProductVariant(pc.getScmateProductVariant());
        dto.setScmateStateSource(pc.getScmateStateSource());
        dto.setScmateTransitionPending(pc.getScmateTransitionPending());
        dto.setScmateRawState(pc.getScmateRawState());
        dto.setScmateObservedAt(pc.getScmateObservedAt());
        dto.setScmateBaselineUpdatedAt(pc.getScmateBaselineUpdatedAt());
        dto.setScmateBaselineCreatedAt(pc.getScmateBaselineCreatedAt());
        dto.setScmateSnap1CreatedAt(pc.getScmateSnap1CreatedAt());
        dto.setScmateSnap2CreatedAt(pc.getScmateSnap2CreatedAt());
        dto.setScmateError(pc.getScmateError());
        dto.setScmateWorkflowStage(pc.getScmateWorkflowStage());
        dto.setScmateWorkflowId(pc.getScmateWorkflowId());
        dto.setScmateAllowedActions(resolveScmateAllowedActions(pc));
        return dto;
    }

    private List<String> resolveScmateAllowedActions(PcMaster pc) {
        if (!"ONLINE".equalsIgnoreCase(pc.getStatus())) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        boolean detailedScmateSupported = AgentVersionUtil.isAtLeast(pc.getAgentVersion(), 1, 1, 35);
        if (detailedScmateSupported) {
            actions.add("REFRESH_STATUS_DETAIL");
        }
        if ("TEACHER".equalsIgnoreCase(pc.getRole())) {
            return List.copyOf(actions);
        }

        boolean modeControlSupported = AgentVersionUtil.isAtLeast(pc.getAgentVersion(), 1, 1, 27);
        if (modeControlSupported && pc.getSystemBootTimeEpochSeconds() != null) {
            actions.add("REBOOT_TO_OPERATOR");
            actions.add("REBOOT_TO_PROTECTED");
        }

        if ("NOT_INSTALLED".equalsIgnoreCase(pc.getScmateAvailabilityReason())
                && AgentVersionUtil.isAtLeast(pc.getAgentVersion(), 1, 1, 28)
                && !"INSTALL_PENDING_REBOOT".equals(pc.getScmateWorkflowStage())) {
            actions.add("INSTALL_RECOVERY_MODULE");
        }
        if ("PROTECT_DISABLED".equalsIgnoreCase(pc.getScmateAvailabilityReason())
                && AgentVersionUtil.isAtLeast(pc.getAgentVersion(), 1, 1, 40)) {
            actions.add("ENABLE_PROTECTION");
        }

        if (!Boolean.TRUE.equals(pc.getScmateAvailable())) {
            return List.copyOf(actions);
        }

        if (AgentVersionUtil.isAtLeast(pc.getAgentVersion(), 1, 1, 70)) {
            actions.add("LIST_EXCEPTION_FOLDERS");
            actions.add("ADD_EXCEPTION_FOLDER");
            actions.add("REMOVE_EXCEPTION_FOLDER");
        }

        if (detailedScmateSupported) {
            actions.add("REQUEST_OPERATOR");
            actions.add("REQUEST_PROTECTED");
            actions.add("RESTORE_BASELINE");
        }

        boolean stableManual = "MANUAL".equalsIgnoreCase(pc.getScmateSchedule())
                && !Boolean.TRUE.equals(pc.getScmateTransitionPending());
        boolean rebootObserved = pc.getScmateWorkflowStartBootTimeEpochSeconds() != null
                && pc.getSystemBootTimeEpochSeconds() != null
                && !Objects.equals(pc.getScmateWorkflowStartBootTimeEpochSeconds(),
                        pc.getSystemBootTimeEpochSeconds());
        if (detailedScmateSupported && stableManual && rebootObserved) {
            actions.add("UPDATE_BASELINE");
        }
        return List.copyOf(actions);
    }
}
