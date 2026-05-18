package com.example.backend.controller.group;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.model.pc.ClientPosition;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.ClientPositionRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강의실(그룹) 관리 컨트롤러.
 *
 * <p>강의실 CRUD, PC-그룹 할당, 클라이언트 좌표 관리를 담당합니다.
 * 강의실 삭제 시에는 소속 PC의 미완료 명령도 CANCELLED 처리하여 큐 잔류를 방지합니다.</p>
 */
@RestController
@RequestMapping("/api/v1/groups")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Group Management", description = "강의실(그룹) 관리 API")
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    private final ClassroomGroupRepository groupRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final ClientPositionRepository positionRepository;
    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final ClassroomValidationService classroomValidationService;

    @Autowired
    public GroupController(ClassroomGroupRepository groupRepository,
                           ClassroomIpRangeRepository ipRangeRepository,
                           ClassroomTeacherDeviceRepository teacherDeviceRepository,
                           ClientPositionRepository positionRepository,
                           PcMasterRepository pcMasterRepository,
                           CommandQueueRepository commandQueueRepository,
                           ClassroomResolutionService classroomResolutionService,
                           ClassroomValidationService classroomValidationService) {
        this.groupRepository = groupRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.positionRepository = positionRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.commandQueueRepository = commandQueueRepository;
        this.classroomResolutionService = classroomResolutionService;
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
    @Transactional
    @Operation(
        summary = "강의실 삭제",
        description = "강의실을 삭제합니다. 소속 PC의 groupId를 null로 초기화하고, " +
                      "대기 중인 명령(QUEUED/DELIVERED/ACKED)을 CANCELLED 처리합니다. " +
                      "연관 IP 대역과 교사 장치 정보도 함께 삭제됩니다."
    )
    @ApiResponse(responseCode = "204", description = "강의실 삭제 성공")
    @ApiResponse(responseCode = "404", description = "강의실을 찾을 수 없음")
    public ResponseEntity<Void> deleteGroup(@Parameter(description = "강의실 ID") @PathVariable Long id) {
        var opt = groupRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 1) 클라이언트 좌표 그룹 해제
        List<ClientPosition> positions = positionRepository.findByGroupId(id);
        for (ClientPosition p : positions) {
            p.setGroupId(null);
            positionRepository.save(p);
        }

        // 2) 소속 PC 그룹 해제
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(id);
        for (PcMaster pc : pcs) {
            pc.setGroupId(null);
            pcMasterRepository.save(pc);
        }

        // 3) 삭제 대상 PC들의 미완료 명령을 CANCELLED 처리
        //    강의실이 사라진 PC에 명령이 전달되면 실행 주체가 없어지므로 선제적으로 취소한다.
        Set<String> macAddresses = pcs.stream()
                .map(PcMaster::getMacAddress)
                .filter(mac -> mac != null && !mac.isBlank())
                .collect(Collectors.toSet());

        if (!macAddresses.isEmpty()) {
            List<String> activeStatuses = List.of("QUEUED", "DELIVERED", "ACKED");
            LocalDateTime cancelledAt = LocalDateTime.now();
            int cancelledCount = 0;
            for (String mac : macAddresses) {
                List<CommandQueue> activeCommands =
                        commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(mac, activeStatuses);
                for (CommandQueue cmd : activeCommands) {
                    cmd.setStatus("CANCELLED");
                    cmd.setCompletedAt(cancelledAt);
                    commandQueueRepository.save(cmd);
                    cancelledCount++;
                }
            }
            log.info("강의실 {} 삭제: {} 대의 PC, {} 건의 미완료 명령 CANCELLED 처리",
                    id, macAddresses.size(), cancelledCount);
        }

        // 4) 연관 IP 대역/교사 장치도 정리해야 재생성 후 고아 참조가 남지 않는다.
        ipRangeRepository.deleteByGroupId(id);
        teacherDeviceRepository.deleteByGroupId(id);

        groupRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
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
    @Operation(summary = "클라이언트 좌표 저장", description = "강의실 내 PC의 좌표(x, y)를 저장합니다. MAC 또는 IP 기준으로 기존 레코드를 갱신하거나 새로 생성합니다.")
    @ApiResponse(responseCode = "200", description = "좌표 저장 성공")
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
    @Operation(summary = "강의실 클라이언트 좌표 조회", description = "강의실 내 PC들의 좌표(x, y) 목록을 조회합니다. PcMaster에서 IP를 보강합니다.")
    @ApiResponse(responseCode = "200", description = "좌표 목록 조회 성공")
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
