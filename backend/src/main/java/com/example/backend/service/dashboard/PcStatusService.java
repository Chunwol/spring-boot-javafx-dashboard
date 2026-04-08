package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for PC status management including broken PC tracking and updates.
 */
@Service
public class PcStatusService {

    private static final Logger logger = LoggerFactory.getLogger(PcStatusService.class);
    private static final String BROKEN_PLACEHOLDER_PREFIX = "BROKEN-IP::";

    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;

    public PcStatusService(PcMasterRepository pcMasterRepository,
                           ClassroomResolutionService classroomResolutionService) {
        this.pcMasterRepository = pcMasterRepository;
        this.classroomResolutionService = classroomResolutionService;
    }

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
    public Page<PcStatusDto> getPcStatusesPaged(Long groupId, int page, int size) {
        List<PcStatusDto> statuses = getPcStatuses(groupId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int total = statuses.size();
        int fromIndex = safePage * safeSize;
        if (fromIndex >= total) {
            return new PageImpl<>(List.of(), PageRequest.of(safePage, safeSize), total);
        }
        int toIndex = Math.min(fromIndex + safeSize, total);
        return new PageImpl<>(statuses.subList(fromIndex, toIndex), PageRequest.of(safePage, safeSize), total);
    }

    /**
     * Mark a PC as broken (supports both MAC and IP addresses)
     */
    public PcStatusDto markPcAsBroken(String macAddress, String ipAddress, String note) {
        PcMaster pc;
        if (macAddress != null && !macAddress.isBlank()) {
            pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                    new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        } else if (ipAddress != null && !ipAddress.isBlank()) {
            pc = pcMasterRepository.findByIpAddress(ipAddress)
                    .orElseGet(() -> createBrokenPlaceholderPc(ipAddress, note));
        } else {
            throw new IllegalArgumentException("macAddress 또는 ipAddress가 필요합니다.");
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
        return pcMasterRepository.findAll().stream()
                .filter(pc -> "BROKEN".equalsIgnoreCase(pc.getStatus()) || "FAILED".equalsIgnoreCase(pc.getStatus()))
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
    private PcMaster createBrokenPlaceholderPc(String ipAddress, String note) {
        Long groupId = classroomResolutionService.resolveGroupIdByIp(ipAddress);
        if (groupId == null) {
            throw new IllegalArgumentException("해당 IP에 매핑된 강의실이 없습니다: " + ipAddress);
        }

        PcMaster pc = new PcMaster();
        pc.setMacAddress(BROKEN_PLACEHOLDER_PREFIX + ipAddress.replace('.', '-'));
        pc.setPcName("BROKEN " + ipAddress);
        pc.setIpAddress(ipAddress);
        pc.setGroupId(groupId);
        pc.setRole(classroomResolutionService.resolveRole(groupId, ipAddress, null).name());
        pc.setStatus("BROKEN");
        pc.setBrokenNote(note == null || note.isBlank() ? "broken" : note.trim());
        pc.setLastSeen(null);
        return pc;
    }

    /**
     * Check if a PC is a broken placeholder entry
     */
    private boolean isBrokenPlaceholder(PcMaster pc) {
        return pc.getMacAddress() != null && pc.getMacAddress().startsWith(BROKEN_PLACEHOLDER_PREFIX);
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
        dto.setRole(pc.getRole());
        dto.setBrokenNote(pc.getBrokenNote());
        dto.setLastSeen(pc.getLastSeen());
        return dto;
    }
}
