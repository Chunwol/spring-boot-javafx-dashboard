package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.IpRangeBatchRequest;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomValidationService;
import com.example.backend.util.IpRangeUtil;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * IP 범위 관리 API
 * 강의실(그룹)별 IP 범위(CIDR)를 관리합니다.
 * IP 범위는 하트비트 수신 시 PC를 강의실에 자동으로 할당하는데 사용됩니다.
 */
@RestController
@RequestMapping("/api/v1/ip-ranges")
@Tag(name = "IP Ranges", description = "강의실별 IP 범위 관리 API")
public class IpRangeApiController {

    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final ClassroomValidationService classroomValidationService;
    private final ClassroomResolutionService classroomResolutionService;

    @Autowired
    public IpRangeApiController(ClassroomIpRangeRepository ipRangeRepository,
                                ClassroomGroupRepository classroomGroupRepository,
                                ClassroomValidationService classroomValidationService,
                                ClassroomResolutionService classroomResolutionService) {
        this.ipRangeRepository = ipRangeRepository;
        this.classroomGroupRepository = classroomGroupRepository;
        this.classroomValidationService = classroomValidationService;
        this.classroomResolutionService = classroomResolutionService;
    }

    /**
     * 모든 IP 범위 조회
     * 등록된 모든 강의실별 IP 범위를 조회합니다.
     */
    @GetMapping
    @Operation(
        summary = "IP 범위 목록 조회",
        description = "등록된 강의실의 IP 범위를 조회합니다. groupId 지정 시 해당 강의실만 필터링합니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 목록 조회 성공")
    public ResponseEntity<Page<ClassroomIpRange>> getAllIpRanges(
            @Parameter(description = "강의실 ID (옵션 필터)")
            @RequestParam(required = false) Long groupId,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        List<ClassroomIpRange> all = (groupId != null)
                ? ipRangeRepository.findByGroupId(groupId)
                : ipRangeRepository.findAll();
        populateGroupNames(all);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return ResponseEntity.ok(new PageImpl<>(from >= total ? List.of() : all.subList(from, to),
                PageRequest.of(page, size), total));
    }

    /**
     * 신규 IP 범위 등록
     * 강의실에 IP 범위를 등록합니다.
     * 예: 192.168.10.0/24, 192.168.10.1~192.168.10.254
     */
    @PostMapping
    @Transactional
    @Operation(
        summary = "IP 범위 등록",
        description = "강의실에 IP 범위(CIDR 또는 start-end)를 등록합니다. " +
                      "등록 후 이 범위의 PC가 하트비트를 보내면 해당 강의실로 자동 할당됩니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 등록 성공")
    @ApiResponse(responseCode = "400", description = "IP 범위 형식 오류 또는 중복")
    public ResponseEntity<ClassroomIpRange> createIpRange(@RequestBody ClassroomIpRange ipRange) {
        classroomValidationService.validateIpRangeForCreate(ipRange);
        ClassroomIpRange saved = ipRangeRepository.save(ipRange);
        classroomResolutionService.evictIpRangeCache();
        return ResponseEntity.ok(saved);
    }

