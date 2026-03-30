package com.example.backend.controller.dashboard;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.dto.dashboard.PcStatusDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Classroom Devices", description = "교사 PC 및 고장 PC 관리 API")
public class ClassroomDevicesApiController {
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final PcMasterRepository pcMasterRepository;

    @Autowired
    public ClassroomDevicesApiController(ClassroomTeacherDeviceRepository teacherDeviceRepository,
                                         PcMasterRepository pcMasterRepository) {
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.pcMasterRepository = pcMasterRepository;
    }

    @GetMapping("/{groupId}/teacher-devices")
    @Operation(summary = "강의실 교사 PC 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public ResponseEntity<List<ClassroomTeacherDevice>> getTeacherDevices(@PathVariable Long groupId) {
        return ResponseEntity.ok(teacherDeviceRepository.findByGroupId(groupId));
    }

    @PostMapping("/{groupId}/teacher-devices")
    @Operation(summary = "강의실 교사 PC 등록")
    public ResponseEntity<ClassroomTeacherDevice> addTeacherDevice(@PathVariable Long groupId,
                                                                   @RequestBody ClassroomTeacherDevice device) {
        device.setGroupId(groupId);
        ClassroomTeacherDevice saved = teacherDeviceRepository.save(device);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/teacher-devices/{id}")
    @Operation(summary = "강의실 교사 PC 삭제")
    public ResponseEntity<Void> deleteTeacherDevice(@PathVariable Long id) {
        teacherDeviceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pcs/broken")
    @Operation(summary = "PC를 고장 상태로 표시")
    public ResponseEntity<PcStatusDto> markBrokenPc(@RequestBody Map<String, String> payload) {
        String macAddress = payload.get("macAddress");
        if (macAddress == null || macAddress.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String note = payload.getOrDefault("note", "broken");
        PcMaster pc = pcMasterRepository.findById(macAddress)
                .orElseThrow(() -> new IllegalArgumentException("PC를 찾을 수 없습니다: " + macAddress));
        pc.setStatus("BROKEN");
        pc.setLastSeen(LocalDateTime.now());
        pcMasterRepository.save(pc);
        PcStatusDto dto = new PcStatusDto();
        dto.setMacAddress(pc.getMacAddress());
        dto.setPcName(pc.getPcName());
        dto.setIpAddress(pc.getIpAddress());
        dto.setGroupId(pc.getGroupId());
        dto.setRole(pc.getRole());
        dto.setStatus(pc.getStatus());
        dto.setLastSeen(pc.getLastSeen());
        return ResponseEntity.ok(dto);
    }
}
