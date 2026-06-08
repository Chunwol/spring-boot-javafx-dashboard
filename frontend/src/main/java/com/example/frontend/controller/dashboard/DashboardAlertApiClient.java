package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 운영 경보 관련 API 호출 래퍼.
 *
 * <p>알림 타입 필터 ({@link #setHiddenTypes})를 설정하면
 * {@link #fetchAlertSnapshot}에서 해당 타입의 알림을 제외한다.</p>
 */
public class DashboardAlertApiClient {

    /** 숨길 alert type 집합 (시스템 설정에서 변경 가능). */
    private static final Set<String> HIDDEN_TYPES = ConcurrentHashMap.newKeySet();

    private final ApiClient apiClient;

    public DashboardAlertApiClient(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    // ── 필터 관리 ───────────────────────────────────────────────────────────

    /** 숨길 alert type 목록을 교체한다. 빈 Set을 넣으면 필터 해제. */
    public static void setHiddenTypes(Set<String> types) {
        HIDDEN_TYPES.clear();
        if (types != null) HIDDEN_TYPES.addAll(types);
    }

    public static Set<String> getHiddenTypes() {
        return Collections.unmodifiableSet(HIDDEN_TYPES);
    }

    public static boolean shouldDisplay(DashboardAlertItem item) {
        if (item == null) {
            return false;
        }
        String alertType = item.getAlertType();
        return alertType == null || !HIDDEN_TYPES.contains(alertType);
    }

    public static List<DashboardAlertItem> filterVisibleAlerts(List<DashboardAlertItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (HIDDEN_TYPES.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(DashboardAlertApiClient::shouldDisplay)
                .collect(Collectors.toList());
    }

    // ── API 호출 ───────────────────────────────────────────────────────────

    public CompletableFuture<List<DashboardAlertItem>> fetchAlertSnapshot(int limit) {
        int safeLimit = Math.max(1, limit);
        return apiClient.getJson("/alerts?limit=" + safeLimit, new TypeReference<List<DashboardAlertItem>>() {})
                .thenApply(list -> {
                    if (list == null) return List.of();
                    return filterVisibleAlerts(list);
                });
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
