package com.example.backend.controller.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.dto.heartbeat.HeartbeatOfflineDto;
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

/**
 * 에이전트 하트비트 수신 컨트롤러.
 *
 * <p>에이전트는 일정 주기로 {@code POST /api/v1/heartbeat} 를 호출하여
 * 자신의 온라인 상태, IP, 트래픽 등 메트릭을 보고한다.
 * 서버는 이 시점에 대기 중인 명령이 있으면 응답 본문에 포함해 즉시 전달한다.</p>
 *
 * <p>에이전트 종료 시에는 {@code POST /api/v1/heartbeat/offline} 으로 선신고하여
 * 타임아웃 기반 오프라인 감지보다 빠르게 OFFLINE 상태로 전환된다.</p>
 */
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
        description = "에이전트로부터 하트비트를 수신하고, 대기 중인 명령이 있으면 응답에 포함합니다. " +
                      "응답에 command 필드가 있으면 에이전트가 즉시 실행해야 합니다."
    )
    @ApiResponse(responseCode = "200", description = "하트비트 수신 성공")
    @ApiResponse(responseCode = "400", description = "macAddress 누락")
    @ApiResponse(responseCode = "401", description = "에이전트 토큰 인증 실패")
    @ApiResponse(responseCode = "403", description = "토큰 MAC과 요청 MAC 불일치")
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

    @PostMapping("/offline")
    @Operation(
        summary = "에이전트 종료/재부팅 오프라인 선신고",
        description = "에이전트 종료 훅에서 호출되어 즉시 OFFLINE 상태를 반영합니다. " +
                      "하트비트 타임아웃 감지보다 훨씬 빠르게 상태가 전환됩니다."
    )
    @ApiResponse(responseCode = "200", description = "오프라인 선신고 수신 성공")
    @ApiResponse(responseCode = "400", description = "macAddress 누락")
    @ApiResponse(responseCode = "401", description = "에이전트 토큰 인증 실패")
    @ApiResponse(responseCode = "403", description = "토큰 MAC과 요청 MAC 불일치")
    public ResponseEntity<Map<String, Object>> receiveOfflineNotice(
            @RequestBody HeartbeatOfflineDto offlineNotice,
            HttpServletRequest httpRequest) {

        if (offlineNotice == null || offlineNotice.getMacAddress() == null || offlineNotice.getMacAddress().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "macAddress is required"));
        }

        String agentMac = (String) httpRequest.getAttribute("agentMac");
        if (agentMac != null && !agentMac.equals(offlineNotice.getMacAddress())) {
            return ResponseEntity.status(403).body(Map.of("error", "Token MAC mismatch"));
        }

        heartbeatService.processGracefulOffline(offlineNotice);
        auditService.log(
                offlineNotice.getMacAddress(),
                "HEARTBEAT_OFFLINE_NOTICE",
                "PC",
                offlineNotice.getMacAddress(),
                offlineNotice.getReason(),
                offlineNotice.getIpAddress());
        return ResponseEntity.ok(Map.of("accepted", true));
    }
}
