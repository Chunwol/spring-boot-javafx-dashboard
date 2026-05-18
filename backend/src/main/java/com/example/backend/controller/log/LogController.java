package com.example.backend.controller.log;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그/리포트 API
 * 트래픽 로그 및 강의실 사용율 리포트를 조회합니다.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Logs", description = "로그 및 리포트 API")
public class LogController {

    private final TrafficLogsRepository trafficLogRepository;

    @Autowired
    public LogController(TrafficLogsRepository trafficLogRepository) {
        this.trafficLogRepository = trafficLogRepository;
    }

    /**
     * 최근 트래픽 로그 조회.
     * timestamp(발생 시각)와 message(인바운드 KB/s 요약)를 포함한 페이지 응답을 반환합니다.
     */
    @GetMapping("/logs")
    @Operation(
        summary = "트래픽 로그 조회",
        description = "최근 네트워크 트래픽 로그를 시간 역순으로 조회합니다. " +
                      "각 항목은 timestamp와 message(인바운드 KB/s 요약)를 포함합니다. 페이지네이션을 지원합니다."
    )
    @ApiResponse(responseCode = "200", description = "트래픽 로그 목록")
    @ApiResponse(responseCode = "400", description = "page/size 파라미터 오류")
    public Page<?> recentLogs(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 200 권장)", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<TrafficLog> logPage = trafficLogRepository.findAllByOrderByTimestampDesc(pageable);
        // TrafficLog 엔티티를 프론트 표시용 간략 맵으로 변환
        return logPage.map(l -> java.util.Map.<String, Object>of(
                "timestamp", l.getTimestamp(),
                "message", "Traffic " + l.getInboundKbps() + " KB/s"));
    }
}
