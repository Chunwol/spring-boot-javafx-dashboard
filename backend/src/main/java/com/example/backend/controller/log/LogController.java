package com.example.backend.controller.log;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 로그/리포트 API
 * 트래픽 로그 및 강의실 사용율 리포트를 조회합니다.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Logs", description = "로그 및 리포트 API")
public class LogController {

    private final TrafficLogRepository trafficLogRepository;

    @Autowired
    public LogController(TrafficLogRepository trafficLogRepository) {
        this.trafficLogRepository = trafficLogRepository;
    }

    /**
     * 최근 트래픽 로그 조회
     */
    @GetMapping("/logs")
    @Operation(
        summary = "트래픽 로그 조회",
        description = "최근 네트워크 트래픽 로그를 시간 역순으로 조회합니다. 페이지네이션을 지원합니다."
    )
    @ApiResponse(responseCode = "200", description = "트래픽 로그 목록")
    public Page<?> recentLogs(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        List<TrafficLog> all = trafficLogRepository.findAll();
        List<java.util.Map<String, Object>> sorted = all.stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .map(l -> java.util.Map.<String, Object>of(
                        "timestamp", l.getTimestamp(),
                        "message", "Traffic " + l.getInboundKbps() + " KB/s"))
                .collect(Collectors.toList());
        int total = sorted.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return new PageImpl<>(from >= total ? List.of() : sorted.subList(from, to),
                PageRequest.of(page, size), total);
    }
}
