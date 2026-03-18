package com.example.backend.controller.admin;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.service.agent.AgentTokenService;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.command.CommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 관리자 단편 API
 * 강의실단위 명령 및 WOL 전송 등을 담당합니다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "관리자 단편 API")
public class AdminController {

    private final CommandService commandService;
    private final AgentTokenService agentTokenService;
    private final AuditService auditService;

    /**
     * 빠른 API로 PC 단일 개에 명령 단큐라는 단순한 방식.
     */
    @PostMapping("/enqueue")
    @Operation(
        summary = "PC 명령 단순 큐",
        description = "단동 PC 단나에 명령(RESTORE, WAKEUP 등)을 큐에 넣습니다."
    )
    @ApiResponse(responseCode = "200", description = "{\"status\":\"queued\"}")
    public ResponseEntity<Map<String, Object>> enqueueCommand(
            @Parameter(description = "대상 PC의 MAC 주소", example = "00:1A:2B:3C:4D:5E") @RequestParam String mac,
            @Parameter(description = "명령 종류", example = "RESTORE") @RequestParam String type,
            @Parameter(description = "스크립트 URL (선택)") @RequestParam(required = false) String scriptUrl) {
        var req = new CommandRequestDto();
        req.setMacAddress(mac);
        req.setType(type);
        req.setScriptUrl(scriptUrl);
        commandService.enqueueCommand(req);
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "queued");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/group/wol")
    public ResponseEntity<Map<String, Object>> groupWol(@RequestParam Long groupId) {
        boolean ok = commandService.sendGroupWol(groupId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", ok ? "ok" : "no-leader");
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/agents/{mac}/revoke")
    @Operation(
        summary = "에이전트 토큰 폐기",
        description = "지정한 MAC 주소의 에이전트 토큰을 즉시 폐기합니다."
    )
    @ApiResponse(responseCode = "200", description = "{\"status\":\"revoked\"}")
    @ApiResponse(responseCode = "404", description = "해당 MAC의 토큰 없음")
    public ResponseEntity<Map<String, Object>> revokeToken(
            @Parameter(description = "에이전트 MAC 주소", example = "AA:BB:CC:DD:EE:FF")
            @PathVariable String mac) {
        agentTokenService.revokeByMac(mac);
        auditService.log("ADMIN", "REVOKE_TOKEN", "AGENT", mac);
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "revoked");
        resp.put("mac", mac);
        return ResponseEntity.ok(resp);
    }
}
