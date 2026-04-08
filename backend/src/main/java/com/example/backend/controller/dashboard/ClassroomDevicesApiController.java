package com.example.backend.controller.dashboard;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.service.dashboard.PcStatusService;
import com.example.backend.service.dashboard.IpRangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ⚠️ DEPRECATED: This controller is maintained for backward compatibility only.
 * Use DashboardApiController endpoints instead:
 * - GET /api/v1/dashboard/classrooms/{groupId}/teacher-devices
 * - POST /api/v1/dashboard/classrooms/{groupId}/teacher-devices
 * - DELETE /api/v1/dashboard/classrooms/teacher-devices/{id}
 * - POST /api/v1/dashboard/pcs/broken
 * - GET /api/v1/dashboard/pcs/broken
 * - DELETE /api/v1/dashboard/pcs/broken/{macAddress}
 * - PUT /api/v1/dashboard/pcs/broken/{macAddress}
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Classroom Devices (Deprecated)", description = "⚠️ DEPRECATED - Use /api/v1/dashboard endpoints instead")
public class ClassroomDevicesApiController {
    private static final Logger log = LoggerFactory.getLogger(ClassroomDevicesApiController.class);

    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final PcMasterRepository pcMasterRepository;
    private final IpRangeService ipRangeService;
    private final PcStatusService pcStatusService;

    @Autowired
    public ClassroomDevicesApiController(ClassroomTeacherDeviceRepository teacherDeviceRepository,
                                         PcMasterRepository pcMasterRepository,
                                         IpRangeService ipRangeService,
                                         PcStatusService pcStatusService) {
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeService = ipRangeService;
        this.pcStatusService = pcStatusService;
    }

    @Hidden // 숨김 처리 (Swagger에서 제거)
    @GetMapping("/{groupId}/teacher-devices")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 목록 조회 (DEPRECATED)", description = "Use GET /api/v1/dashboard/classrooms/{groupId}/teacher-devices instead")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(@PathVariable Long groupId) {
        log.warn("DEPRECATED endpoint called: GET /{}/teacher-devices. Use GET /api/v1/dashboard/classrooms/{}/teacher-devices instead", groupId, groupId);
        return ResponseEntity.ok(ipRangeService.getTeacherDevices(groupId));
    }

    @Hidden
    @PostMapping("/{groupId}/teacher-devices")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 등록 (DEPRECATED)", description = "Use POST /api/v1/dashboard/classrooms/{groupId}/teacher-devices instead")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(@PathVariable Long groupId,
                                                                   @RequestBody ClassroomTeacherDevice device) {
        log.warn("DEPRECATED endpoint called: POST /{}/teacher-devices. Use POST /api/v1/dashboard/classrooms/{}/teacher-devices instead", groupId, groupId);
        device.setGroupId(groupId);
        return ResponseEntity.ok(ipRangeService.saveTeacherDevice(device));
    }

    @Hidden
    @DeleteMapping("/teacher-devices/{id}")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 삭제 (DEPRECATED)", description = "Use DELETE /api/v1/dashboard/classrooms/teacher-devices/{id} instead")
    public ResponseEntity<Void> deleteTeacherDevice(@PathVariable Long id) {
        log.warn("DEPRECATED endpoint called: DELETE /teacher-devices/{}. Use DELETE /api/v1/dashboard/classrooms/teacher-devices/{} instead", id, id);
        ipRangeService.deleteTeacherDevice(id);
        return ResponseEntity.noContent().build();
    }

    @Hidden
    @PostMapping("/pcs/broken")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "PC를 고장 상태로 표시 (DEPRECATED)", description = "Use POST /api/v1/dashboard/pcs/broken instead - supports both MAC and IP")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        log.warn("DEPRECATED endpoint called: POST /pcs/broken. Use POST /api/v1/dashboard/pcs/broken instead (supports MAC or IP)");
        String macAddress = payload.get("macAddress");
        String ipAddress = payload.get("ipAddress");

        // Fallback to service for better handling
        if ((macAddress == null || macAddress.isBlank()) && (ipAddress == null || ipAddress.isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        return ResponseEntity.ok(pcStatusService.markPcAsBroken(macAddress, ipAddress, note));
    }
}

