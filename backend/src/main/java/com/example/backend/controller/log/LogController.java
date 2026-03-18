package com.example.backend.controller.log;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
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
        description = "최근 네트워크 트래픽 로그를 시간 역순으로 조회합니다. 기본값 최근 50개입니다."
    )
    @ApiResponse(responseCode = "200", description = "트래픽 로그 목록")
    public List<?> recentLogs(
            @Parameter(description = "반환할 로그 최대 개수, 기본값 50", example = "50") @RequestParam(defaultValue = "50") int limit) {
        List<TrafficLog> all = trafficLogRepository.findAll();
        return all.stream()
                .sorted((a,b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(limit)
                .map(l -> java.util.Map.of("timestamp", l.getTimestamp(), "message", "Traffic " + l.getInboundKbps() + " KB/s"))
                .collect(Collectors.toList());
    }
}
