package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import com.example.backend.service.dashboard.DashboardService;
import com.example.backend.service.classroom.ClassroomValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 대시보드 관련 집계 데이터를 제공하는 API 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "대시보드 통계 및 집계 API")
public class DashboardApiController {

    private final DashboardService dashboardService;
    private final ClassroomValidationService classroomValidationService;

    @Autowired
    public DashboardApiController(DashboardService dashboardService,
                                  ClassroomValidationService classroomValidationService) {
        this.dashboardService = dashboardService;
        this.classroomValidationService = classroomValidationService;
    }

    /**
     * 모든 강의실의 요약 정보를 조회합니다.
     * @return 강의실 요약 정보 리스트
     */
    @GetMapping("/classrooms")
    @Operation(summary = "모든 강의실 요약 정보 조회", description = "온라인 PC 수, 전체 PC 수, 마지막 하트비트 등 강의실별 요약 정보를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 요약 조회 성공")
    public ResponseEntity<List<ClassroomSummaryDto>> getClassroomSummaries() {
        List<ClassroomSummaryDto> summaries = dashboardService.getClassroomSummaries();
        return ResponseEntity.ok(summaries);
    }

    /**
     * 특정 강의실에 속한 PC 목록과 상태를 조회합니다.
     * @param classroomId 강의실 ID
     * @return PC 상태 정보 리스트
     */
    @GetMapping("/classrooms/{classroomId}/pcs")
    @Operation(summary = "특정 강의실의 PC 상태 조회", description = "지정된 강의실에 속한 모든 PC의 현재 상태(온라인/오프라인, 역할 등)를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 상태 조회 성공")
    public ResponseEntity<List<PcStatusDto>> getPcsInClassroom(
            @Parameter(description = "강의실 ID") @PathVariable Long classroomId) {
        List<PcStatusDto> pcs = dashboardService.getPcStatuses(classroomId);
        return ResponseEntity.ok(pcs);
    }

    /**
     * 전체 네트워크 트래픽 현황을 조회합니다.
     * @return 인바운드/아웃바운드 트래픽 정보
     */
    @GetMapping("/traffic")
    @Operation(summary = "네트워크 트래픽 조회", description = "현재 인바운드/아웃바운드 트래픽(Mbps)을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "트래픽 조회 성공")
    public ResponseEntity<NetworkTrafficDto> getNetworkTraffic() {
        return ResponseEntity.ok(dashboardService.getNetworkTraffic());
    }

    /** 프론트 호환용 alias: /dashboard/traffic/summary */
    @GetMapping("/traffic/summary")
    public ResponseEntity<NetworkTrafficDto> getNetworkTrafficSummary() {
        return ResponseEntity.ok(dashboardService.getNetworkTraffic());
    }

    @GetMapping("/classrooms/{groupId}/ip-ranges")
    public ResponseEntity<List<ClassroomIpRange>> getIpRanges(@PathVariable Long groupId) {
        return ResponseEntity.ok(dashboardService.getClassroomIpRanges(groupId));
    }

    @PostMapping("/classrooms/{groupId}/ip-ranges")
    public ResponseEntity<ClassroomIpRange> addIpRange(@PathVariable Long groupId,
                                                       @RequestBody ClassroomIpRange ipRange) {
        ipRange.setGroupId(groupId);
        classroomValidationService.validateIpRangeForCreate(ipRange);
        return ResponseEntity.ok(dashboardService.saveClassroomIpRange(ipRange));
    }

    @DeleteMapping("/classrooms/ip-ranges/{id}")
    public ResponseEntity<Void> deleteIpRange(@PathVariable Long id) {
        dashboardService.deleteClassroomIpRange(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/classrooms/{groupId}/teacher-devices")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(@PathVariable Long groupId) {
        return ResponseEntity.ok(dashboardService.getTeacherDevices(groupId));
    }

    @PostMapping("/classrooms/{groupId}/teacher-devices")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(@PathVariable Long groupId,
                                                                   @RequestBody ClassroomTeacherDevice device) {
        device.setGroupId(groupId);
        return ResponseEntity.ok(dashboardService.saveTeacherDevice(device));
    }

    @DeleteMapping("/classrooms/teacher-devices/{id}")
    public ResponseEntity<Void> deleteTeacherDevice(@PathVariable Long id) {
        dashboardService.deleteTeacherDevice(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pcs/broken")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        String macAddress = payload.get("macAddress");
        if (macAddress == null || macAddress.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        return ResponseEntity.ok(dashboardService.markPcAsBroken(macAddress, note));
    }
}
