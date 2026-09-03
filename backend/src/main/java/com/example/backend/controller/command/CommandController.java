package com.example.backend.controller.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.dto.command.CommandStatusBatchRequestDto;
import com.example.backend.service.command.CommandLifecycleService;
import com.example.backend.service.command.CommandPollRegistry;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.example.backend.security.FeaturePermissionGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Locale;
import java.util.Map;

/**
 * 원격 명령 관리 컨트롤러.
 *
 * <p>PC에 종료, 재부팅, WOL, PS1 스크립트 실행 등의 명령을 발행하고 결과를 수집합니다.
 * 모든 비즈니스 로직은 {@link CommandLifecycleService}에 위임합니다.</p>
 *
 * <h3>명령 흐름</h3>
 * <ol>
 *   <li>관리자가 {@code POST /api/v1/commands} 로 명령 요청 (SINGLE 또는 GROUP 스코프)</li>
 *   <li>서버가 {@code CommandQueue} 에 저장 (상태: QUEUED)</li>
 *   <li>에이전트가 Long-poll {@code GET /agent/commands/next} 또는 하트비트로 명령 수령 (상태: DELIVERED)</li>
 *   <li>에이전트가 ACK 후 실행 → {@code POST /agent/commands/{taskId}/result} 로 결과 보고 (상태: COMPLETED/FAILED)</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Commands", description = "PC 원격 제어 및 명령 관리 API")
public class CommandController {

    /** Long-poll 서버 홀드 시간 (ms). 이 시간 안에 명령이 없으면 204 반환. */
    private static final long LONG_POLL_TIMEOUT_MS = 10_000L;

    private final CommandLifecycleService commandLifecycleService;
    private final HeartbeatService heartbeatService;
    private final CommandPollRegistry commandPollRegistry;
    private final FeaturePermissionGuard featurePermissionGuard;

    /**
     * 에이전트 Long-poll 명령 수령 (GET /api/v1/agent/commands/next)
     *
     * 동작:
     * 1. 즉시 QUEUED 명령이 있으면 → DELIVERED 처리 후 200 + {"command": {...}} 즉시 반환
     * 2. 없으면 → DeferredResult 등록, 최대 10초 대기
     *    - 그 사이 명령이 큐에 삽입되면 → 즉시 응답 (enqueue 경로에서 푸시)
     *    - 10초 경과 → 204 No Content 자동 반환
     */
    @GetMapping("/agent/commands/next")
    @Operation(summary = "에이전트 Long-poll 명령 수령",
               description = "명령이 없으면 최대 10초 대기. 명령이 큐잉되면 즉시 응답. 에이전트는 응답 후 즉시 재연결.")
    @ApiResponse(responseCode = "200", description = "전달할 명령 있음")
    @ApiResponse(responseCode = "204", description = "타임아웃 (대기 명령 없음) - 즉시 재연결")
    public DeferredResult<ResponseEntity<?>> nextCommand(HttpServletRequest request) {
        String agentMac = resolveAgentMac(request);
        if (agentMac == null) {
            DeferredResult<ResponseEntity<?>> err = new DeferredResult<>();
            err.setResult(ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "Missing agent identity")));
            return err;
        }
        String canonical = canonicalMac(agentMac);
        Map<String, Object> cmd = heartbeatService.dispatchNextCommand(agentMac);
        if (cmd != null) {
            DeferredResult<ResponseEntity<?>> result = new DeferredResult<>();
            result.setResult(ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("command", cmd)));
            return result;
        }
        return commandPollRegistry.register(canonical, LONG_POLL_TIMEOUT_MS);
    }

    @PostMapping("/commands")
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "PC 명령 발행",
        description = "관리자가 PC에 원격 명령(종료, 재부팅, WOL, 스크립트 등)을 발행합니다. " +
                      "MULTI 범위는 targetMacs 목록을 한 번에 큐에 저장합니다. " +
                      "GROUP + WOL인 경우 그룹의 온라인 에이전트를 통해 릴레이됩니다."
    )
    @ApiResponse(responseCode = "202", description = "명령 발행 성공 (큐에 저장됨)")
    @ApiResponse(responseCode = "400", description = "잘못된 요청 (필수 파라미터 누락, scope 또는 type 오류)")
    public ResponseEntity<Map<String, Object>> enqueue(@Valid @RequestBody CommandRequestDto req) {
        featurePermissionGuard.requireCommand(req.getType());
        return ResponseEntity.accepted().body(commandLifecycleService.enqueue(req));
    }

    @PostMapping("/agent/commands/{taskId}/ack")
    @Operation(
        summary = "에이전트 명령 수신 확인",
        description = "에이전트가 명령을 받았음을 서버에 알립니다. 명령 상태를 DELIVERED에서 ACKED로 변경합니다."
    )
    @ApiResponse(responseCode = "200", description = "확인 성공")
    @ApiResponse(responseCode = "404", description = "taskId를 찾을 수 없음")
    public ResponseEntity<?> ack(
            @Parameter(description = "명령 ID") @PathVariable Long taskId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String agentMac = resolveAgentMac(request);
        if (agentMac == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing agent identity"));
        }
        return ResponseEntity.ok(commandLifecycleService.ack(taskId, agentMac));
    }

    @PostMapping("/agent/commands/{taskId}/result")
    @Operation(
        summary = "에이전트 명령 실행 결과 보고",
        description = "에이전트가 명령 실행을 완료하고 결과를 보고합니다. " +
                      "종료 코드가 0이면 COMPLETED, 아니면 FAILED로 상태를 업데이트합니다."
    )
    @ApiResponse(responseCode = "200", description = "결과 수신 성공")
    @ApiResponse(responseCode = "404", description = "taskId를 찾을 수 없음")
    public ResponseEntity<?> result(
            @Parameter(description = "명령 ID") @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String agentMac = resolveAgentMac(request);
        if (agentMac == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing agent identity"));
        }
        return ResponseEntity.ok(commandLifecycleService.result(taskId, agentMac, body));
    }

    @PostMapping("/commands/to-ip")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "IP 기반 PC 명령 발행",
               description = "IP 주소로 PC를 조회하여 명령을 발행합니다.")
    @ApiResponse(responseCode = "202", description = "명령 발행 성공")
    public ResponseEntity<Map<String, Object>> enqueueToIp(@RequestBody Map<String, Object> body) {
        String ipAddress = body.containsKey("ipAddress") ? body.get("ipAddress").toString().trim() : null;
        String type = body.containsKey("type") ? body.get("type").toString() : null;
        featurePermissionGuard.requireCommand(type);
        String scriptUrl = body.containsKey("scriptUrl") ? body.get("scriptUrl").toString() : null;
        String payloadJson = body.containsKey("payloadJson") ? body.get("payloadJson").toString() : null;
        String broadcastIp = body.containsKey("broadcastIp") ? body.get("broadcastIp").toString() : null;
        return ResponseEntity.accepted().body(
                commandLifecycleService.enqueueToIp(ipAddress, type, scriptUrl, payloadJson, broadcastIp));
    }

    @GetMapping("/commands")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "명령 목록 조회",
               description = "진행 중이거나 완료된 명령 목록을 조회합니다. 페이지네이션을 지원합니다.")
    @ApiResponse(responseCode = "200", description = "명령 목록 조회 성공")
    public ResponseEntity<?> listCommands(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long taskId,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        if (taskId != null) {
            return ResponseEntity.ok(commandLifecycleService.findCommandById(taskId));
        }
        return ResponseEntity.ok(commandLifecycleService.listCommands(status, page, size));
    }

    @PostMapping("/commands/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "명령 상태 일괄 조회", description = "대량 작업의 taskId를 한 요청으로 조회합니다.")
    public ResponseEntity<?> commandStatuses(@Valid @RequestBody CommandStatusBatchRequestDto request) {
        return ResponseEntity.ok(commandLifecycleService.findCommandsByIds(request.getTaskIds()));
    }

    @PostMapping("/commands/{taskId}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_COMMAND_CANCEL')")
    @Operation(
        summary = "대기 명령 취소",
        description = "명령 종류와 관계없이 아직 에이전트에 전달되지 않은 QUEUED 명령을 취소합니다."
    )
    @ApiResponse(responseCode = "200", description = "명령 취소 성공")
    @ApiResponse(responseCode = "409", description = "이미 전달 또는 실행되어 취소할 수 없음")
    public ResponseEntity<Map<String, Object>> cancelCommand(
            @Parameter(description = "명령 ID") @PathVariable Long taskId) {
        return ResponseEntity.ok(commandLifecycleService.cancelQueuedCommand(taskId));
    }

    @DeleteMapping("/commands/{taskId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "완료된 명령 이력 삭제",
        description = "관리자만 완료, 실패 또는 취소된 명령 이력을 삭제할 수 있습니다. 진행 중인 명령은 먼저 취소하거나 완료되어야 합니다."
    )
    @ApiResponse(responseCode = "200", description = "명령 이력 삭제 성공")
    @ApiResponse(responseCode = "409", description = "진행 중이거나 다른 운영 기록에서 참조 중인 명령")
    public ResponseEntity<Map<String, Object>> deleteCommandHistory(
            @Parameter(description = "명령 ID") @PathVariable Long taskId) {
        return ResponseEntity.ok(commandLifecycleService.deleteCommandHistory(taskId));
    }

    // ── HTTP 유틸리티 ────────────────────────────────────────────────────────

    private String resolveAgentMac(HttpServletRequest request) {
        if (request == null) return null;
        Object value = request.getAttribute("agentMac");
        if (value == null) return null;
        String mac = value.toString().trim();
        return mac.isBlank() ? null : mac;
    }

    private String canonicalMac(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) return null;
        String normalized = macAddress.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
