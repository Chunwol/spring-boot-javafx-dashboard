package com.example.backend.controller.dashboard;

import com.example.backend.model.dashboard.DashboardModule;
import com.example.backend.repository.dashboard.DashboardModuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 대시보드 모듈 관리 API
 * 대시보드 위젯(모듈)의 CRUD를 처리합니다. 각 모듈은 위치(x, y), 타입, 설정을 가집니다.
 */
@RestController
@RequestMapping("/api/v1/dashboard/modules")
@Tag(name = "Dashboard Modules", description = "대시보드 모듈 CRUD API")
public class DashboardModuleController {

    private final DashboardModuleRepository moduleRepository;

    @Autowired
    public DashboardModuleController(DashboardModuleRepository moduleRepository) {
        this.moduleRepository = moduleRepository;
    }

    /**
     * 모든 대시보드 모듈 조회
     */
    @GetMapping
    @Operation(
        summary = "모든 대시보드 모듈 조회",
        description = "등록된 모든 대시보드 모듈 목록을 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "모듈 목록")
    public List<DashboardModule> listAll() {
        return moduleRepository.findAll();
    }

    /**
     * 강의실별 모듈 조회
     */
    @GetMapping("/group/{groupId}")
    @Operation(
        summary = "강의실별 모듈 조회",
        description = "특정 강의실의 대시보드 모듈 목록을 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "강의실의 모듈 목록")
    public List<DashboardModule> listByGroup(
            @Parameter(description = "강의실 ID", example = "1") @PathVariable Long groupId) {
        return moduleRepository.findByGroupId(groupId);
    }

    /**
     * 새 모듈 생성
     * type, config, x/y, groupId를 포함한 모듈을 생성합니다.
     */
    @PostMapping
    @Transactional
    @Operation(
        summary = "모듈 생성",
        description = "새 대시보드 모듈을 생성합니다. type(위젯 종류), config(설정), x/y(위치)를 포함합니다."
    )
    @ApiResponse(responseCode = "200", description = "생성된 모듈")
    @ApiResponse(responseCode = "400", description = "필수 필드 누락")
    public DashboardModule create(@RequestBody DashboardModule m) {
        return moduleRepository.save(m);
    }

    /**
     * 모듈 수정
     * 기존 모듈의 타입·설정·위치를 덮어씁니다. ID가 없으면 404를 반환합니다.
     */
    @PutMapping("/{id}")
    @Transactional
    @Operation(
        summary = "모듈 수정",
        description = "기존 대시보드 모듈을 수정합니다. 타입, 설정, 위치(x/y) 등을 변경할 수 있습니다."
    )
    @ApiResponse(responseCode = "200", description = "수정된 모듈")
    @ApiResponse(responseCode = "404", description = "모듈을 찾을 수 없음")
    public ResponseEntity<DashboardModule> update(
            @Parameter(description = "모듈 ID") @PathVariable Long id,
            @RequestBody DashboardModule m) {
        Optional<DashboardModule> opt = moduleRepository.findById(id);
        if (opt.isPresent()) {
            DashboardModule existing = opt.get();
            existing.setType(m.getType());
            existing.setConfig(m.getConfig());
            existing.setX(m.getX());
            existing.setY(m.getY());
            existing.setGroupId(m.getGroupId());
            return ResponseEntity.ok(moduleRepository.save(existing));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 모듈 삭제
     * 존재하지 않는 ID이면 404를 반환합니다.
     */
    @DeleteMapping("/{id}")
    @Transactional
    @Operation(
        summary = "모듈 삭제",
        description = "대시보드 모듈을 삭제합니다."
    )
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "모듈을 찾을 수 없음")
    public ResponseEntity<Void> delete(
            @Parameter(description = "모듈 ID") @PathVariable Long id) {
        if (moduleRepository.existsById(id)) {
            moduleRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
