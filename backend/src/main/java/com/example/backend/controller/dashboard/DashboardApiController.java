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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 대시보드 관련 집계 데이터를 제공하는 API 컨트롤러.
 * 강의실/PC 요약, 트래픽 현황, IP 범위, 교사 PC, 고장 PC 관리를 통합합니다.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "대시보드 통계 및 집계 API")
public class DashboardApiController {

    private static final Logger log = LoggerFactory.getLogger(DashboardApiController.class);

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

    /**
     * 프론트 호환용 alias: /dashboard/traffic/summary
     * 트래픽 조회 엔드포인트가 두 경로로 제공됩니다.
     */
    @GetMapping("/traffic/summary")
    @Operation(summary = "네트워크 트래픽 조회 (alias)", description = "/traffic 의 프론트 호환 별칭 엔드포인트입니다.")
    @ApiResponse(responseCode = "200", description = "트래픽 조회 성공")
    public ResponseEntity<NetworkTrafficDto> getNetworkTrafficSummary() {
        return ResponseEntity.ok(dashboardService.getNetworkTraffic());
    }

    @GetMapping("/refresh")
    @Operation(summary = "대시보드 통합 새로고침", description = "강의실, 트래픽, 텍스트, 이미지 위젯을 한 번에 조회합니다.")
    @ApiResponse(responseCode = "200", description = "새로고침 스냅샷 조회 성공")
    public ResponseEntity<DashboardRefreshDto> getDashboardRefreshSnapshot() {
        return ResponseEntity.ok(dashboardService.getRefreshSnapshot());
    }

