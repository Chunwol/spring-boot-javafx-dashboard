package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.IpRangeBatchRequest;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.service.dashboard.IpRangeService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * IP 범위 관리 API
 * 강의실(그룹)별 IP 범위(CIDR)를 관리합니다.
 */
@RestController
@RequestMapping("/api/v1/ip-ranges")
@RequiredArgsConstructor
@Tag(name = "IP Ranges", description = "강의실별 IP 범위 관리 API")
public class IpRangeController {

    private final IpRangeService ipRangeService;

    @GetMapping
    @Operation(
        summary = "IP 범위 목록 조회",
        description = "등록된 강의실의 IP 범위를 조회합니다. groupId 지정 시 해당 강의실만 필터링합니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 목록 조회 성공")
    public ResponseEntity<Page<ClassroomIpRange>> findAll(
            @Parameter(description = "강의실 ID (옵션 필터)")
            @RequestParam(required = false) Long groupId,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        List<ClassroomIpRange> all = (groupId != null)
                ? ipRangeService.getClassroomIpRanges(groupId)
                : ipRangeService.findAll();
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return ResponseEntity.ok(new PageImpl<>(from >= total ? List.of() : all.subList(from, to),
                PageRequest.of(page, size), total));
    }

    @PostMapping
    @Operation(
        summary = "IP 범위 등록",
        description = "강의실에 IP 범위(CIDR 또는 start-end)를 등록합니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 등록 성공")
    @ApiResponse(responseCode = "400", description = "IP 범위 형식 오류 또는 중복")
    public ResponseEntity<ClassroomIpRange> save(@RequestBody ClassroomIpRange ipRange) {
        return ResponseEntity.ok(ipRangeService.saveClassroomIpRange(ipRange));
    }

    @PostMapping("/batch")
    @Operation(
        summary = "다중 IP 대역 일괄 등록",
        description = "rangeSpec(쉼표/줄바꿈/세미콜론 구분)에 나열된 여러 IP 대역을 한 번에 등록합니다."
    )
    @ApiResponse(responseCode = "200", description = "일괄 등록 성공 (created: 등록 건수, items: 등록 목록)")
    @ApiResponse(responseCode = "400", description = "잘못된 rangeSpec 형식 또는 basePrefix 누락")
    public ResponseEntity<Map<String, Object>> saveBatch(@Valid @RequestBody IpRangeBatchRequest req) {
        List<ClassroomIpRange> saved = ipRangeService.saveBatch(req);
        return ResponseEntity.ok(Map.of("created", saved.size(), "items", saved));
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "IP 범위 삭제",
        description = "등록된 IP 범위를 삭제합니다. 삭제 후 강의실 자동 배정 캐시가 갱신됩니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 삭제 성공")
    public ResponseEntity<Void> delete(
            @Parameter(description = "IP 범위 ID") @PathVariable Long id) {
        ipRangeService.deleteClassroomIpRange(id);
        return ResponseEntity.ok().build();
    }
}
