package com.example.backend.controller;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.service.dashboard.ClassroomService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 레거시 프론트 경로 호환용 alias 컨트롤러.
 *
 * <p>/api/v1/classrooms 경로는 구버전 프론트엔드가 사용하던 URL이다.
 * 신규 엔드포인트는 /api/v1/dashboard/classrooms 를 사용한다.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Compatibility", description = "레거시 URL 호환 API (신규 개발에는 /api/v1/dashboard 사용 권장)")
public class ApiCompatibilityController {

    private final ClassroomService classroomService;

    /**
     * 구버전 프론트엔드 호환용 강의실 목록 조회.
     * 신규 연동은 GET /api/v1/dashboard/classrooms 를 사용할 것.
     */
    @Hidden
    @GetMapping("/classrooms")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 목록 조회 (DEPRECATED)", description = "구버전 호환 alias. GET /api/v1/dashboard/classrooms 를 사용하세요.")
    public ResponseEntity<Page<ClassroomSummaryDto>> listClassroomsAlias(Pageable pageable) {
        return ResponseEntity.ok(classroomService.getClassroomSummariesPaged(pageable));
    }
}
