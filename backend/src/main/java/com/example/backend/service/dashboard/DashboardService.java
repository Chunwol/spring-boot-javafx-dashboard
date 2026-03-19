package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.DashboardRefreshDto;
import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.backend.repository.pc.PcMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final DashboardWidgetItemRepository dashboardWidgetItemRepository;
    private final ObjectMapper objectMapper;

    public DashboardService(ClassroomGroupRepository classroomGroupRepository,
                           PcMasterRepository pcMasterRepository,
                           ClassroomIpRangeRepository ipRangeRepository,
                           ClassroomTeacherDeviceRepository teacherDeviceRepository,
                           DashboardWidgetItemRepository dashboardWidgetItemRepository,
                           ObjectMapper objectMapper) {
        this.classroomGroupRepository = classroomGroupRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.dashboardWidgetItemRepository = dashboardWidgetItemRepository;
        this.objectMapper = objectMapper;
    }

    public List<ClassroomSummaryDto> getClassroomSummaries() {
        try {
            List<ClassroomGroup> groups = classroomGroupRepository.findAll();
            List<ClassroomSummaryDto> summaries = new ArrayList<>();

            // 한 번의 쿼리로 모든 데이터를 fetch (N+1 최적화)
            List<PcMaster> allPcs = pcMasterRepository.findAll();
            List<ClassroomIpRange> allIpRanges = ipRangeRepository.findAll();
            List<ClassroomTeacherDevice> allTeachers = teacherDeviceRepository.findAll();

            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);

            for (ClassroomGroup group : groups) {
                // 메모리에서 groupId로 필터링
                List<PcMaster> pcs = allPcs.stream()
                        .filter(pc -> pc.getGroupId() != null && pc.getGroupId().equals(group.getId()))
                        .toList();
                
                long onlineCount = pcs.stream()
                    .filter(pc -> pc.getLastSeen() != null && pc.getLastSeen().isAfter(fiveMinutesAgo))
                    .count();

                List<String> ipRanges = allIpRanges.stream()
                        .filter(r -> r.getGroupId() != null && r.getGroupId().equals(group.getId()))
                        .map(r -> {
                            if (r.getCidr() != null && !r.getCidr().isBlank()) return r.getCidr();
                            if (r.getStartIp() != null && r.getEndIp() != null) return r.getStartIp() + "~" + r.getEndIp();
                            return "";
                        })
                        .filter(s -> s != null && !s.isBlank())
                        .toList();

                String teacherStatus = "OFFLINE";
                List<ClassroomTeacherDevice> teachers = allTeachers.stream()
                        .filter(t -> t.getGroupId() != null && t.getGroupId().equals(group.getId()))
                        .toList();
                        
                if (!teachers.isEmpty()) {
                    boolean anyOnlineTeacher = teachers.stream().anyMatch(t -> {
                        List<PcMaster> candidates = pcs.stream().filter(pc -> {
                            boolean macMatch = t.getMacAddress() != null && pc.getMacAddress() != null
                                    && t.getMacAddress().equalsIgnoreCase(pc.getMacAddress());
                            boolean ipMatch = t.getIpAddress() != null && pc.getIpAddress() != null
                                    && t.getIpAddress().equals(pc.getIpAddress());
                            return macMatch || ipMatch;
                        }).toList();
                        return candidates.stream().anyMatch(pc -> pc.getLastSeen() != null
                                && pc.getLastSeen().isAfter(fiveMinutesAgo));
                    });
                    teacherStatus = anyOnlineTeacher ? "ONLINE" : "OFFLINE";
                }

                ClassroomSummaryDto summary = new ClassroomSummaryDto();
                summary.setGroupId(group.getId());
                summary.setGroupName(group.getName());
                summary.setOnlinePcs((int) onlineCount);
                summary.setTotalPcs(pcs.size());
                summary.setOnlinePcCount((int) onlineCount);
                summary.setTotalPcCount(pcs.size());
                summary.setIpRanges(ipRanges);
                summary.setTeacherPcStatus(teacherStatus);
                summary.setVisible(true);
                summaries.add(summary);
            }

            return summaries;
        } catch (Exception e) {
            logger.error("Failed to fetch classroom summaries", e);
            return new ArrayList<>();
        }
    }

    public List<PcStatusDto> getPcStatuses(Long groupId) {
        // 최적화: 특정 그룹의 PCs만 로드 (메모리 효율성)
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(groupId);
        return pcs.stream().map(pc -> {
            PcStatusDto dto = new PcStatusDto();
            dto.setMacAddress(pc.getMacAddress());
            dto.setPcName(pc.getPcName());
            dto.setIpAddress(pc.getIpAddress());
            dto.setGroupId(pc.getGroupId());
            dto.setStatus(pc.getStatus());
            dto.setRole(pc.getRole());
            dto.setLastSeen(pc.getLastSeen());
            return dto;
        }).collect(Collectors.toList());
    }

    public NetworkTrafficDto getNetworkTraffic() {
        // TODO: 실제 트래픽 집계 로직 구현 (현재는 더미 값 반환)
        return new NetworkTrafficDto(0.0, 0.0);
    }

    public DashboardRefreshDto getRefreshSnapshot() {
        DashboardRefreshDto snapshot = new DashboardRefreshDto();
        snapshot.setClassrooms(getClassroomSummaries());
        snapshot.setTraffic(getNetworkTraffic());
        snapshot.setTexts(getWidgetItemsByType("TEXT"));
        snapshot.setImages(getWidgetItemsByType("IMAGE"));
        return snapshot;
    }

    public List<ClassroomIpRange> getClassroomIpRanges(Long groupId) {
        return ipRangeRepository.findByGroupId(groupId);
    }

    public ClassroomIpRange saveClassroomIpRange(ClassroomIpRange range) {
        return ipRangeRepository.save(range);
    }

    public void deleteClassroomIpRange(Long id) {
        ipRangeRepository.deleteById(id);
    }

    public List<ClassroomTeacherDevice> getTeacherDevices(Long groupId) {
        return teacherDeviceRepository.findByGroupId(groupId);
    }

    public ClassroomTeacherDevice saveTeacherDevice(ClassroomTeacherDevice device) {
        return teacherDeviceRepository.save(device);
    }

    public void deleteTeacherDevice(Long id) {
        teacherDeviceRepository.deleteById(id);
    }

    public PcStatusDto markPcAsBroken(String macAddress, String note) {
        PcMaster pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        pc.setStatus("BROKEN");
        pcMasterRepository.save(pc);

        PcStatusDto dto = new PcStatusDto();
        dto.setMacAddress(pc.getMacAddress());
        dto.setPcName(pc.getPcName());
        dto.setIpAddress(pc.getIpAddress());
        dto.setGroupId(pc.getGroupId());
        dto.setStatus(pc.getStatus());
        dto.setRole(pc.getRole());
        dto.setLastSeen(pc.getLastSeen());
        return dto;
    }

    private List<java.util.Map<String, Object>> getWidgetItemsByType(String type) {
        try {
            return dashboardWidgetItemRepository.findByTypeIgnoreCase(type).stream()
                    .map(this::toWidgetResponse)
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch widget items for type {}", type, e);
            return List.of();
        }
    }

    private java.util.Map<String, Object> toWidgetResponse(DashboardWidgetItem item) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", item.getId());
        out.put("type", item.getType());
        out.put("x", item.getX());
        out.put("y", item.getY());
        out.put("width", item.getWidth());
        out.put("height", item.getHeight());
        out.put("visible", item.isVisible());
        out.put("config", item.getConfig());
        if (item.getConfig() != null && !item.getConfig().isBlank()) {
            try {
                java.util.Map<String, Object> parsed = objectMapper.readValue(item.getConfig(), new TypeReference<java.util.Map<String, Object>>() {});
                out.putAll(parsed);
            } catch (Exception e) {
                logger.warn("Failed to parse widget config for item {}", item.getId(), e);
            }
        }
        return out;
    }
}
