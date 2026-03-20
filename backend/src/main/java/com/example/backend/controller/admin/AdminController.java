package com.example.backend.controller.admin;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.agent.AgentTokenService;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.command.CommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;

    @Value("${agent.install-key:INST-CHANGE-ME-BEFORE-DEPLOY}")
    private String installKey;

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

    @GetMapping("/install-key")
    @Operation(summary = "에이전트 설치키 조회", description = "현재 서버에 설정된 에이전트 설치키를 반환합니다.")
    public ResponseEntity<Map<String, Object>> getInstallKey() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("installKey", installKey);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/agents")
    @Operation(summary = "등록된 에이전트 목록", description = "등록된 모든 PC 에이전트 목록과 토큰 상태를 반환합니다.")
    public ResponseEntity<List<Map<String, Object>>> listAgents() {
        List<PcMaster> pcs = pcMasterRepository.findAll();
        List<AgentAuthToken> tokens = agentAuthTokenRepository.findAll();
        Map<String, AgentAuthToken> tokenByMac = new LinkedHashMap<>();
        for (AgentAuthToken token : tokens) {
            if (token.getMacAddress() != null && !tokenByMac.containsKey(token.getMacAddress())) {
                tokenByMac.put(token.getMacAddress(), token);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (PcMaster pc : pcs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("macAddress", pc.getMacAddress());
            row.put("pcName", pc.getPcName());
            row.put("ipAddress", pc.getIpAddress());
            row.put("status", pc.getStatus());
            row.put("role", pc.getRole());
            row.put("lastSeen", pc.getLastSeen());
            AgentAuthToken tok = tokenByMac.get(pc.getMacAddress());
            row.put("tokenActive", tok != null && !tok.isRevoked());
            row.put("tokenIssuedAt", tok != null ? tok.getIssuedAt() : null);
            row.put("tokenLastUsedAt", tok != null ? tok.getLastUsedAt() : null);
            result.add(row);
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/agents/{mac}")
    @Operation(summary = "에이전트 강제 삭제", description = "PC 레코드와 토큰을 모두 삭제합니다.")
    public ResponseEntity<Map<String, Object>> deleteAgent(@PathVariable String mac) {
        agentAuthTokenRepository.findByMacAddress(mac).ifPresent(agentAuthTokenRepository::delete);
        pcMasterRepository.findById(mac).ifPresent(pcMasterRepository::delete);
        auditService.log("ADMIN", "DELETE_AGENT", "AGENT", mac);
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "deleted");
        resp.put("mac", mac);
        return ResponseEntity.ok(resp);
    }
}
