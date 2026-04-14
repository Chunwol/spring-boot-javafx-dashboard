package com.example.backend.controller.alert;

import com.example.backend.dto.alert.AlertDto;
import com.example.backend.service.alert.AlertCenterService;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "운영 알림 조회/확인 API")
public class AlertController {

    private final AlertCenterService alertCenterService;

    public AlertController(AlertCenterService alertCenterService) {
        this.alertCenterService = alertCenterService;
    }

    @GetMapping
    @Operation(summary = "운영 알림 목록 조회")
    public List<AlertDto> listAlerts(@RequestParam(defaultValue = "30") int limit,
                                     @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return alertCenterService.listRecent(limit, unreadOnly).stream()
                .map(AlertDto::from)
                .toList();
    }

    @GetMapping("/unread-count")
    @Operation(summary = "미확인 운영 알림 개수 조회")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", alertCenterService.unreadCount());
    }

    @PostMapping("/{alertId}/ack")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "운영 알림 확인 처리")
    public ResponseEntity<?> acknowledge(@PathVariable Long alertId, Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        return alertCenterService.acknowledge(alertId, actor)
                .<ResponseEntity<?>>map(alert -> ResponseEntity.ok(AlertDto.from(alert)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
