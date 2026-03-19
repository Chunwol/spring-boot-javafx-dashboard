package com.example.backend.controller.group;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.pc.ClientPosition;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.pc.ClientPositionRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Group Management", description = "강의실(그룹) 관리 API")
public class GroupController {

    private final ClassroomGroupRepository groupRepository;
    private final ClientPositionRepository positionRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomValidationService classroomValidationService;

    @Autowired
    public GroupController(ClassroomGroupRepository groupRepository,
                           ClientPositionRepository positionRepository,
                           PcMasterRepository pcMasterRepository,
                           ClassroomValidationService classroomValidationService) {
        this.groupRepository = groupRepository;
        this.positionRepository = positionRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.classroomValidationService = classroomValidationService;
    }

    @GetMapping
    @Operation(summary = "모든 강의실 조회", description = "전체 강의실(그룹) 목록을 조회합니다. 페이지네이션을 지원합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 목록 조회 성공")
    public Page<ClassroomGroup> listGroups(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        return groupRepository.findAll(org.springframework.data.domain.PageRequest.of(page, size));
    }

    @PostMapping
    @Operation(summary = "신규 강의실 생성", description = "새로운 강의실(그룹)을 생성합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 생성 성공")
    public ClassroomGroup createGroup(@RequestBody ClassroomGroup g) {
        classroomValidationService.validateGroupNameForCreate(g.getName());
        return groupRepository.save(g);
    }

    @PutMapping("/{id}")
    @Operation(summary = "강의실 정보 수정", description = "강의실의 이름과 설명을 수정합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 수정 성공")
    @ApiResponse(responseCode = "404", description = "강의실을 찾을 수 없음")
    public ResponseEntity<ClassroomGroup> updateGroup(
            @Parameter(description = "강의실 ID") @PathVariable Long id,
            @RequestBody ClassroomGroup g) {
        return groupRepository.findById(id).map(existing -> {
            classroomValidationService.validateGroupNameForUpdate(id, g.getName());
            existing.setName(g.getName());
            existing.setDescription(g.getDescription());
            return ResponseEntity.ok(groupRepository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "강의실 삭제", description = "강의실을 삭제합니다. 관련 클라이언트 위치 정보도 정리됩니다.")
    @ApiResponse(responseCode = "204", description = "강의실 삭제 성공")
    @ApiResponse(responseCode = "404", description = "강의실을 찾을 수 없음")
    public ResponseEntity<Void> deleteGroup(@Parameter(description = "강의실 ID") @PathVariable Long id) {
        var opt = groupRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        var g = opt.get();
        List<ClientPosition> positions = positionRepository.findByGroupId(id);
        for (ClientPosition p : positions) {
            p.setGroupId(null);
            positionRepository.save(p);
            PcMaster pm = pcMasterRepository.findById(p.getMacAddress()).orElse(null);
            if (pm != null && pm.getGroupId() != null && pm.getGroupId().equals(id)) {
                pm.setGroupId(null);
                pcMasterRepository.save(pm);
            }
        }
        groupRepository.delete(g);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "IP 주소로 그룹에 PC 할당", description = "IP 주소를 기반으로 PC를 특정 강의실에 할당합니다.")
    @ApiResponse(responseCode = "200", description = "PC 할당 성공")
    @ApiResponse(responseCode = "404", description = "해당 IP의 PC를 찾을 수 없음")
    public ResponseEntity<Void> assignByIp(
            @Parameter(description = "강의실 ID") @PathVariable Long id,
            @Parameter(description = "PC IP 주소") @RequestParam String ip) {
        // find by PcMaster ip
        Optional<PcMaster> pmOpt = pcMasterRepository.findByIpAddress(ip);
        if (pmOpt.isPresent()) {
            PcMaster pm = pmOpt.get();
            pm.setGroupId(id);
            pcMasterRepository.save(pm);
            ClientPosition cp = positionRepository.findByMacAddress(pm.getMacAddress());
            if (cp == null) cp = positionRepository.findByIpAddress(ip);
            if (cp == null) {
                cp = new ClientPosition();
                cp.setMacAddress(pm.getMacAddress());
                cp.setIpAddress(ip);
            }
            cp.setGroupId(id);
            positionRepository.save(cp);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/assign/manual")
    @Operation(summary = "MAC 주소로 그룹에 PC 할당", description = "MAC 주소를 기반으로 PC를 특정 강의실에 할당합니다.")
    @ApiResponse(responseCode = "200", description = "PC 할당 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<Void> assignManual(
            @Parameter(description = "PC MAC 주소") @RequestParam String mac,
            @Parameter(description = "강의실 ID") @RequestParam Long groupId) {
        Optional<PcMaster> pmOpt = pcMasterRepository.findById(mac);
        if (pmOpt.isPresent()) {
            PcMaster pm = pmOpt.get();
            pm.setGroupId(groupId);
            pcMasterRepository.save(pm);
            ClientPosition cp = positionRepository.findByMacAddress(mac);
            if (cp == null) {
                cp = new ClientPosition();
                cp.setMacAddress(mac);
            }
            cp.setGroupId(groupId);
            positionRepository.save(cp);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/positions")
    public ResponseEntity<ClientPosition> savePosition(@PathVariable Long id, @RequestBody ClientPosition pos) {
        // pos.macAddress or ipAddress should be provided
        pos.setGroupId(id);
        ClientPosition existing = null;
        if (pos.getMacAddress() != null) existing = positionRepository.findByMacAddress(pos.getMacAddress());
        if (existing == null && pos.getIpAddress() != null) existing = positionRepository.findByIpAddress(pos.getIpAddress());
        if (existing != null) {
            existing.setX(pos.getX());
            existing.setY(pos.getY());
            existing.setGroupId(id);
            positionRepository.save(existing);
            return ResponseEntity.ok(existing);
        }
        ClientPosition saved = positionRepository.save(pos);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}/positions")
    public List<ClientPosition> getPositions(@PathVariable Long id) {
        List<ClientPosition> positions = positionRepository.findByGroupId(id);
        // enrich with status from PcMaster
        return positions.stream().map(p -> {
            PcMaster pc = pcMasterRepository.findById(p.getMacAddress()).orElse(null);
            if (pc != null) {
                p.setIpAddress(pc.getIpAddress());
            }
            return p;
        }).collect(Collectors.toList());
    }
}
