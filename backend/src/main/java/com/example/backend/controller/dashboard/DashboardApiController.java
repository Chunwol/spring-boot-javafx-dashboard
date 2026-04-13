package com.example.backend.controller.dashboard;

import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.dto.dashboard.NetworkTrafficDto;
import com.example.backend.service.dashboard.DashboardService;
import com.example.backend.service.dashboard.ClassroomService;
import com.example.backend.service.dashboard.PcStatusService;
import com.example.backend.service.dashboard.IpRangeService;
import com.example.backend.service.classroom.ClassroomValidationService;
import com.example.backend.config.TrafficThresholdConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ClassroomService classroomService;
    private final PcStatusService pcStatusService;
    private final IpRangeService ipRangeService;
    private final ClassroomValidationService classroomValidationService;
    private final TrafficThresholdConfig trafficThresholdConfig;

    @Autowired
    public DashboardApiController(DashboardService dashboardService,
                                  ClassroomService classroomService,
                                  PcStatusService pcStatusService,
                                  IpRangeService ipRangeService,
                                  ClassroomValidationService classroomValidationService,
                                  TrafficThresholdConfig trafficThresholdConfig) {
        this.dashboardService = dashboardService;
        this.classroomService = classroomService;
        this.pcStatusService = pcStatusService;
        this.ipRangeService = ipRangeService;
        this.classroomValidationService = classroomValidationService;
        this.trafficThresholdConfig = trafficThresholdConfig;
    }

    /**
     * 모든 강의실의 요약 정보를 조회합니다.
     * @return 강의실 요약 정보 리스트
     */
    @GetMapping("/classrooms")
    @Operation(summary = "모든 강의실 요약 정보 조회", description = "온라인 PC 수, 전체 PC 수, 마지막 하트비트 등 강의실별 요약 정보를 조회합니다. 페이지네이션을 지원합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 요약 조회 성공")
    public ResponseEntity<Page<ClassroomSummaryDto>> getClassroomSummaries(Pageable pageable) {
        return ResponseEntity.ok(classroomService.getClassroomSummariesPaged(pageable));
    }

    /**
     * 특정 강의실에 속한 PC 목록과 상태를 조회합니다.
     * @param classroomId 강의실 ID
     * @return PC 상태 정보 리스트
     */
    @GetMapping("/classrooms/{classroomId}/pcs")
    @Operation(summary = "특정 강의실의 PC 상태 조회", description = "지정된 강의실에 속한 모든 PC의 현재 상태(온라인/오프라인, 역할 등)를 조회합니다. 페이지네이션을 지원합니다.")
    @ApiResponse(responseCode = "200", description = "PC 상태 조회 성공")
    public ResponseEntity<Page<PcStatusDto>> getPcsInClassroom(
            @Parameter(description = "강의실 ID") @PathVariable Long classroomId,
            Pageable pageable) {
        return ResponseEntity.ok(pcStatusService.getPcStatusesPaged(classroomId, pageable));
    }

    @GetMapping("/classrooms/{classroomId}/traffic/pcs")
    @Operation(summary = "강의실별 PC 트래픽 조회", description = "지정된 강의실의 PC별 최신 인바운드/아웃바운드 트래픽을 조회합니다.")
    public ResponseEntity<List<Map<String, Object>>> getPcTrafficInClassroom(
            @Parameter(description = "강의실 ID") @PathVariable Long classroomId) {
        return ResponseEntity.ok(classroomService.getClassroomPcTraffic(classroomId));
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

    /**
     * 트래픽 임계값 설정을 조회합니다.
     * @return 글로벌, 강의실, PC별 트래픽 임계값
     */
    @GetMapping("/config/traffic-thresholds")
    @Operation(summary = "트래픽 임계값 설정 조회", description = "글로벌, 강의실, PC별 트래픽 임계값(Mbps)을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "설정 조회 성공")
    public ResponseEntity<Map<String, Double>> getTrafficThresholds() {
        Map<String, Double> thresholds = new java.util.HashMap<>();
        thresholds.put("globalMbps", trafficThresholdConfig.getGlobalThresholdMbps());
        thresholds.put("classroomMbps", trafficThresholdConfig.getClassroomThresholdMbps());
        thresholds.put("pcMbps", trafficThresholdConfig.getPcThresholdMbps());
        return ResponseEntity.ok(thresholds);
    }

    /** 프론트 호환용 alias: /dashboard/traffic/summary */
    @GetMapping("/traffic/summary")
    public ResponseEntity<NetworkTrafficDto> getNetworkTrafficSummary() {
        return ResponseEntity.ok(dashboardService.getNetworkTraffic());
    }

    @GetMapping("/refresh")
    @Operation(summary = "대시보드 통합 새로고침", description = "강의실, 트래픽, 텍스트, 이미지 위젯을 한 번에 조회합니다.")
    public ResponseEntity<DashboardRefreshDto> getDashboardRefreshSnapshot() {
        return ResponseEntity.ok(dashboardService.getRefreshSnapshot());
    }

    @GetMapping("/classrooms/{groupId}/ip-ranges")
    public ResponseEntity<List<ClassroomIpRange>> getIpRanges(@PathVariable Long groupId) {
        return ResponseEntity.ok(ipRangeService.getClassroomIpRanges(groupId));
    }

    @PostMapping("/classrooms/{groupId}/ip-ranges")
    public ResponseEntity<ClassroomIpRange> addIpRange(@PathVariable Long groupId,
                                                       @RequestBody ClassroomIpRange ipRange) {
        ipRange.setGroupId(groupId);
        classroomValidationService.validateIpRangeForCreate(ipRange);
        return ResponseEntity.ok(ipRangeService.saveClassroomIpRange(ipRange));
    }

    @DeleteMapping("/classrooms/ip-ranges/{id}")
    public ResponseEntity<Void> deleteIpRange(@PathVariable Long id) {
        ipRangeService.deleteClassroomIpRange(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/classrooms/{groupId}/teacher-devices")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(@PathVariable Long groupId) {
        return ResponseEntity.ok(ipRangeService.getTeacherDevices(groupId));
    }

    @PostMapping("/classrooms/{groupId}/teacher-devices")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(@PathVariable Long groupId,
                                                                   @RequestBody ClassroomTeacherDevice device) {
        device.setGroupId(groupId);
        return ResponseEntity.ok(ipRangeService.saveTeacherDevice(device));
    }

    @DeleteMapping("/classrooms/teacher-devices/{id}")
    public ResponseEntity<Void> deleteTeacherDevice(@PathVariable Long id) {
        ipRangeService.deleteTeacherDevice(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pcs/broken")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        String macAddress = payload.get("macAddress");
        String ipAddress = payload.get("ipAddress");
        if ((macAddress == null || macAddress.isBlank()) && (ipAddress == null || ipAddress.isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        return ResponseEntity.ok(pcStatusService.markPcAsBroken(macAddress, ipAddress, note));
    }

    @GetMapping("/pcs/broken")
    public ResponseEntity<List<PcStatusDto>> getBrokenPcs() {
        return ResponseEntity.ok(pcStatusService.getBrokenPcStatuses());
    }

    @DeleteMapping("/pcs/broken/{macAddress}")
    public ResponseEntity<PcStatusDto> clearBrokenPc(@PathVariable String macAddress) {
        return ResponseEntity.ok(pcStatusService.clearBrokenPc(macAddress));
    }

    @PutMapping("/pcs/broken/{macAddress}")
    public ResponseEntity<PcStatusDto> updateBrokenPcNote(@PathVariable String macAddress,
                                                          @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(pcStatusService.updateBrokenPcNote(macAddress, payload.get("note")));
    }
}
