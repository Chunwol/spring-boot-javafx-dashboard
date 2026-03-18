package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

public class DashboardDataService {
    private final ApiClient apiClient = ApiClient.getInstance();

    public List<ClassroomSummaryDto> fetchClassrooms() throws Exception {
        return apiClient.getJson("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>(){}).get();
    }
    // 필요시 fetchTexts, fetchImages 등 추가 구현
}
