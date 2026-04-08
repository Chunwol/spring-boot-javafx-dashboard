package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.DashboardRefreshDto;
import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for core dashboard operations including refresh snapshots and widget management.
 * Delegates to specialized services for classroom, PC status, and IP range management.
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final DashboardWidgetItemRepository dashboardWidgetItemRepository;
    private final ClassroomService classroomService;
    private final ObjectMapper objectMapper;

    public DashboardService(DashboardWidgetItemRepository dashboardWidgetItemRepository,
                           ClassroomService classroomService,
                           ObjectMapper objectMapper) {
        this.dashboardWidgetItemRepository = dashboardWidgetItemRepository;
        this.classroomService = classroomService;
        this.objectMapper = objectMapper;
    }

    /**
     * Get classroom summaries for dashboard display
     */
    public List<ClassroomSummaryDto> getClassroomSummaries() {
        return classroomService.getClassroomSummaries();
    }

    /**
     * Get network traffic data (placeholder for future integration)
     */
    public NetworkTrafficDto getNetworkTraffic() {
        // Currently returns default values; will be updated with actual traffic pipeline integration
        return new NetworkTrafficDto(0.0, 0.0);
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