    /**
     * 강의실의 IP 범위 목록 조회
     * @param groupId 강의실 ID
     */
    @GetMapping("/classrooms/{groupId}/ip-ranges")
    @Operation(summary = "강의실 IP 범위 조회", description = "특정 강의실에 등록된 IP 범위 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "IP 범위 목록 조회 성공")
    public ResponseEntity<List<ClassroomIpRange>> getIpRanges(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId) {
        return ResponseEntity.ok(ipRangeService.getClassroomIpRanges(groupId));
    }

    /**
     * 강의실에 IP 범위 등록
     * 검증 후 저장합니다 — 이 IP 대역 PC는 이후 하트비트 수신 시 해당 강의실로 자동 배정됩니다.
     */
    @PostMapping("/classrooms/{groupId}/ip-ranges")
    @Operation(summary = "강의실 IP 범위 등록", description = "강의실에 새 IP 범위를 등록합니다.")
    @ApiResponse(responseCode = "200", description = "IP 범위 등록 성공")
    @ApiResponse(responseCode = "400", description = "IP 범위 형식 오류 또는 중복")
    public ResponseEntity<ClassroomIpRange> addIpRange(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId,
            @RequestBody ClassroomIpRange ipRange) {
        ipRange.setGroupId(groupId);
        classroomValidationService.validateIpRangeForCreate(ipRange);
        return ResponseEntity.ok(ipRangeService.saveClassroomIpRange(ipRange));
    }

    /**
     * IP 범위 삭제
     * @param id 삭제할 IP 범위 ID
     */
    @DeleteMapping("/classrooms/ip-ranges/{id}")
    @Operation(summary = "강의실 IP 범위 삭제", description = "등록된 IP 범위를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "IP 범위 삭제 성공")
    @ApiResponse(responseCode = "404", description = "IP 범위를 찾을 수 없음")
    public ResponseEntity<Void> deleteIpRange(
            @Parameter(description = "IP 범위 ID") @PathVariable Long id) {
        ipRangeService.deleteClassroomIpRange(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 강의실의 교사 PC 목록 조회
     * @param groupId 강의실 ID
     */
    @GetMapping("/classrooms/{groupId}/teacher-devices")
    @Operation(summary = "강의실 교사 PC 목록 조회", description = "특정 강의실에 등록된 교사 PC(선생님 전용 단말) 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "교사 PC 목록 조회 성공")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId) {
        return ResponseEntity.ok(ipRangeService.getTeacherDevices(groupId));
    }

    /**
     * 강의실에 교사 PC 등록
     * @param groupId 강의실 ID
     * @param device  등록할 교사 PC 정보 (MAC, IP, 설명)
     */
    @PostMapping("/classrooms/{groupId}/teacher-devices")
    @Operation(summary = "강의실 교사 PC 등록", description = "강의실에 교사 PC를 등록합니다.")
    @ApiResponse(responseCode = "200", description = "교사 PC 등록 성공")
    @ApiResponse(responseCode = "400", description = "요청 데이터 오류")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId,
            @RequestBody ClassroomTeacherDevice device) {
        device.setGroupId(groupId);
        return ResponseEntity.ok(ipRangeService.saveTeacherDevice(device));
    }

    /**
     * 교사 PC 삭제
     * @param id 삭제할 교사 PC ID
     */
    @DeleteMapping("/classrooms/teacher-devices/{id}")
    @Operation(summary = "교사 PC 삭제", description = "등록된 교사 PC를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "교사 PC 삭제 성공")
    @ApiResponse(responseCode = "404", description = "교사 PC를 찾을 수 없음")
    public ResponseEntity<Void> deleteTeacherDevice(
            @Parameter(description = "교사 PC ID") @PathVariable Long id) {
        ipRangeService.deleteTeacherDevice(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * PC를 고장 상태로 표시합니다.
     * macAddress 또는 ipAddress 중 하나는 반드시 제공해야 합니다.
     */
    @PostMapping("/pcs/broken")
    @Operation(
        summary = "PC 고장 상태 표시",
        description = "지정된 PC를 고장(BROKEN) 상태로 표시합니다. macAddress 또는 ipAddress 중 하나 필수."
    )
    @ApiResponse(responseCode = "200", description = "PC 고장 상태 표시 성공")
    @ApiResponse(responseCode = "400", description = "macAddress/ipAddress 모두 누락")
    @ApiResponse(responseCode = "404", description = "해당 PC를 찾을 수 없음")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        String macAddress = payload.get("macAddress");
        String ipAddress = payload.get("ipAddress");
        // MAC과 IP 모두 누락인 경우 400 반환
        if ((macAddress == null || macAddress.isBlank()) && (ipAddress == null || ipAddress.isBlank())) {
            log.warn("markBrokenPc 호출 시 macAddress/ipAddress 모두 누락");
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        return ResponseEntity.ok(pcStatusService.markPcAsBroken(macAddress, ipAddress, note));
    }

    /**
     * 고장 상태인 PC 목록 조회
     */
    @GetMapping("/pcs/broken")
    @Operation(summary = "고장 PC 목록 조회", description = "현재 고장(BROKEN) 상태로 표시된 PC 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "고장 PC 목록 조회 성공")
    public ResponseEntity<List<PcStatusDto>> getBrokenPcs() {
        return ResponseEntity.ok(pcStatusService.getBrokenPcStatuses());
    }

    /**
     * PC 고장 상태 해제
     * @param macAddress 해제할 PC의 MAC 주소
     */
    @DeleteMapping("/pcs/broken/{macAddress}")
    @Operation(summary = "PC 고장 상태 해제", description = "고장 상태인 PC를 정상 상태로 복구합니다.")
    @ApiResponse(responseCode = "200", description = "고장 상태 해제 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<PcStatusDto> clearBrokenPc(
            @Parameter(description = "PC MAC 주소") @PathVariable String macAddress) {
        return ResponseEntity.ok(pcStatusService.clearBrokenPc(macAddress));
    }

    /**
     * 고장 PC의 메모 수정
     * @param macAddress 대상 PC의 MAC 주소
     * @param payload    note 필드를 포함한 요청 바디
     */
    @PutMapping("/pcs/broken/{macAddress}")
    @Operation(summary = "고장 PC 메모 수정", description = "고장 상태 PC의 메모(note)를 수정합니다.")
    @ApiResponse(responseCode = "200", description = "메모 수정 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<PcStatusDto> updateBrokenPcNote(
            @Parameter(description = "PC MAC 주소") @PathVariable String macAddress,
            @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(pcStatusService.updateBrokenPcNote(macAddress, payload.get("note")));
    }
}