    /**
     * 다중 비연속 대역 일괄 등록.
     *
     * 예시 1) basePrefix=10.0.1. / rangeSpec=10-20,30-40
     * 예시 2) rangeSpec=10.0.1.10-10.0.1.20,10.0.1.30-10.0.1.40
     */
    /**
     * 다중 비연속 대역 일괄 등록.
     * rangeSpec 내 토큰마다 IP 범위를 생성하고 캐시를 무효화합니다.
     * 부분 실패 시 전체 롤백을 위해 트랜잭션으로 묶습니다.
     *
     * 예시 1) basePrefix=10.0.1. / rangeSpec=10-20,30-40
     * 예시 2) rangeSpec=10.0.1.10-10.0.1.20,10.0.1.30-10.0.1.40
     */
    @PostMapping("/batch")
    @Transactional
    @Operation(
        summary = "다중 IP 대역 일괄 등록",
        description = "rangeSpec(쉼표/줄바꿈/세미콜론 구분)에 나열된 여러 IP 대역을 한 번에 등록합니다. " +
                      "CIDR, start-end, last-octet 범위를 모두 지원합니다."
    )
    @ApiResponse(responseCode = "200", description = "일괄 등록 성공 (created: 등록 건수, items: 등록 목록)")
    @ApiResponse(responseCode = "400", description = "잘못된 rangeSpec 형식 또는 basePrefix 누락")
    public ResponseEntity<Map<String, Object>> createIpRangesBatch(@Valid @RequestBody IpRangeBatchRequest req) {
        List<ClassroomIpRange> saved = new ArrayList<>();
        String normalizedSpec = req.getRangeSpec()
            .replace("\n", ",")
            .replace(";", ",")
            .replace(" ", ",")
            .replace("\t", ",");
        String[] tokens = normalizedSpec.split(",");

        for (String raw : tokens) {
            String token = raw == null ? "" : raw.trim();
            if (token.isBlank()) continue;

            ClassroomIpRange item = new ClassroomIpRange();
            item.setGroupId(req.getGroupId());
            item.setDescription(req.getDescription());

            // CIDR
            if (token.contains("/")) {
                item.setCidr(token);
            } else if (isIpv4RangeToken(token)) {
                // full ipv4 range: a.b.c.d-a.b.c.e or a.b.c.d~a.b.c.e
                String normalized = token.replace("~", "-");
                String[] p = normalized.split("-");
                item.setStartIp(p[0].trim());
                item.setEndIp(p[1].trim());
            } else if (isLastOctetRangeToken(token)) {
                // last-octet range: 10-20 with basePrefix
                String prefix = req.getBasePrefix();
                if (prefix == null || prefix.isBlank()) {
                    throw new IllegalArgumentException("last-octet 범위 사용 시 basePrefix가 필요합니다. 예: 10.0.1.");
                }
                String normalized = token.replace("~", "-");
                String[] p = normalized.split("-");
                int s = Integer.parseInt(p[0].trim());
                int e = Integer.parseInt(p[1].trim());
                if (s < 0 || s > 255 || e < 0 || e > 255) {
                    throw new IllegalArgumentException("IPv4 마지막 옥텟 범위는 0~255 입니다: " + token);
                }
                item.setStartIp(prefix + s);
                item.setEndIp(prefix + e);
            } else {
                throw new IllegalArgumentException("지원하지 않는 rangeSpec 토큰 형식: " + token);
            }

            // 기본 형식 검증
            if (item.getStartIp() != null && !IpRangeUtil.isIpv4(item.getStartIp())) {
                throw new IllegalArgumentException("startIp 형식이 올바르지 않습니다: " + item.getStartIp());
            }
            if (item.getEndIp() != null && !IpRangeUtil.isIpv4(item.getEndIp())) {
                throw new IllegalArgumentException("endIp 형식이 올바르지 않습니다: " + item.getEndIp());
            }

            classroomValidationService.validateIpRangeForCreate(item);
            saved.add(ipRangeRepository.save(item));
        }

        if (!saved.isEmpty()) {
            classroomResolutionService.evictIpRangeCache();
        }

        return ResponseEntity.ok(Map.of(
                "created", saved.size(),
                "items", saved
        ));
    }

    /**
     * IP 범위 삭제
     * @param id IP 범위 ID
     */
    /**
     * IP 범위 삭제
     * 삭제 후 IP 범위 캐시를 무효화합니다.
     * @param id 삭제할 IP 범위 ID
     */
    @DeleteMapping("/{id}")
    @Transactional
    @Operation(
        summary = "IP 범위 삭제",
        description = "등록된 IP 범위를 삭제합니다. 삭제 후 강의실 자동 배정 캐시가 갱신됩니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 삭제 성공")
    @ApiResponse(responseCode = "404", description = "IP 범위를 찾을 수 없음")
    public ResponseEntity<Void> deleteIpRange(
            @Parameter(description = "IP 범위 ID") @PathVariable Long id) {
        ipRangeRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
        return ResponseEntity.ok().build();
    }

    private void populateGroupNames(List<ClassroomIpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        Map<Long, String> nameMap = classroomGroupRepository.findAll().stream()
                .collect(Collectors.toMap(
                        com.example.backend.model.classroom.ClassroomGroup::getId,
                        com.example.backend.model.classroom.ClassroomGroup::getName,
                        (a, b) -> a));
        ranges.forEach(r -> r.setGroupName(nameMap.getOrDefault(r.getGroupId(), "강의실#" + r.getGroupId())));
    }

    private static boolean isIpv4RangeToken(String token) {
        String normalized = token.replace("~", "-");
        String[] p = normalized.split("-");
        return p.length == 2 && IpRangeUtil.isIpv4(p[0].trim()) && IpRangeUtil.isIpv4(p[1].trim());
    }

    private static boolean isLastOctetRangeToken(String token) {
        return token.matches("^\\d{1,3}\\s*[-~]\\s*\\d{1,3}$");
    }
}