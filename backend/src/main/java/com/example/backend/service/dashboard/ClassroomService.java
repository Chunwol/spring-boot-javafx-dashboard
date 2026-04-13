package com.example.backend.service.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.util.PaginationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for classroom-related operations including summaries, PC stats, and traffic management.
 */
@Service
public class ClassroomService {

    private static final Logger logger = LoggerFactory.getLogger(ClassroomService.class);

    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final TrafficLogsRepository trafficLogsRepository;

    public ClassroomService(ClassroomGroupRepository classroomGroupRepository,
                            PcMasterRepository pcMasterRepository,
                            ClassroomIpRangeRepository ipRangeRepository,
                            ClassroomTeacherDeviceRepository teacherDeviceRepository,
                            TrafficLogsRepository trafficLogsRepository) {
        this.classroomGroupRepository = classroomGroupRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.trafficLogsRepository = trafficLogsRepository;
    }

    /**
     * Get comprehensive classroom summaries including online PC counts and teacher status
     */
    public List<ClassroomSummaryDto> getClassroomSummaries() {
        try {
            // Verify all classroom groups exist
            List<ClassroomGroup> allGroups = classroomGroupRepository.findAll();
            logger.info("Total classroom groups in DB: {}", allGroups.size());
            allGroups.forEach(g -> logger.debug("  DB Group: id={}, name={}", g.getId(), g.getName()));

            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
            String cutoff = fiveMinutesAgo.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // Load group list + PC counts with single LEFT JOIN query
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

            // Load PC list, IP ranges, and teacher devices
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

    /**
     * Get paginated classroom summaries
     */
    public Page<ClassroomSummaryDto> getClassroomSummariesPaged(Pageable pageable) {
        List<ClassroomSummaryDto> summaries = getClassroomSummaries();
        return PaginationUtil.paginate(summaries, pageable);
    }

    /**
     * Get classroom PC traffic data for the last 10 minutes
     */
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

        Map<String, TrafficLog> latestByMac = new HashMap<>();
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

    private record PcStats(long totalCount, long onlineCount) {
        private static final PcStats EMPTY = new PcStats(0, 0);
    }
}
