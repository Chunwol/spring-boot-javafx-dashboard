package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.function.Consumer;

public class DashboardDataService {
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);
    private final ApiClient apiClient;

    public DashboardDataService() {
        this(ApiClient.getInstance());
    }

    public DashboardDataService(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetch classrooms asynchronously to avoid blocking FX thread
     */
    public void fetchClassroomsAsync(Consumer<List<ClassroomSummaryDto>> callback) {
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>(){})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch classrooms", ex);
                    callback.accept(List.of());
                    return null;
                });
    }

    /**
     * Synchronous fetch with timeout (for initialization only, not on FX thread)
     * @deprecated Use fetchClassroomsAsync instead
     */
    @Deprecated(since = "2026-04-09", forRemoval = true)
    public List<ClassroomSummaryDto> fetchClassrooms() throws Exception {
        return apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>(){})
                .get(8, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void fetchRefreshSnapshotAsync(Consumer<DashboardRefreshDto> callback) {
        apiClient.getJson("/dashboard/refresh", new TypeReference<DashboardRefreshDto>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch dashboard refresh snapshot", ex);
                    callback.accept(new DashboardRefreshDto());
                    return null;
                });
    }
}
