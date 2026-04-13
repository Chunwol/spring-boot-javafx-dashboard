package com.example.backend.controller;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.service.dashboard.ClassroomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 레거시 프론트 경로 호환용 alias 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiCompatibilityController {

    private final ClassroomService classroomService;

    public ApiCompatibilityController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @GetMapping("/classrooms")
    public ResponseEntity<Page<ClassroomSummaryDto>> listClassroomsAlias(Pageable pageable) {
        return ResponseEntity.ok(classroomService.getClassroomSummariesPaged(pageable));
    }
}
