package com.example.backend.controller.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.heartbeat.HeartbeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/heartbeat")
@RequiredArgsConstructor
@Tag(name = "Heartbeat", description = "에이전트 하트비트 및 상태 보고 API")
public class HeartbeatController {

    private final HeartbeatService heartbeatService;
    private final AuditService auditService;

    @PostMapping
    @Operation(
        summary = "에이전트 하트비트 수신",
        description = "에이전트로부터 하트비트를 수신하고, 대기 중인 명령이 있으면 응답에 포함합니다."
    )
    @ApiResponse(responseCode = "200", description = "하트비트 수신 성공")
    @ApiResponse(responseCode = "401", description = "에이전트 토큰 인증 실패")
    public ResponseEntity<Map<String, Object>> receiveHeartbeat(
            @RequestBody HeartbeatDto heartbeat,
            HttpServletRequest httpRequest) {

        if (heartbeat == null || heartbeat.getMacAddress() == null || heartbeat.getMacAddress().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "macAddress is required"));
        }

        // 인터셉터가 설정한 인증된 MAC 주소
        String agentMac = (String) httpRequest.getAttribute("agentMac");
        if (agentMac != null && !agentMac.equals(heartbeat.getMacAddress())) {
            return ResponseEntity.status(403).body(Map.of("error", "Token MAC mismatch"));
        }

        // 서비스에서 PC 갱신 + 트래픽 저장 + 명령 조회를 한 트랜잭션으로 처리
        Map<String, Object> pendingCmd = heartbeatService.processHeartbeat(heartbeat);

        auditService.log(heartbeat.getMacAddress(), "HEARTBEAT", "PC",
                heartbeat.getMacAddress(), null, heartbeat.getIpAddress());

        Map<String, Object> resp = new HashMap<>();
        if (pendingCmd != null) {
            resp.put("command", pendingCmd);
        }
        return ResponseEntity.ok(resp);
    }
}
