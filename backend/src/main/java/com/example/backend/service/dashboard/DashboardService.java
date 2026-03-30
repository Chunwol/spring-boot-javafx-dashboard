package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.DashboardRefreshDto;
import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.backend.repository.pc.PcMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    private static final String BROKEN_PLACEHOLDER_PREFIX = "BROKEN-IP::";

    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final DashboardWidgetItemRepository dashboardWidgetItemRepository;
    private final TrafficLogsRepository trafficLogsRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final ObjectMapper objectMapper;

    public DashboardService(ClassroomGroupRepository classroomGroupRepository,
                           PcMasterRepository pcMasterRepository,
                           ClassroomIpRangeRepository ipRangeRepository,
                           ClassroomTeacherDeviceRepository teacherDeviceRepository,
                           DashboardWidgetItemRepository dashboardWidgetItemRepository,
                           TrafficLogsRepository trafficLogsRepository,
                           ClassroomResolutionService classroomResolutionService,
                           ObjectMapper objectMapper) {
        this.classroomGroupRepository = classroomGroupRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.dashboardWidgetItemRepository = dashboardWidgetItemRepository;
        this.trafficLogsRepository = trafficLogsRepository;
        this.classroomResolutionService = classroomResolutionService;
        this.objectMapper = objectMapper;
    }

    public List<ClassroomSummaryDto> getClassroomSummaries() {
        try {
            // 먼저 전체 강의실 존재 여부 확인
            List<ClassroomGroup> allGroups = classroomGroupRepository.findAll();
            logger.info("Total classroom groups in DB: {}", allGroups.size());
            allGroups.forEach(g -> logger.debug("  DB Group: id={}, name={}", g.getId(), g.getName()));

            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
            String cutoff = fiveMinutesAgo.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // 단일 LEFT JOIN 쿼리로 그룹 목록 + PC 집계 로드 (DB 쿼리 수: 5→3)
            List<ClassroomGroupRepository.GroupWithPcStatsProjection> groupStats =
                    classroomGroupRepository.findAllWithPcStats(cutoff);
            logger.info("Loaded {} classroom groups from query (cutoff: {})", groupStats.size(), cutoff);
            groupStats.forEach(g -> logger.debug("  - Group: id={}, name={}, total={}, online={}", 
                    g.getGroupId(), g.getGroupName(), g.getTotalCount(), g.getOnlineCount()));

            List<Long> groupIds = groupStats.stream()
                    .map(ClassroomGroupRepository.GroupWithPcStatsProjection::getGroupId)
                    .toList();
            Map<Long, PcStats> statsByGroup = new HashMap<>();
            for (ClassroomGroupRepository.GroupWithPcStatsProjection s : groupStats) {
                statsByGroup.put(s.getGroupId(), new PcStats(
                        s.getTotalCount() == null ? 0L : s.getTotalCount(),
                        s.getOnlineCount() == null ? 0L : s.getOnlineCount()
                ));
            }

            // 교사 상태 판단용 PC 목록 + IP 대역 + 교사 장치 로드
            List<PcMaster> groupPcs = groupIds.isEmpty() ? List.of() : pcMasterRepository.findByGroupIdIn(groupIds);
            List<ClassroomIpRange> allIpRanges = ipRangeRepository.findAll();
            List<ClassroomTeacherDevice> allTeachers = teacherDeviceRepository.findAll();

            List<ClassroomSummaryDto> summaries = new ArrayList<>();

            for (ClassroomGroupRepository.GroupWithPcStatsProjection groupStat : groupStats) {
                Long groupId = groupStat.getGroupId();
                logger.info("Processing classroom: groupId={}, name={}", groupId, groupStat.getGroupName());

                List<PcMaster> pcs = groupPcs.stream()
                        .filter(pc -> pc.getGroupId() != null && pc.getGroupId().equals(groupId))
                        .toList();

                PcStats stats = statsByGroup.getOrDefault(groupId, PcStats.EMPTY);
                long onlineCount = stats.onlineCount();
                long totalCount = stats.totalCount();

                List<String> ipRanges = allIpRanges.stream()
                        .filter(r -> r.getGroupId() != null && r.getGroupId().equals(groupId))
                        .map(r -> {
                            if (r.getCidr() != null && !r.getCidr().isBlank()) return r.getCidr();
                            if (r.getStartIp() != null && r.getEndIp() != null) return r.getStartIp() + "~" + r.getEndIp();
                            return "";
                        })
                        .filter(s -> s != null && !s.isBlank())
                        .toList();

                String teacherStatus = "OFFLINE";
                List<ClassroomTeacherDevice> teachers = allTeachers.stream()
                        .filter(t -> t.getGroupId() != null && t.getGroupId().equals(groupId))
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
                summary.setGroupId(groupId);
                summary.setGroupName(groupStat.getGroupName());
                summary.setOnlinePcs((int) onlineCount);
                summary.setTotalPcs((int) totalCount);
                summary.setOnlinePcCount((int) onlineCount);
                summary.setTotalPcCount((int) totalCount);
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
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(groupId);
        return pcs.stream().map(this::toPcStatusDto).collect(Collectors.toList());
    }

    public Page<ClassroomSummaryDto> getClassroomSummariesPaged(int page, int size) {
        List<ClassroomSummaryDto> summaries = getClassroomSummaries();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int total = summaries.size();
        int fromIndex = safePage * safeSize;
        if (fromIndex >= total) {
            return new PageImpl<>(List.of(), PageRequest.of(safePage, safeSize), total);
        }
        int toIndex = Math.min(fromIndex + safeSize, total);
        return new PageImpl<>(summaries.subList(fromIndex, toIndex), PageRequest.of(safePage, safeSize), total);
    }

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

    public NetworkTrafficDto getNetworkTraffic() {
        // 현재는 기본값을 반환하며, 트래픽 수집 파이프라인 연동 시 실제 집계값으로 교체됩니다.
        return new NetworkTrafficDto(0.0, 0.0);
    }

    public List<Map<String, Object>> getClassroomPcTraffic(Long classroomId) {
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(classroomId);
        if (pcs.isEmpty()) {
            return List.of();
        }

        List<String> macAddresses = pcs.stream()
                .map(PcMaster::getMacAddress)
                .filter(mac -> mac != null && !mac.isBlank())
                .toList();

        if (macAddresses.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusMinutes(10);
        var logs = trafficLogsRepository.findByMacAddressInAndTimestampBetween(macAddresses, from, now);

        Map<String, com.example.backend.model.traffic.TrafficLog> latestByMac = new HashMap<>();
        for (var log : logs) {
            if (log.getMacAddress() == null) {
                continue;
            }
            var current = latestByMac.get(log.getMacAddress());
            if (current == null || (log.getTimestamp() != null && current.getTimestamp() != null && log.getTimestamp().isAfter(current.getTimestamp()))
                    || (current.getTimestamp() == null && log.getTimestamp() != null)) {
                latestByMac.put(log.getMacAddress(), log);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PcMaster pc : pcs) {
            var log = latestByMac.get(pc.getMacAddress());
            double inboundMbps = log != null && log.getInboundKbps() != null ? log.getInboundKbps() / 1024.0 : 0.0;
            double outboundMbps = log != null && log.getOutboundKbps() != null ? log.getOutboundKbps() / 1024.0 : 0.0;

            Map<String, Object> row = new HashMap<>();
            row.put("pcName", pc.getPcName());
            row.put("ipAddress", pc.getIpAddress());
            row.put("macAddress", pc.getMacAddress());
            row.put("status", pc.getStatus());
            row.put("inboundMbps", inboundMbps);
            row.put("outboundMbps", outboundMbps);
            row.put("updatedAt", log != null ? log.getTimestamp() : null);
            rows.add(row);
        }

        rows.sort(Comparator.comparingDouble((Map<String, Object> row) ->
                ((Number) row.getOrDefault("inboundMbps", 0.0)).doubleValue()
                        + ((Number) row.getOrDefault("outboundMbps", 0.0)).doubleValue())
                .reversed());
        return rows;
    }

    public DashboardRefreshDto getRefreshSnapshot() {
        DashboardRefreshDto snapshot = new DashboardRefreshDto();
        snapshot.setClassrooms(getClassroomSummaries());
        snapshot.setTraffic(getNetworkTraffic());
        // 텍스트/이미지 위젯은 대시보드별 스코핑을 위해 별도 API(/dashboard/texts, /dashboard/images)로 로드
        snapshot.setTexts(List.of());
        snapshot.setImages(List.of());
        return snapshot;
    }

    public List<ClassroomIpRange> getClassroomIpRanges(Long groupId) {
        List<ClassroomIpRange> ranges = ipRangeRepository.findByGroupId(groupId);
        populateGroupNames(ranges);
        return ranges;
    }

    public ClassroomIpRange saveClassroomIpRange(ClassroomIpRange range) {
        ClassroomIpRange saved = ipRangeRepository.save(range);
        classroomResolutionService.evictIpRangeCache();
        return saved;
    }

    public void deleteClassroomIpRange(Long id) {
        ipRangeRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
    }

    public List<ClassroomTeacherDevice> getTeacherDevices(Long groupId) {
        List<ClassroomTeacherDevice> devices = teacherDeviceRepository.findByGroupId(groupId);
        populateTeacherGroupNames(devices);
        return devices;
    }

    public ClassroomTeacherDevice saveTeacherDevice(ClassroomTeacherDevice device) {
        return teacherDeviceRepository.save(device);
    }

    public void deleteTeacherDevice(Long id) {
        teacherDeviceRepository.deleteById(id);
    }

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

    public List<PcStatusDto> getBrokenPcStatuses() {
        return pcMasterRepository.findAll().stream()
                .filter(pc -> "BROKEN".equalsIgnoreCase(pc.getStatus()) || "FAILED".equalsIgnoreCase(pc.getStatus()))
                .map(this::toPcStatusDto)
                .toList();
    }

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

    private boolean isBrokenPlaceholder(PcMaster pc) {
        return pc.getMacAddress() != null && pc.getMacAddress().startsWith(BROKEN_PLACEHOLDER_PREFIX);
    }

    /** groupId -> groupName 맵을 한 번만 조회해 반환합니다. */
    private Map<Long, String> buildGroupNameMap() {
        return classroomGroupRepository.findAll().stream()
                .collect(Collectors.toMap(
                        com.example.backend.model.classroom.ClassroomGroup::getId,
                        com.example.backend.model.classroom.ClassroomGroup::getName,
                        (a, b) -> a));
    }

    private void populateGroupNames(List<ClassroomIpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        ranges.forEach(r -> r.setGroupName(nameMap.getOrDefault(r.getGroupId(), "강의실#" + r.getGroupId())));
    }

    private void populateTeacherGroupNames(List<ClassroomTeacherDevice> devices) {
        if (devices == null || devices.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        devices.forEach(d -> d.setGroupName(nameMap.getOrDefault(d.getGroupId(), "강의실#" + d.getGroupId())));
    }

    public PcStatusDto updateBrokenPcNote(String macAddress, String note) {
        PcMaster pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        pc.setBrokenNote(note == null || note.isBlank() ? "broken" : note.trim());
        pcMasterRepository.save(pc);
        return toPcStatusDto(pc);
    }

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

    private record PcStats(long totalCount, long onlineCount) {
        private static final PcStats EMPTY = new PcStats(0, 0);
    }
}
