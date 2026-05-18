package com.example.backend.service.dashboard;

import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.NetworkTrafficDto;
import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service for core dashboard operations including refresh snapshots and widget management.
 * Delegates to specialized services for classroom, PC status, and IP range management.
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    private static final int MIN_TRAFFIC_LOOKBACK_SECONDS = 60;

    private final DashboardWidgetItemRepository dashboardWidgetItemRepository;
    private final ClassroomService classroomService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.dashboard.traffic-lookback-seconds:0}")
    private int dashboardTrafficLookbackSeconds;

    @Value("${app.heartbeat.interval-seconds:120}")
    private int heartbeatIntervalSeconds;

    @Value("${app.traffic.log-min-interval-seconds:30}")
    private int trafficLogMinIntervalSeconds;

    public DashboardService(DashboardWidgetItemRepository dashboardWidgetItemRepository,
                           ClassroomService classroomService,
                           TrafficLogsRepository trafficLogsRepository,
                           ObjectMapper objectMapper) {
        this.dashboardWidgetItemRepository = dashboardWidgetItemRepository;
        this.classroomService = classroomService;
        this.trafficLogsRepository = trafficLogsRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Get classroom summaries for dashboard display
     */
    public List<ClassroomSummaryDto> getClassroomSummaries() {
        return classroomService.getClassroomSummaries();
    }

    /**
     * Get network traffic data from recent traffic logs.
     * Lookback window is adaptive to heartbeat/log intervals to avoid empty windows.
     */
    public NetworkTrafficDto getNetworkTraffic() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusSeconds(resolveTrafficLookbackSeconds());
            List<TrafficLog> recentLogs = trafficLogsRepository.findByTimestampBetween(since, now);
            if (recentLogs.isEmpty()) {
                return new NetworkTrafficDto(0.0, 0.0);
            }

            // 최근 윈도우 내 "PC별 최신값"을 합산해 전체 트래픽을 계산한다.
            // (모든 샘플 평균값은 대부분 0으로 희석되어 실제 부하를 과소표시할 수 있다.)
            Map<String, TrafficLog> latestByMac = new HashMap<>();
            int _anonIdx = 0;
            for (TrafficLog log : recentLogs) {
                if (log == null) {
                    continue;
                }
                String mac = log.getMacAddress();
                String key = (mac == null || mac.isBlank()) ? ("_anon_" + (_anonIdx++)) : mac;
                TrafficLog existing = latestByMac.get(key);
                if (isNewerTrafficLog(log, existing)) {
                    latestByMac.put(key, log);
                }
            }
            if (latestByMac.isEmpty()) {
                return new NetworkTrafficDto(0.0, 0.0);
            }

            double inboundMbps = latestByMac.values().stream()
                    .map(TrafficLog::getInboundKbps)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum() / 1024.0;
            double outboundMbps = latestByMac.values().stream()
                    .map(TrafficLog::getOutboundKbps)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum() / 1024.0;
            return new NetworkTrafficDto(
                    Math.round(inboundMbps * 100.0) / 100.0,
                    Math.round(outboundMbps * 100.0) / 100.0);
        } catch (Exception e) {
            logger.warn("트래픽 데이터 조회 실패: {}", e.getMessage());
            return new NetworkTrafficDto(0.0, 0.0);
        }
    }

    private boolean isNewerTrafficLog(TrafficLog candidate, TrafficLog current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        LocalDateTime candidateTs = candidate.getTimestamp();
        LocalDateTime currentTs = current.getTimestamp();
        if (candidateTs == null) {
            return false;
        }
        if (currentTs == null) {
            return true;
        }
        return candidateTs.isAfter(currentTs);
    }

    private int resolveTrafficLookbackSeconds() {
        if (dashboardTrafficLookbackSeconds > 0) {
            return Math.max(MIN_TRAFFIC_LOOKBACK_SECONDS, dashboardTrafficLookbackSeconds);
        }
        int baseInterval = Math.max(heartbeatIntervalSeconds, trafficLogMinIntervalSeconds);
        if (baseInterval <= 0) {
            baseInterval = MIN_TRAFFIC_LOOKBACK_SECONDS;
        }
        return Math.max(MIN_TRAFFIC_LOOKBACK_SECONDS, baseInterval * 2);
    }

    /**
     * Get complete dashboard refresh snapshot
     */
    public DashboardRefreshDto getRefreshSnapshot() {
        DashboardRefreshDto snapshot = new DashboardRefreshDto();
        snapshot.setClassrooms(getClassroomSummaries());
        snapshot.setTraffic(getNetworkTraffic());
        // Text/image widgets are loaded via separate APIs for dashboard-specific scoping
        snapshot.setTexts(List.of());
        snapshot.setImages(List.of());
        return snapshot;
    }

    /**
     * Get widget items by type
     */
    private List<Map<String, Object>> getWidgetItemsByType(String type) {
        try {
            return dashboardWidgetItemRepository.findByTypeIgnoreCase(type).stream()
                    .map(this::toWidgetResponse)
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch widget items for type {}", type, e);
            return List.of();
        }
    }

    /**
     * Convert DashboardWidgetItem to response map
     */
    private Map<String, Object> toWidgetResponse(DashboardWidgetItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
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
                Map<String, Object> parsed = objectMapper.readValue(item.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                out.putAll(parsed);
            } catch (Exception e) {
                logger.warn("Failed to parse widget config for item {}", item.getId(), e);
            }
        }
        return out;
    }
}
