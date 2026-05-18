package com.example.backend.controller.status;

import com.example.dto.dashboard.PcStatusDto;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.status.PerformanceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PC 상태 조회 API
 * 모든 PC의 온라인/오프라인 상태, IP, 역할 등을 조회합니다.
 */
@RestController
@RequestMapping("/api/v1/status")
@Tag(name = "Status", description = "PC 상태 조회 API")
public class StatusController {

    private final PcMasterRepository pcMasterRepository;
    private final PerformanceStatusService performanceStatusService;
    private final ClassroomGroupRepository classroomGroupRepository;

    @Autowired
    public StatusController(PcMasterRepository pcMasterRepository,
                            PerformanceStatusService performanceStatusService,
                            ClassroomGroupRepository classroomGroupRepository) {
        this.pcMasterRepository = pcMasterRepository;
        this.performanceStatusService = performanceStatusService;
        this.classroomGroupRepository = classroomGroupRepository;
    }

    /**
     * 모든 PC의 상태 조회
     * 시스템에 등록된 모든 PC의 현재 상태를 조회합니다.
     */
    @GetMapping("/all")
    @Operation(
        summary = "모든 PC 상태 조회",
        description = "시스템에서 관리 중인 모든 PC의 온라인 상태, IP, 역할, 강의실명, 마지막 하트비트 정보를 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "PC 상태 목록 조회 성공")
    public List<PcStatusDto> all() {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findAll().stream()
            .map(p -> toDto(p, groupNames))
            .collect(Collectors.toList());
    }

    /**
     * 특정 PC의 상태 조회 (MAC 주소로)
     * @param mac PC의 MAC 주소
     * @return PC 상태 정보
     */
    @GetMapping("/{mac}")
    @Operation(
        summary = "특정 PC 상태 조회",
        description = "MAC 주소로 특정 PC의 상태(온라인/오프라인, IP, 역할 등)를 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "PC 상태 조회 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<PcStatusDto> getByMac(
            @Parameter(description = "PC MAC 주소", example = "00:1A:2B:3C:4D:5E") @PathVariable String mac) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findById(mac)
                .map(p -> ResponseEntity.ok(toDto(p, groupNames)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 특정 역할 PC 목록 조회 (TEACHER / STUDENT)
     */
    @GetMapping("/role/{role}")
    @Operation(summary = "역할별 PC 조회", description = "역할(TEACHER/STUDENT)로 PC 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 목록 조회 성공")
    @ApiResponse(responseCode = "400", description = "유효하지 않은 역할 값")
    public List<PcStatusDto> byRole(
            @Parameter(description = "역할 (TEACHER 또는 STUDENT)", example = "STUDENT") @PathVariable String role) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findAll().stream()
            .filter(p -> role.equalsIgnoreCase(p.getRole()))
            .map(p -> toDto(p, groupNames))
            .collect(Collectors.toList());
    }

    /**
     * 특정 강의실 PC 목록 조회 (groupId로)
     */
    @GetMapping("/classroom/{groupId}")
    @Operation(summary = "강의실별 PC 조회", description = "강의실 ID로 PC 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "PC 목록 조회 성공")
    public List<PcStatusDto> byClassroom(
            @Parameter(description = "강의실 ID") @PathVariable Long groupId) {
        Map<Long, String> groupNames = loadGroupNameMap();
        return pcMasterRepository.findByGroupId(groupId).stream()
            .map(p -> toDto(p, groupNames))
            .collect(Collectors.toList());
    }

    /**
     * 실시간 성능/부하 스냅샷 조회.
     * 830대 규모 운영에서 큐 적체, stale 명령, 캐시 hit-rate 등을 즉시 확인한다.
     */
    @GetMapping("/performance")
    @Operation(
        summary = "실시간 성능 스냅샷",
        description = "PC 온라인 비율, 명령 큐 적체, stale 명령, 토큰 상태, 캐시 hit-rate를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "성능 스냅샷 조회 성공")
    public ResponseEntity<java.util.Map<String, Object>> performance() {
        return ResponseEntity.ok(performanceStatusService.snapshot());
    }

    /**
     * 강의실 ID → 강의실 이름 맵을 로드합니다.
     * 중복 ID가 있을 경우 첫 번째 값을 유지합니다.
     */
    private Map<Long, String> loadGroupNameMap() {
        return classroomGroupRepository.findAll().stream()
            .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName, (a, b) -> a));
    }

    private PcStatusDto toDto(PcMaster p, Map<Long, String> groupNames) {
        PcStatusDto d = new PcStatusDto();
        d.setMacAddress(p.getMacAddress());
        d.setPcName(p.getPcName());
        d.setIpAddress(p.getIpAddress());
        d.setGroupId(p.getGroupId());
        d.setGroupName(p.getGroupId() != null ? groupNames.getOrDefault(p.getGroupId(), "-") : "-");
        d.setStatus(p.getStatus());
        d.setOnline("ONLINE".equals(p.getStatus()));
        d.setRole(p.getRole());
        d.setAgentVersion(p.getAgentVersion());
        d.setLastSeen(p.getLastSeen());
        return d;
    }
}
