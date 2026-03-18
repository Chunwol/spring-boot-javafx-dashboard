package com.example.backend.dto.dashboard;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DashboardRefreshDto {
    private List<ClassroomSummaryDto> classrooms = new ArrayList<>();
    private NetworkTrafficDto traffic = new NetworkTrafficDto();
    private List<Map<String, Object>> texts = new ArrayList<>();
    private List<Map<String, Object>> images = new ArrayList<>();
}