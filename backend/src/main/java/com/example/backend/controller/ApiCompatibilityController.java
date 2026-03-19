package com.example.backend.controller;

import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.service.dashboard.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.Page;

/**
 * 레거시 프론트 경로 호환용 alias 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiCompatibilityController {

    private final DashboardService dashboardService;

    public ApiCompatibilityController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/classrooms")
    public ResponseEntity<Page<ClassroomSummaryDto>> listClassroomsAlias(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(dashboardService.getClassroomSummariesPaged(page, size));
    }
}
