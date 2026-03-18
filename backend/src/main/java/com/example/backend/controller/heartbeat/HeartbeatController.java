package com.example.backend.controller.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.heartbeat.HeartbeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/heartbeat")
@RequiredArgsConstructor
@Tag(name = "Heartbeat", description = "에이전트 하트비트 및 상태 보고 API")
public class HeartbeatController {

    private final HeartbeatService heartbeatService;
    private final CommandQueueRepository commandQueueRepository;
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

        // 인터셉터가 설정한 인증된 MAC 주소
        String agentMac = (String) httpRequest.getAttribute("agentMac");
        if (agentMac != null && !agentMac.equals(heartbeat.getMacAddress())) {
            return ResponseEntity.status(403).body(Map.of("error", "Token MAC mismatch"));
        }

        heartbeatService.processHeartbeat(heartbeat);

        auditService.log(heartbeat.getMacAddress(), "HEARTBEAT", "PC",
                heartbeat.getMacAddress(), null, heartbeat.getIpAddress());

        // 대기 중인 QUEUED 명령 하나 꺼내서 DELIVERED 상태로 변경 후 응답
        Map<String, Object> resp = new HashMap<>();
        CommandQueue pending = commandQueueRepository
                .findFirstByMacAddressAndStatusOrderByTaskIdAsc(heartbeat.getMacAddress(), "QUEUED");

        if (pending != null) {
            pending.setStatus("DELIVERED");
            pending.setDeliveredAt(LocalDateTime.now());
            commandQueueRepository.save(pending);

            Map<String, Object> cmd = new HashMap<>();
            cmd.put("taskId", pending.getTaskId());
            cmd.put("type", pending.getCommandType());
            cmd.put("payload", pending.getPayloadJson());
            cmd.put("scriptUrl", pending.getScriptUrl());
            resp.put("command", cmd);
        }

        return ResponseEntity.ok(resp);
    }
}