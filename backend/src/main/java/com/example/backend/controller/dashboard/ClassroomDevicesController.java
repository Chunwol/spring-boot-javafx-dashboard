package com.example.backend.controller.dashboard;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.service.dashboard.IpRangeService;
import com.example.backend.service.dashboard.PcStatusService;
import com.example.dto.dashboard.PcStatusDto;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ⚠️ DEPRECATED: This controller is maintained for backward compatibility only.
 * Use DashboardController endpoints instead.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Classroom Devices (Deprecated)", description = "⚠️ DEPRECATED - Use /api/v1/dashboard endpoints instead")
public class ClassroomDevicesController {

    private static final Logger log = LoggerFactory.getLogger(ClassroomDevicesController.class);

    private final IpRangeService ipRangeService;
    private final PcStatusService pcStatusService;

    @Hidden
    @GetMapping("/{groupId}/teacher-devices")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 목록 조회 (DEPRECATED)", description = "Use GET /api/v1/dashboard/classrooms/{groupId}/teacher-devices instead")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(@PathVariable Long groupId) {
        log.warn("DEPRECATED endpoint called: GET /{}/teacher-devices", groupId);
        return ResponseEntity.ok(ipRangeService.getTeacherDevices(groupId));
    }

    @Hidden
    @PostMapping("/{groupId}/teacher-devices")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 등록 (DEPRECATED)", description = "Use POST /api/v1/dashboard/classrooms/{groupId}/teacher-devices instead")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(@PathVariable Long groupId,
                                                                   @RequestBody ClassroomTeacherDevice device) {
        log.warn("DEPRECATED endpoint called: POST /{}/teacher-devices", groupId);
        device.setGroupId(groupId);
        return ResponseEntity.ok(ipRangeService.saveTeacherDevice(device));
    }

    @Hidden
    @DeleteMapping("/teacher-devices/{id}")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "강의실 교사 PC 삭제 (DEPRECATED)", description = "Use DELETE /api/v1/dashboard/classrooms/teacher-devices/{id} instead")
    public ResponseEntity<Void> deleteTeacherDevice(@PathVariable Long id) {
        log.warn("DEPRECATED endpoint called: DELETE /teacher-devices/{}", id);
        ipRangeService.deleteTeacherDevice(id);
        return ResponseEntity.noContent().build();
    }

    @Hidden
    @PostMapping("/pcs/broken")
    @Deprecated(since = "2026-04-08", forRemoval = true)
    @Operation(summary = "PC를 고장 상태로 표시 (DEPRECATED)", description = "Use POST /api/v1/dashboard/pcs/broken instead")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        log.warn("DEPRECATED endpoint called: POST /pcs/broken");
        String macAddress = payload.get("macAddress");
        String ipAddress = payload.get("ipAddress");
        if ((macAddress == null || macAddress.isBlank()) && (ipAddress == null || ipAddress.isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        return ResponseEntity.ok(pcStatusService.markPcAsBroken(macAddress, ipAddress, note));
    }
}
