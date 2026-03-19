package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.IpRangeBatchRequest;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.service.classroom.ClassroomValidationService;
import com.example.backend.util.IpRangeUtil;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

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
    private final ClassroomValidationService classroomValidationService;

    @Autowired
    public IpRangeApiController(ClassroomIpRangeRepository ipRangeRepository,
                                ClassroomValidationService classroomValidationService) {
        this.ipRangeRepository = ipRangeRepository;
        this.classroomValidationService = classroomValidationService;
    }

    /**
     * 모든 IP 범위 조회
     * 등록된 모든 강의실별 IP 범위를 조회합니다.
     */
    @GetMapping
    @Operation(
        summary = "IP 범위 목록 조회",
        description = "등록된 모든 강의실의 IP 범위(CIDR 형식)를 조회합니다. 페이지네이션을 지원합니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 목록 조회 성공")
    public ResponseEntity<Page<ClassroomIpRange>> getAllIpRanges(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        List<ClassroomIpRange> all = ipRangeRepository.findAll();
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
    @Operation(
        summary = "IP 범위 등록",
        description = "강의실에 IP 범위(CIDR 또는 start-end)를 등록합니다. " +
                      "등록 후 이 범위의 PC가 하트비트를 보내면 해당 강의실로 자동 할당됩니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 등록 성공")
    public ResponseEntity<ClassroomIpRange> createIpRange(@RequestBody ClassroomIpRange ipRange) {
        classroomValidationService.validateIpRangeForCreate(ipRange);
        return ResponseEntity.ok(ipRangeRepository.save(ipRange));
    }

    /**
     * 다중 비연속 대역 일괄 등록.
     *
     * 예시 1) basePrefix=10.0.1. / rangeSpec=10-20,30-40
     * 예시 2) rangeSpec=10.0.1.10-10.0.1.20,10.0.1.30-10.0.1.40
     */
    @PostMapping("/batch")
    @Operation(summary = "다중 IP 대역 일괄 등록")
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

        return ResponseEntity.ok(Map.of(
                "created", saved.size(),
                "items", saved
        ));
    }

    /**
     * IP 범위 삭제
     * @param id IP 범위 ID
     */
    @DeleteMapping("/{id}")
    @Operation(
        summary = "IP 범위 삭제",
        description = "등록된 IP 범위를 삭제합니다."
    )
    @ApiResponse(responseCode = "200", description = "IP 범위 삭제 성공")
    @ApiResponse(responseCode = "404", description = "IP 범위를 찾을 수 없음")
    public ResponseEntity<Void> deleteIpRange(
            @Parameter(description = "IP 범위 ID") @PathVariable Long id) {
        ipRangeRepository.deleteById(id);
        return ResponseEntity.ok().build();
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