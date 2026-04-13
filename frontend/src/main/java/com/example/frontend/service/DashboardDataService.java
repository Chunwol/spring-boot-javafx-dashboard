package com.example.frontend.service;

import com.example.dto.dashboard.DashboardRefreshDto;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
