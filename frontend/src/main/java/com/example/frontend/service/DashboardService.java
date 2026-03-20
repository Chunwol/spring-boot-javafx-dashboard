package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private final ApiClient apiClient = ApiClient.getInstance();

    public void fetchClassroomsAsync(Consumer<List<ClassroomSummaryDto>> callback) {
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch classrooms", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    public void fetchNetworkTrafficAsync(BiConsumer<Double, Double> callback) {
        apiClient.get("/dashboard/traffic")
            .thenAccept(body -> {
                    try {
                var node = apiClient.getObjectMapper().readTree(body);
                        double inTraffic = node.path("inboundMbps").asDouble(0.0);
                        double outTraffic = node.path("outboundMbps").asDouble(0.0);
                        callback.accept(inTraffic, outTraffic);
                    } catch (Exception e) {
                        log.warn("Failed to parse network traffic response", e);
                        callback.accept(0.0, 0.0);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Failed to fetch network traffic", ex);
                    callback.accept(0.0, 0.0);
                    return null;
                });
    }

    public void fetchTextItemsAsync(Consumer<List<java.util.Map<String, Object>>> callback) {
        apiClient.getJson("/dashboard/texts", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch text items", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    public void fetchImageItemsAsync(Consumer<List<java.util.Map<String, Object>>> callback) {
        apiClient.getJson("/dashboard/images", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch image items", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    public void fetchDashboardsAsync(Consumer<List<DashboardInfoDto>> callback) {
        apiClient.getJson("/dashboards", new TypeReference<List<DashboardInfoDto>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch dashboards", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    public void createDashboardAsync(String name, String description, Consumer<DashboardInfoDto> callback) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", name);
            payload.put("description", description);
            payload.put("displayOrder", 0);

            apiClient.postJson("/dashboards", payload, DashboardInfoDto.class)
                    .thenAccept(callback)
                    .exceptionally(ex -> {
                        log.warn("Failed to create dashboard", ex);
                        callback.accept(null);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Invalid dashboard create request", e);
            callback.accept(null);
        }
    }

    public void fetchDashboardPanelsAsync(Long dashboardId, Consumer<List<DashboardPanelDto>> callback) {
        apiClient.getJson("/dashboards/" + dashboardId + "/panels", new TypeReference<List<DashboardPanelDto>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch dashboard panels", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    public void createPanelAsync(Long dashboardId, String panelType, Long refGroupId,
                                  double x, double y, double width, double height,
                                  Consumer<DashboardPanelDto> callback) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("panelType", panelType);
            if (refGroupId != null) payload.put("refGroupId", refGroupId);
            payload.put("x", x);
            payload.put("y", y);
            payload.put("width", width);
            payload.put("height", height);
            payload.put("visible", true);
            payload.put("zIndex", 0);

            apiClient.postJson("/dashboards/" + dashboardId + "/panels", payload, DashboardPanelDto.class)
                    .thenAccept(callback)
                    .exceptionally(ex -> {
                        log.warn("Failed to create panel", ex);
                        callback.accept(null);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Invalid panel create request", e);
            callback.accept(null);
        }
    }

    public void deletePanelAsync(Long dashboardId, Long panelId, Runnable onDone) {
        apiClient.delete("/dashboards/" + dashboardId + "/panels/" + panelId)
                .thenRun(onDone)
                .exceptionally(ex -> {
                    log.warn("Failed to delete panel {}", panelId, ex);
                    onDone.run();
                    return null;
                });
    }

    public void updatePanelPositionAsync(Long dashboardId, Long panelId,
                                          double x, double y, double width, double height) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("x", x);
            payload.put("y", y);
            payload.put("width", width);
            payload.put("height", height);

            apiClient.putJson("/dashboards/" + dashboardId + "/panels/" + panelId, payload).exceptionally(ex -> {
                log.warn("Failed to update panel position {}", panelId, ex);
                return null;
            });
        } catch (Exception e) {
            log.warn("Invalid panel position payload", e);
        }
    }
}
