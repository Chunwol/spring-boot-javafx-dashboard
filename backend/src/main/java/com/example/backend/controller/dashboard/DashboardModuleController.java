package com.example.backend.controller.dashboard;

import com.example.backend.model.dashboard.DashboardModule;
import com.example.backend.service.dashboard.DashboardModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 대시보드 모듈 관리 API
 * 대시보드 위젯(모듈)의 CRUD를 처리합니다. 각 모듈은 위치(x, y), 타입, 설정을 가집니다.
 */
@RestController
@RequestMapping("/api/v1/dashboard/modules")
@RequiredArgsConstructor
@Tag(name = "Dashboard Modules", description = "대시보드 모듈 CRUD API")
public class DashboardModuleController {

    private final DashboardModuleService moduleService;

    @GetMapping
    @Operation(summary = "모든 대시보드 모듈 조회", description = "등록된 모든 대시보드 모듈 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "모듈 목록")
    public List<DashboardModule> findAll() {
        return moduleService.findAll();
    }

    @GetMapping("/group/{groupId}")
    @Operation(summary = "강의실별 모듈 조회", description = "특정 강의실의 대시보드 모듈 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "강의실의 모듈 목록")
    public List<DashboardModule> findByGroup(
            @Parameter(description = "강의실 ID", example = "1") @PathVariable Long groupId) {
        return moduleService.findByGroupId(groupId);
    }

    @PostMapping
    @Operation(
        summary = "모듈 생성",
        description = "새 대시보드 모듈을 생성합니다. type(위젯 종류), config(설정), x/y(위치)를 포함합니다."
    )
    @ApiResponse(responseCode = "200", description = "생성된 모듈")
    public DashboardModule save(@RequestBody DashboardModule m) {
        return moduleService.save(m);
    }

    @PutMapping("/{id}")
    @Operation(summary = "모듈 수정", description = "기존 대시보드 모듈을 수정합니다. 타입, 설정, 위치(x/y) 등을 변경할 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "수정된 모듈")
    @ApiResponse(responseCode = "404", description = "모듈을 찾을 수 없음")
    public ResponseEntity<DashboardModule> update(
            @Parameter(description = "모듈 ID") @PathVariable Long id,
            @RequestBody DashboardModule m) {
        return moduleService.update(id, m)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "모듈 삭제", description = "대시보드 모듈을 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "모듈을 찾을 수 없음")
    public ResponseEntity<Void> delete(
            @Parameter(description = "모듈 ID") @PathVariable Long id) {
        return moduleService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
