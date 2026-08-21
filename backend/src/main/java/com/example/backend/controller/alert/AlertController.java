package com.example.backend.controller.alert;

import com.example.backend.dto.alert.AlertDto;
import com.example.backend.service.alert.AlertCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 운영 알림 조회 및 확인 API
 *
 * <p>에이전트 오프라인, 업데이트 실패 등의 운영 이벤트가 발생하면 알림이 생성된다.
 * 이 컨트롤러는 알림 목록 조회, 미확인 건수, 단건/전체 확인 처리를 담당한다.</p>
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "운영 알림 조회/확인 API")
public class AlertController {

    private final AlertCenterService alertCenterService;

    public AlertController(AlertCenterService alertCenterService) {
        this.alertCenterService = alertCenterService;
    }

    @GetMapping
    @Operation(
        summary = "운영 알림 목록 조회",
        description = "최근 운영 알림을 조회합니다. unreadOnly=true이면 미확인 알림만 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "알림 목록 반환")
    public List<AlertDto> listAlerts(
            @Parameter(description = "조회 건수 (기본 30)", example = "30") @RequestParam(defaultValue = "30") int limit,
            @Parameter(description = "미확인 알림만 조회 여부", example = "false") @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return alertCenterService.listRecent(limit, unreadOnly).stream()
                .map(AlertDto::from)
                .toList();
    }

    @GetMapping("/unread-count")
    @Operation(
        summary = "미확인 운영 알림 개수 조회",
        description = "아직 확인되지 않은 운영 알림의 개수를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "미확인 알림 개수 반환")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", alertCenterService.unreadCount());
    }

    @PostMapping("/{alertId}/ack")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
        summary = "운영 알림 확인 처리",
        description = "특정 알림을 확인 처리합니다. 확인자(actor)는 인증된 사용자 이름으로 기록됩니다."
    )
    @ApiResponse(responseCode = "200", description = "확인 처리 성공")
    @ApiResponse(responseCode = "401", description = "인증 필요")
    @ApiResponse(responseCode = "404", description = "해당 alertId 없음")
    public ResponseEntity<?> acknowledge(
            @Parameter(description = "알림 ID") @PathVariable Long alertId,
            Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        return alertCenterService.acknowledge(alertId, actor)
                .<ResponseEntity<?>>map(alert -> ResponseEntity.ok(AlertDto.from(alert)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/ack-all")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
        summary = "운영 알림 전체 확인 처리",
        description = "미확인 운영 알림을 모두 확인 처리합니다."
    )
    @ApiResponse(responseCode = "200", description = "처리된 건수 반환")
    @ApiResponse(responseCode = "401", description = "인증 필요")
    public Map<String, Integer> acknowledgeAll(Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        int acknowledged = alertCenterService.acknowledgeAllUnread(actor);
        return Map.of("acknowledged", acknowledged);
    }

    @PostMapping("/ack-selected")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "선택한 운영 알림 확인 처리", description = "화면 필터에 표시된 미확인 알림만 일괄 확인합니다.")
    public Map<String, Integer> acknowledgeSelected(
            @RequestBody Map<String, List<Long>> body,
            Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        int acknowledged = alertCenterService.acknowledgeUnreadByIds(body.get("alertIds"), actor);
        return Map.of("acknowledged", acknowledged);
    }
}
