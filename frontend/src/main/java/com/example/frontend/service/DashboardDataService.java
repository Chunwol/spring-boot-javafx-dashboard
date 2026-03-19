package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.function.Consumer;

public class DashboardDataService {
    private final ApiClient apiClient;

    public DashboardDataService() {
        this(ApiClient.getInstance());
    }

    public DashboardDataService(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public List<ClassroomSummaryDto> fetchClassrooms() throws Exception {
        return apiClient.getJson("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>(){}).get();
    }

    public void fetchRefreshSnapshotAsync(Consumer<DashboardRefreshDto> callback) {
        apiClient.getJson("/dashboard/refresh", new TypeReference<DashboardRefreshDto>() {})
                .thenAccept(callback)
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    callback.accept(new DashboardRefreshDto());
                    return null;
                });
    }
}
