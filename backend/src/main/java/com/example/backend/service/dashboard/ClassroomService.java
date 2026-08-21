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
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.util.DeviceAddressUtil;
import com.example.backend.util.PaginationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 강의실 관련 비즈니스 로직 서비스.
 *
 * <p>담당 기능:
 * <ul>
 *   <li>강의실 그룹별 PC 온라인/전체 수 집계 ({@link #getClassroomSummaries()})</li>
 *   <li>교사 PC 상태(온라인/오프라인) 판별</li>
 *   <li>강의실 최근 트래픽 조회 ({@link #getClassroomPcTraffic(Long)})</li>
 * </ul>
 *
 * <p>모든 조회 메서드는 읽기 전용 트랜잭션을 사용하여 불필요한 flush를 방지한다.
 */
@Service
public class ClassroomService {

    private static final Logger logger = LoggerFactory.getLogger(ClassroomService.class);

    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final TrafficLogsRepository trafficLogsRepository;
    private final CommandQueueRepository commandQueueRepository;

    public ClassroomService(ClassroomGroupRepository classroomGroupRepository,
                            PcMasterRepository pcMasterRepository,
                            ClassroomIpRangeRepository ipRangeRepository,
                            ClassroomTeacherDeviceRepository teacherDeviceRepository,
                            TrafficLogsRepository trafficLogsRepository,
                            CommandQueueRepository commandQueueRepository) {
        this.classroomGroupRepository = classroomGroupRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.trafficLogsRepository = trafficLogsRepository;
        this.commandQueueRepository = commandQueueRepository;
    }

    /**
     * 전체 강의실 요약 정보를 조회한다.
     *
     * <p>조회 전략: N+1 문제를 방지하기 위해 그룹별 PC 통계를 LEFT JOIN 단일 쿼리로 로드한다.
     * 이후 PC 목록, IP 범위, 교사 디바이스를 인메모리에서 그룹별로 매핑한다.
     *
     * <p>교사 PC 상태 판별: MAC 주소 또는 IP 주소 일치 + {@code lastSeen} 5분 이내를 온라인으로 판정한다.
     *
     * @return 강의실 요약 DTO 목록 (조회 실패 시 빈 목록 반환)
     */
    @Transactional(readOnly = true)
    public List<ClassroomSummaryDto> getClassroomSummaries() {
        try {
            // Verify all classroom groups exist
            List<ClassroomGroup> allGroups = classroomGroupRepository.findAll();
            logger.info("Total classroom groups in DB: {}", allGroups.size());
            allGroups.forEach(g -> logger.debug("  DB Group: id={}, name={}", g.getId(), g.getName()));

            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);

            // Load group list + PC counts with single LEFT JOIN query
            List<ClassroomGroupRepository.GroupWithPcStatsProjection> groupStats =
                    classroomGroupRepository.findAllWithPcStats(fiveMinutesAgo);
            logger.info("Loaded {} classroom groups from query (cutoff: {})", groupStats.size(), fiveMinutesAgo);
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
            Map<Long, Long> activeCommandsByGroup = loadActiveCommandCounts(groupIds);

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
                            boolean macMatch = DeviceAddressUtil.sameMac(t.getMacAddress(), pc.getMacAddress());
                            boolean ipMatch = DeviceAddressUtil.sameIp(t.getIpAddress(), pc.getIpAddress());
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
                summary.setRunningCommandCount(activeCommandsByGroup.getOrDefault(groupId, 0L).intValue());
                summary.setVisible(true);
                summaries.add(summary);
            }

            return summaries;
        } catch (Exception e) {
            logger.error("Failed to fetch classroom summaries", e);
            return new ArrayList<>();
        }
    }

    private Map<Long, Long> loadActiveCommandCounts(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        try {
            return commandQueueRepository.countActiveCommandsByGroupIds(groupIds).stream()
                    .filter(row -> row.getGroupId() != null)
                    .collect(Collectors.toMap(
                            CommandQueueRepository.GroupActiveCommandCountProjection::getGroupId,
                            row -> row.getActiveCount() == null ? 0L : row.getActiveCount(),
                            Long::sum));
        } catch (Exception e) {
            logger.warn("Failed to load classroom active command counts: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 페이징 처리된 강의실 요약 정보를 반환한다.
     *
     * <p>내부적으로 {@link #getClassroomSummaries()}로 전체 목록을 조회한 뒤
     * 인메모리 페이징을 적용한다. 강의실 수가 적으므로 DB 페이징 대신 단순 인메모리 분할을 사용한다.
     *
     * @param pageable 페이지 요청 정보
     * @return 페이징된 강의실 요약 페이지
     */
    @Transactional(readOnly = true)
    public Page<ClassroomSummaryDto> getClassroomSummariesPaged(Pageable pageable) {
        List<ClassroomSummaryDto> summaries = getClassroomSummaries();
        return PaginationUtil.paginate(summaries, pageable);
    }

    /**
     * 특정 강의실의 최근 10분간 PC별 트래픽 데이터를 조회한다.
     *
     * <p>PC별 최신 로그를 선택하여 현재 시점의 트래픽 현황을 반환한다.
     * 결과는 인바운드+아웃바운드 합산 기준으로 내림차순 정렬된다 (트래픽 상위 PC 파악 용이).
     *
     * @param classroomId 강의실 그룹 ID
     * @return PC별 트래픽 정보 맵 목록 (pcName, ipAddress, macAddress, status, inboundMbps, outboundMbps, updatedAt)
     */
    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getClassroomTrafficSummaries() {
        List<ClassroomSummaryDto> classrooms = getClassroomSummaries();
        List<Long> groupIds = classrooms.stream().map(ClassroomSummaryDto::getGroupId).toList();
        if (groupIds.isEmpty()) return List.of();

        List<PcMaster> pcs = pcMasterRepository.findByGroupIdIn(groupIds);
        List<String> macAddresses = pcs.stream()
                .map(PcMaster::getMacAddress)
                .filter(Objects::nonNull)
                .filter(mac -> !mac.isBlank())
                .toList();
        LocalDateTime now = LocalDateTime.now();
        List<TrafficLog> logs = macAddresses.isEmpty()
                ? List.of()
                : trafficLogsRepository.findByMacAddressInAndTimestampBetween(macAddresses, now.minusMinutes(10), now);

        Map<String, TrafficLog> latestByMac = new HashMap<>();
        for (TrafficLog log : logs) {
            String mac = DeviceAddressUtil.normalizeMac(log.getMacAddress());
            if (mac == null) continue;
            TrafficLog current = latestByMac.get(mac);
            if (current == null || current.getTimestamp() == null
                    || (log.getTimestamp() != null && log.getTimestamp().isAfter(current.getTimestamp()))) {
                latestByMac.put(mac, log);
            }
        }

        Map<Long, double[]> totalsByGroup = new HashMap<>();
        Map<Long, LocalDateTime> updatedAtByGroup = new HashMap<>();
        for (PcMaster pc : pcs) {
            if (pc.getGroupId() == null) continue;
            TrafficLog log = latestByMac.get(DeviceAddressUtil.normalizeMac(pc.getMacAddress()));
            if (log == null) continue;
            double[] totals = totalsByGroup.computeIfAbsent(pc.getGroupId(), ignored -> new double[2]);
            totals[0] += log.getInboundKbps() == null ? 0.0 : log.getInboundKbps() / 1024.0;
            totals[1] += log.getOutboundKbps() == null ? 0.0 : log.getOutboundKbps() / 1024.0;
            if (log.getTimestamp() != null) {
                updatedAtByGroup.merge(pc.getGroupId(), log.getTimestamp(),
                        (left, right) -> left.isAfter(right) ? left : right);
            }
        }

        return classrooms.stream().map(classroom -> {
            double[] totals = totalsByGroup.getOrDefault(classroom.getGroupId(), new double[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupId", classroom.getGroupId());
            row.put("groupName", classroom.getGroupName());
            row.put("onlinePcs", classroom.getOnlinePcs());
            row.put("totalPcs", classroom.getTotalPcs());
            row.put("inboundMbps", totals[0]);
            row.put("outboundMbps", totals[1]);
            row.put("updatedAt", updatedAtByGroup.get(classroom.getGroupId()));
            return row;
        }).toList();
    }

    private record PcStats(long totalCount, long onlineCount) {
        private static final PcStats EMPTY = new PcStats(0, 0);
    }
}
