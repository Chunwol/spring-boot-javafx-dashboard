package com.example.frontend.service;

import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private final ApiClient apiClient = ApiClient.getInstance();

    private static final Map<String, Double> DEFAULT_TRAFFIC_THRESHOLDS = Map.of(
            "globalMbps", 5000.0,
            "classroomMbps", 1000.0,
            "pcMbps", 300.0
    );

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

    public void fetchTrafficThresholdsAsync(Consumer<Map<String, Double>> callback) {
        if (!apiClient.hasBearerToken()) {
            callback.accept(DEFAULT_TRAFFIC_THRESHOLDS);
            return;
        }
        apiClient.getJson("/dashboard/config/traffic-thresholds", new TypeReference<Map<String, Double>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                            ? ex.getCause()
                            : ex;
                    if (cause instanceof ApiClientException apiEx
                            && (apiEx.getStatusCode() == 401 || apiEx.getStatusCode() == 403)) {
                        log.info("Traffic threshold API unavailable for current session; using defaults (status={})",
                                apiEx.getStatusCode());
                    } else {
                        log.warn("Failed to fetch traffic thresholds, using defaults", cause);
                    }
                    callback.accept(DEFAULT_TRAFFIC_THRESHOLDS);
                    return null;
                });
    }

    public void fetchClassroomPcTrafficAsync(Long classroomId, Consumer<List<Map<String, Object>>> callback) {
        if (classroomId == null) {
            callback.accept(List.of());
            return;
        }
        apiClient.getJson("/dashboard/classrooms/" + classroomId + "/traffic/pcs",
                        new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch classroom pc traffic for {}", classroomId, ex);
                    callback.accept(List.of());
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

    public CompletableFuture<String> deleteDashboardAsync(Long dashboardId) {
        return apiClient.delete("/dashboards/" + dashboardId);
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
                .thenRun(() -> Platform.runLater(onDone))
                .exceptionally(ex -> {
                    log.warn("Failed to delete panel {}", panelId, ex);
                    Platform.runLater(onDone);
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

    /**
     * 여러 패널의 위치/크기/Z-순서를 1번의 API 호출로 일괄 업데이트.
     * 정렬·분배·맨앞/맨뒤 등 다중 작업 시 사용.
     *
     * @param items 각 항목은 panelId(필수), x/y/width/height/zIndex/visible(선택) 키를 포함
     */
    public void updatePanelsBatchAsync(Long dashboardId, java.util.List<Map<String, Object>> items, Runnable onDone) {
        try {
            if (items == null || items.isEmpty()) {
                if (onDone != null) Platform.runLater(onDone);
                return;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("items", items);
            apiClient.putJson("/dashboards/" + dashboardId + "/panels/batch-position", body)
                    .thenRun(() -> { if (onDone != null) Platform.runLater(onDone); })
                    .exceptionally(ex -> {
                        log.warn("Failed batch-update panels (dashboardId={}, count={})",
                                dashboardId, items.size(), ex);
                        if (onDone != null) Platform.runLater(onDone);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Invalid batch panel payload", e);
            if (onDone != null) Platform.runLater(onDone);
        }
    }
}
