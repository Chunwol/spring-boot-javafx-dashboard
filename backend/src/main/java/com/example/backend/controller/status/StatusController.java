package com.example.backend.controller.status;

import com.example.dto.dashboard.PcStatusDto;
import com.example.backend.service.dashboard.PcStatusService;
import com.example.backend.service.status.PerformanceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * PC 상태 조회 API
 */
@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
@Tag(name = "Status", description = "PC 상태 조회 API")
public class StatusController {

    private final PcStatusService pcStatusService;
    private final PerformanceStatusService performanceStatusService;

    @GetMapping("/all")
    @Operation(summary = "모든 PC 상태 조회", description = "시스템에서 관리 중인 모든 PC의 온라인 상태, IP, 역할, 강의실명, 마지막 하트비트 정보를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 상태 목록 조회 성공")
    public List<PcStatusDto> findAll() {
        return pcStatusService.findAll();
    }

    @GetMapping("/{mac}")
    @Operation(summary = "특정 PC 상태 조회", description = "MAC 주소로 특정 PC의 상태를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 상태 조회 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<PcStatusDto> findOne(
            @Parameter(description = "PC MAC 주소", example = "00:1A:2B:3C:4D:5E") @PathVariable String mac) {
        return pcStatusService.findByMac(mac)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/role/{role}")
    @Operation(summary = "역할별 PC 조회", description = "역할(TEACHER/STUDENT)로 PC 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 목록 조회 성공")
    public List<PcStatusDto> findByRole(
            @Parameter(description = "역할 (TEACHER 또는 STUDENT)", example = "STUDENT") @PathVariable String role) {
        return pcStatusService.findByRole(role);
    }

    @GetMapping("/classroom/{groupId}")
    @Operation(summary = "강의실별 PC 조회", description = "강의실 ID로 PC 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 목록 조회 성공")
    public List<PcStatusDto> findByClassroom(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId) {
        return pcStatusService.findByGroupId(groupId);
    }

    @GetMapping("/performance")
    @Operation(summary = "실시간 성능 스냅샷", description = "PC 온라인 비율, 명령 큐 적체, stale 명령, 토큰 상태, 캐시 hit-rate를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "성능 스냅샷 조회 성공")
    public ResponseEntity<Map<String, Object>> performance() {
        return ResponseEntity.ok(performanceStatusService.snapshot());
    }
}
