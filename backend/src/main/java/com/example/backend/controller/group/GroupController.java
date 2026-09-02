package com.example.backend.controller.group;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.pc.ClientPosition;
import com.example.backend.service.group.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 강의실(그룹) 관리 컨트롤러.
 *
 * <p>강의실 CRUD, PC-그룹 할당, 클라이언트 좌표 관리를 담당합니다.
 * 모든 비즈니스 로직은 {@link GroupService}에 위임합니다.</p>
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_CLASSROOM_SETTINGS')")
@Tag(name = "Group Management", description = "강의실(그룹) 관리 API")
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    @Operation(summary = "모든 강의실 조회", description = "전체 강의실(그룹) 목록을 조회합니다. 페이지네이션을 지원합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 목록 조회 성공")
    public Page<ClassroomGroup> findAll(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        return groupService.findAll(page, size);
    }

    @PostMapping
    @Operation(summary = "신규 강의실 생성", description = "새로운 강의실(그룹)을 생성합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 생성 성공")
    public ClassroomGroup save(@RequestBody ClassroomGroup g) {
        return groupService.save(g);
    }

    @PutMapping("/{id}")
    @Operation(summary = "강의실 정보 수정", description = "강의실의 이름과 설명을 수정합니다.")
    @ApiResponse(responseCode = "200", description = "강의실 수정 성공")
    @ApiResponse(responseCode = "404", description = "강의실을 찾을 수 없음")
    public ResponseEntity<ClassroomGroup> update(
            @Parameter(description = "강의실 ID") @PathVariable Long id,
            @RequestBody ClassroomGroup g) {
        return groupService.update(id, g)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "강의실 삭제",
        description = "강의실을 삭제합니다. 소속 PC의 groupId를 null로 초기화하고, " +
                      "대기 중인 명령(QUEUED/DELIVERED/ACKED)을 CANCELLED 처리합니다. " +
                      "연관 IP 대역과 교사 장치 정보도 함께 삭제됩니다."
    )
    @ApiResponse(responseCode = "204", description = "강의실 삭제 성공")
    @ApiResponse(responseCode = "404", description = "강의실을 찾을 수 없음")
    public ResponseEntity<Void> delete(
            @Parameter(description = "강의실 ID") @PathVariable Long id) {
        return groupService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "IP 주소로 그룹에 PC 할당", description = "IP 주소를 기반으로 PC를 특정 강의실에 할당합니다.")
    @ApiResponse(responseCode = "200", description = "PC 할당 성공")
    @ApiResponse(responseCode = "404", description = "해당 IP의 PC를 찾을 수 없음")
    public ResponseEntity<Void> assignByIp(
            @Parameter(description = "강의실 ID") @PathVariable Long id,
            @Parameter(description = "PC IP 주소") @RequestParam String ip) {
        return groupService.assignByIp(id, ip)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/assign/manual")
    @Operation(summary = "MAC 주소로 그룹에 PC 할당", description = "MAC 주소를 기반으로 PC를 특정 강의실에 할당합니다.")
    @ApiResponse(responseCode = "200", description = "PC 할당 성공")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 PC를 찾을 수 없음")
    public ResponseEntity<Void> assignByMac(
            @Parameter(description = "PC MAC 주소") @RequestParam String mac,
            @Parameter(description = "강의실 ID") @RequestParam Long groupId) {
        return groupService.assignByMac(mac, groupId)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/positions")
    @Operation(summary = "클라이언트 좌표 저장", description = "강의실 내 PC의 좌표(x, y)를 저장합니다. MAC 또는 IP 기준으로 기존 레코드를 갱신하거나 새로 생성합니다.")
    @ApiResponse(responseCode = "200", description = "좌표 저장 성공")
    public ResponseEntity<ClientPosition> savePosition(
            @PathVariable Long id,
            @RequestBody ClientPosition pos) {
        return ResponseEntity.ok(groupService.savePosition(id, pos));
    }

    @GetMapping("/{id}/positions")
    @Operation(summary = "강의실 클라이언트 좌표 조회", description = "강의실 내 PC들의 좌표(x, y) 목록을 조회합니다. PcMaster에서 IP를 보강합니다.")
    @ApiResponse(responseCode = "200", description = "좌표 목록 조회 성공")
    public List<ClientPosition> findPositions(@PathVariable Long id) {
        return groupService.findPositions(id);
    }
}
