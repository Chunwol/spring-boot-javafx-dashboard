package com.example.backend.controller.status;

import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.pc.PcMasterRepository;
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

    @Autowired
    public StatusController(PcMasterRepository pcMasterRepository) {
        this.pcMasterRepository = pcMasterRepository;
    }

    /**
     * 모든 PC의 상태 조회
     * 시스템에 등록된 모든 PC의 현재 상태를 조회합니다.
     */
    @GetMapping("/all")
    @Operation(
        summary = "모든 PC 상태 조회",
        description = "시스템에서 관리 중인 모든 PC의 온라인 상태, IP, 역할, 마지막 하트비트 정보를 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "PC 상태 목록 조회 성공")
    public List<PcStatusDto> all() {
        List<PcMaster> all = pcMasterRepository.findAll();
        return all.stream().map(this::toDto).collect(Collectors.toList());
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
        return pcMasterRepository.findById(mac)
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    private PcStatusDto toDto(PcMaster p) {
        PcStatusDto d = new PcStatusDto();
        d.setMacAddress(p.getMacAddress());
        d.setPcName(p.getPcName());
        d.setIpAddress(p.getIpAddress());
        d.setGroupId(p.getGroupId());
        d.setStatus(p.getStatus());
        d.setRole(p.getRole());
        d.setLastSeen(p.getLastSeen());
        return d;
    }
}
