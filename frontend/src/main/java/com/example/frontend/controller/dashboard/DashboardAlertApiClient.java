package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 운영 경보 관련 API 호출 래퍼.
 */
public class DashboardAlertApiClient {

    private final ApiClient apiClient;

    public DashboardAlertApiClient(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    public CompletableFuture<List<DashboardAlertItem>> fetchAlertSnapshot(int limit) {
        int safeLimit = Math.max(1, limit);
        return apiClient.getJson("/alerts?limit=" + safeLimit, new TypeReference<List<DashboardAlertItem>>() {});
    }

    public CompletableFuture<Long> fetchUnreadCount() {
        return apiClient
                .getJson("/alerts/unread-count", new TypeReference<Map<String, Object>>() {})
                .thenApply(payload -> {
                    Object unread = payload == null ? null : payload.get("unread");
                    if (unread instanceof Number number) {
                        return Math.max(0L, number.longValue());
                    }
                    return 0L;
                });
    }

    public CompletableFuture<Void> acknowledgeAlert(Long alertId) {
        if (alertId == null) {
            return failedFuture(new IllegalArgumentException("alertId must not be null"));
        }
        return apiClient.postJson("/alerts/" + alertId + "/ack", Map.of()).thenApply(resp -> null);
    }

    public CompletableFuture<Void> acknowledgeAllAlerts() {
        return apiClient.postJson("/alerts/ack-all", Map.of()).thenApply(resp -> null);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
