package com.example.backend.controller.admin;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.service.admin.AdminService;
import com.example.backend.service.agent.AgentInstallKeyService;
import com.example.backend.service.agent.AgentTokenService;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.command.CommandLifecycleService;
import com.example.backend.service.command.CommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 관리자 단편 API
 *
 * <p>강의실 단위 명령 발행, WOL 전송, 에이전트 토큰 관리, 감사 로그 조회 등
 * 관리자 전용 기능을 제공한다. 모든 엔드포인트에는 ADMIN 역할이 필요하다.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "관리자 단편 API")
public class AdminController {

    private static final Pattern MAC_PATTERN = Pattern.compile("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$");
    private static final int MAX_BULK_AGENTS = 1000;

    private final CommandService commandService;
    private final CommandLifecycleService commandLifecycleService;
    private final AgentInstallKeyService agentInstallKeyService;
    private final AgentTokenService agentTokenService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final AdminService adminService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PutMapping("/change-password")
    @Operation(summary = "관리자 비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경합니다.")
    @ApiResponse(responseCode = "200", description = "변경 성공")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치 또는 입력 오류")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody Map<String, String> body) {
        String current = body.getOrDefault("currentPassword", "");
        String next    = body.getOrDefault("newPassword", "");
        if (current.isBlank() || next.isBlank() || next.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "새 비밀번호는 8자 이상이어야 합니다."));
        }
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        if (!passwordEncoder.matches(current, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "현재 비밀번호가 일치하지 않습니다."));
        }
        user.changePassword(passwordEncoder.encode(next));
        userRepository.save(user);
        auditService.log(username, "CHANGE_PASSWORD", "USER", username);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

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
        return ResponseEntity.ok(Map.of("status", "queued"));
    }

    @PostMapping("/group/wol")
    @Operation(
        summary = "그룹 WOL 전송",
        description = "지정한 강의실 그룹의 온라인 리더 에이전트를 통해 Wake-on-LAN 패킷을 그룹 전체에 전송합니다."
    )
    @ApiResponse(responseCode = "200", description = "WOL 전송 성공 또는 온라인 리더 없음")
    public ResponseEntity<Map<String, Object>> groupWol(
            @Parameter(description = "강의실 그룹 ID") @RequestParam Long groupId) {
        var req = new CommandRequestDto();
        req.setScope("GROUP");
        req.setGroupId(groupId);
        req.setType("PCON");
        req.setRole("ALL");
        Map<String, Object> result = commandLifecycleService.enqueue(req);
        int queued = Integer.parseInt(String.valueOf(result.getOrDefault("queued", 0)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", queued > 0 ? "ok" : "no-leader");
        response.putAll(result);
        return ResponseEntity.ok(response);
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
        return ResponseEntity.ok(Map.of("status", "revoked", "mac", mac));
    }

    @GetMapping("/install-key")
    @Operation(summary = "에이전트 설치키 조회", description = "관리자 화면에서 복사/배포할 현재 설치키와 미리보기를 반환합니다.")
    public ResponseEntity<Map<String, Object>> findInstallKey() {
        return ResponseEntity.ok(agentInstallKeyService.describeInstallKey());
    }

    @PutMapping("/install-key")
    @Operation(summary = "에이전트 설치키 변경", description = "새 설치키를 발급하고 기존 설치키로는 신규 등록되지 않도록 합니다.")
    public ResponseEntity<Map<String, Object>> rotateInstallKey() {
        Map<String, Object> resp = agentInstallKeyService.rotateInstallKey();
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        auditService.log(username, "CHANGE_INSTALL_KEY", "AGENT", "install-key");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/agents")
    @Operation(summary = "등록된 에이전트 목록", description = "등록된 모든 PC 에이전트 목록과 토큰 상태를 반환합니다. pc_master에 없는 고아 토큰도 포함됩니다.")
    @ApiResponse(responseCode = "200", description = "에이전트 목록 조회 성공")
    @ApiResponse(responseCode = "401", description = "인증 실패")
    public ResponseEntity<List<Map<String, Object>>> findAll() {
        return ResponseEntity.ok(adminService.findAll());
    }

    @DeleteMapping("/agents/{mac}")
    @Operation(summary = "에이전트 강제 삭제", description = "PC 레코드와 토큰을 모두 삭제합니다.")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String mac) {
        adminService.delete(mac);
        return ResponseEntity.ok(Map.of("status", "deleted", "mac", mac));
    }

    @PutMapping("/agents/bulk/revoke")
    @Operation(summary = "에이전트 토큰 일괄 폐기", description = "선택한 에이전트의 모든 토큰을 한 요청에서 폐기합니다.")
    public ResponseEntity<Map<String, Object>> revokeTokens(@RequestBody Map<String, List<String>> body) {
        Set<String> macs = normalizeMacs(body.get("macAddresses"));
        macs.forEach(agentTokenService::revokeByMac);
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        auditService.log(username, "BULK_REVOKE_TOKEN", "AGENT", String.valueOf(macs.size()));
        return ResponseEntity.ok(Map.of("status", "revoked", "count", macs.size()));
    }

    @PostMapping("/agents/bulk/delete")
    @Operation(summary = "에이전트 일괄 삭제", description = "선택한 PC 레코드와 토큰을 한 요청에서 삭제합니다.")
    public ResponseEntity<Map<String, Object>> deleteAgents(@RequestBody Map<String, List<String>> body) {
        Set<String> macs = normalizeMacs(body.get("macAddresses"));
        int deleted = adminService.deleteAll(macs);
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        auditService.log(username, "BULK_DELETE_AGENT", "AGENT", String.valueOf(macs.size()));
        return ResponseEntity.ok(Map.of("status", "deleted", "requested", macs.size(), "deleted", deleted));
    }

    @GetMapping("/audit/logs")
    @Operation(summary = "감사 로그 조회", description = "action 또는 actor 기준으로 최근 감사 로그를 조회합니다. 기본 최대 100건.")
    @ApiResponse(responseCode = "200", description = "감사 로그 목록")
    @ApiResponse(responseCode = "401", description = "인증 실패")
    public ResponseEntity<List<Map<String, Object>>> findAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(500, limit));
        List<Map<String, Object>> rows;

        if (action != null && !action.isBlank()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, actor, action, target_type, target_id, detail_json, ip_address, created_at " +
                            "FROM audit_logs WHERE action = ? ORDER BY id DESC LIMIT ?",
                    action, size);
        } else if (actor != null && !actor.isBlank()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, actor, action, target_type, target_id, detail_json, ip_address, created_at " +
                            "FROM audit_logs WHERE actor = ? ORDER BY id DESC LIMIT ?",
                    actor, size);
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, actor, action, target_type, target_id, detail_json, ip_address, created_at " +
                            "FROM audit_logs ORDER BY id DESC LIMIT ?",
                    size);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.get("id"));
            row.put("actor", r.get("actor"));
            row.put("action", r.get("action"));
            row.put("targetType", r.get("target_type"));
            row.put("targetId", r.get("target_id"));
            row.put("detailJson", r.get("detail_json"));
            row.put("ipAddress", r.get("ip_address"));
            row.put("createdAt", r.get("created_at"));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    private Set<String> normalizeMacs(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_BULK_AGENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "macAddresses must contain between 1 and " + MAX_BULK_AGENTS + " items");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String mac = value == null ? "" : value.trim().replace('-', ':').toUpperCase();
            if (!MAC_PATTERN.matcher(mac).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid MAC address");
            }
            normalized.add(mac);
        }
        return normalized;
    }
}
