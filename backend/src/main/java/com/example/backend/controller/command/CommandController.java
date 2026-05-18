package com.example.backend.controller.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.event.AlertEvent;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.command.CommandPollRegistry;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.example.backend.service.scheduler.SchedulerService;
import com.example.backend.service.status.OperationalMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
/**
 * 원격 명령 관리 컨트롤러.
 *
 * <p>PC에 종료, 재부팅, WOL, PS1 스크립트 실행 등의 명령을 발행하고 결과를 수집합니다.</p>
 *
 * <h3>명령 흐름</h3>
 * <ol>
 *   <li>관리자가 {@code POST /api/v1/commands} 로 명령 요청 (SINGLE 또는 GROUP 스코프)</li>
 *   <li>서버가 {@code CommandQueue} 에 저장 (상태: QUEUED)</li>
 *   <li>에이전트가 Long-poll {@code GET /agent/commands/next} 또는 하트비트로 명령 수령 (상태: DELIVERED)</li>
 *   <li>에이전트가 ACK 후 실행 → {@code POST /agent/commands/{taskId}/result} 로 결과 보고 (상태: COMPLETED/FAILED)</li>
 * </ol>
 *
 * <h3>WOL 릴레이 구조</h3>
 * <p>WOL은 같은 서브넷 안에서만 동작하므로, 서버가 직접 매직 패킷을 보내지 않고
 * 그룹 내 ONLINE 상태의 리더 에이전트를 통해 WOL_RELAY 명령을 릴레이합니다.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Commands", description = "PC 원격 제어 및 명령 관리 API")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);
    private static final int DEFAULT_RESULT_TEXT_LIMIT = 4_096;
    private static final int LIST_FILES_RESULT_TEXT_LIMIT = 256 * 1024;
    private static final int COLLECT_FILES_RESULT_TEXT_LIMIT = 32 * 1024;

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SchedulerService schedulerService;
    private final FileDistributionOrchestrator fileDistributionOrchestrator;
    private final ApplicationEventPublisher eventPublisher;
    private final OperationalMetricsService operationalMetricsService;
    private final HeartbeatService heartbeatService;
    private final CommandPollRegistry commandPollRegistry;

    /** Long-poll 서버 홀드 시간 (ms). 이 시간 안에 명령이 없으면 204 반환. */
    private static final long LONG_POLL_TIMEOUT_MS = 10_000L;

    /**
     * 에이전트 Long-poll 명령 수령 (GET /api/v1/agent/commands/next)
     *
     * 동작:
     * 1. 즉시 QUEUED 명령이 있으면 → DELIVERED 처리 후 200 + {"command": {...}} 즉시 반환
     * 2. 없으면 → DeferredResult 등록, 최대 10초 대기
     *    - 그 사이 명령이 큐에 삽입되면 → 즉시 응답 (enqueue 경로에서 푸시)
     *    - 10초 경과 → 204 No Content 자동 반환
     * 3. 에이전트는 응답을 받자마자 즉시 재연결 → 800대 기준 ~80 req/s (10s 타임아웃)
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
            err.setResult(ResponseEntity.status(401).body(Map.of("error", "Missing agent identity")));
            return err;
        }
        String canonical = canonicalMac(agentMac);

        // 1) 즉시 처리 가능한 명령이 있으면 바로 반환
        Map<String, Object> cmd = heartbeatService.dispatchNextCommand(agentMac);
        if (cmd != null) {
            DeferredResult<ResponseEntity<?>> result = new DeferredResult<>();
            result.setResult(ResponseEntity.ok(Map.of("command", cmd)));
            return result;
        }

        // 2) 없으면 Long-poll 대기 등록 (타임아웃 시 자동 204)
        return commandPollRegistry.register(canonical, LONG_POLL_TIMEOUT_MS);
    }

    /**
     * 명령이 큐에 삽입된 뒤 트랜잭션 커밋 후, 해당 MAC의 대기 에이전트에 즉시 응답을 푸시한다.
     */
    private void notifyWaitingAgentsAfterCommit(List<String> macAddresses) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            pushCommandsToWaiters(macAddresses);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushCommandsToWaiters(macAddresses);
            }
        });
    }

    // ─── WOL 페이로드 헬퍼 ──────────────────────────────────────────────────

    /**
     * 단일 MAC 대상 WOL 페이로드 JSON을 빌드한다.
     *
     * <p>ObjectMapper를 사용해 JSON 인젝션을 방지한다.
     * broadcastIp가 없으면 255.255.255.255(전체 브로드캐스트)를 사용한다.</p>
     *
     * @param targetMac   깨울 PC의 MAC 주소
     * @param broadcastIp 브로드캐스트 IP (null이면 255.255.255.255 사용)
     * @return JSON 문자열, 직렬화 실패 시 null
     */
    private String buildWolPayloadJson(String targetMac, String broadcastIp) {
        String bcast = (broadcastIp != null && !broadcastIp.isBlank()) ? broadcastIp : "255.255.255.255";
        try {
            Map<String, String> wolPayload = new LinkedHashMap<>();
            wolPayload.put("targetMacs", targetMac != null ? targetMac : "");
            wolPayload.put("broadcastIp", bcast);
            return objectMapper.writeValueAsString(wolPayload);
        } catch (Exception e) {
            log.warn("WOL 페이로드 직렬화 실패: targetMac={}, broadcastIp={}", targetMac, bcast);
            return null;
        }
    }

    /**
     * 그룹 내 여러 MAC 대상 WOL 페이로드 JSON을 빌드한다.
     *
     * <p>targetMacs는 쉼표로 구분된 MAC 주소 목록이다.</p>
     *
     * @param targetMacs  쉼표 구분 MAC 주소 목록
     * @param broadcastIp 브로드캐스트 IP (null이면 255.255.255.255 사용)
     * @return JSON 문자열, 직렬화 실패 시 null
     */
    private String buildGroupWolPayloadJson(List<String> targetMacs, String broadcastIp) {
        return buildWolPayloadJson(String.join(",", targetMacs), broadcastIp);
    }

    /**
     * WOL_RELAY CommandQueue 엔트리를 생성하고 저장한 뒤, 리더 에이전트에 즉시 푸시한다.
     *
     * @param leaderMac   WOL 릴레이를 수행할 리더 에이전트의 MAC
     * @param payloadJson WOL 페이로드 JSON
     * @return 저장된 CommandQueue
     */
    private CommandQueue saveWolRelayCommand(String leaderMac, String payloadJson) {
        CommandQueue c = new CommandQueue();
        c.setMacAddress(leaderMac);
        c.setCommandType("WOL_RELAY");
        c.setScriptUrl(null);
        c.setPayloadJson(payloadJson);
        c.setStatus("QUEUED");
        c.setCreatedAt(LocalDateTime.now());
        c.setIsExecuted(false);
        CommandQueue saved = commandQueueRepository.save(c);
        notifyWaitingAgentsAfterCommit(List.of(leaderMac));
        return saved;
    }

    // ────────────────────────────────────────────────────────────────────────

    private void pushCommandsToWaiters(List<String> macAddresses) {
        for (String mac : macAddresses) {
            if (mac == null) continue;
            String canonical = canonicalMac(mac);
            DeferredResult<ResponseEntity<?>> waiter = commandPollRegistry.take(canonical);
            if (waiter == null) continue;
            try {
                Map<String, Object> cmd = heartbeatService.dispatchNextCommand(mac);
                if (cmd != null) {
                    waiter.setResult(ResponseEntity.ok(Map.of("command", cmd)));
                }
                // cmd == null이면 waiter는 타임아웃까지 대기 (정상)
            } catch (Exception e) {
                log.debug("대기 에이전트 푸시 실패 mac={}: {}", mac, e.getMessage());
            }
        }
    }

    /**
     * 명령 발행 (POST /api/v1/commands)
     * 단일 PC 또는 그룹의 여러 PC에 명령을 발행합니다.
     * 
     * scope = SINGLE: 특정 MAC의 PC 하나에만 명령 실행
     * scope = GROUP: 그룹의 모든 PC에 명령 실행 (role로 필터링 가능)
     * type = SHUTDOWN: PC 종료
     * type = REBOOT: PC 재부팅
     * type = WOL: PC 켜기 (Wake-on-LAN)
     * type = RUN_CMD: 명령 실행
     * type = RUN_PS1: PS1 스크립트 실행
     * 
     * WOL 특수 처리: GROUP + WOL인 경우, 그룹의 ONLINE 에이전트(leader)를 통해 
     * 매직패킷을 릴레이합니다.
     */
    @PostMapping("/commands")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "PC 명령 발행",
        description = "관리자가 PC에 원격 명령(종료, 재부팅, WOL, 스크립트 등)을 발행합니다. " +
                      "명령은 CommandQueue에 저장되었다가 에이전트의 다음 하트비트 시 전달됩니다. " +
                      "GROUP + WOL인 경우 그룹의 온라인 에이전트를 통해 릴레이됩니다."
    )
    @ApiResponse(responseCode = "202", description = "명령 발행 성공 (큐에 저장됨)")
    @ApiResponse(responseCode = "400", description = "잘못된 요청 (필수 파라미터 누락, scope 또는 type 오류)")
    @Transactional
    public ResponseEntity<Map<String, Object>> enqueue(@Valid @RequestBody CommandRequestDto req) {
        String scope = req.getScope() == null ? "" : req.getScope().toUpperCase(Locale.ROOT);
        String role = req.getRole() == null ? "ALL" : req.getRole().toUpperCase(Locale.ROOT);
        String type = req.getType() == null ? "" : req.getType().toUpperCase(Locale.ROOT);
        if ("PCON".equals(type)) {
            type = "WOL";
        }

        // SINGLE + WOL: 대상 PC의 그룹 리더 에이전트를 통해 WOL 릴레이
        // 같은 서브넷에서만 WOL 매직 패킷이 전달되므로, 서버 대신 그룹 내 ONLINE 리더 에이전트가 발송한다.
        if ("SINGLE".equals(scope) && "WOL".equals(type)) {
            if (req.getMacAddress() == null || req.getMacAddress().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "macAddress required"));
            }

            Optional<PcMaster> targetOpt = pcMasterRepository.findById(req.getMacAddress());
            if (targetOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("message", "target pc not found"));
            }

            PcMaster targetPc = targetOpt.get();
            if (targetPc.getGroupId() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "target groupId not resolved"));
            }

            List<PcMaster> leaders = pcMasterRepository.findByGroupIdAndStatus(targetPc.getGroupId(), "ONLINE");
            if (leaders.isEmpty()) {
                return ResponseEntity.accepted().body(Map.of("queued", 0, "message", "no-online-agent"));
            }

            PcMaster leader = leaders.get(0);
            // 요청에 payloadJson이 이미 있으면 그대로 사용, 없으면 헬퍼로 빌드
            String payloadJson = req.getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                payloadJson = buildWolPayloadJson(targetPc.getMacAddress(), req.getBroadcastIp());
                if (payloadJson == null) {
                    return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
                }
            }

            CommandQueue saved = saveWolRelayCommand(leader.getMacAddress(), payloadJson);
            return ResponseEntity.accepted().body(Map.of(
                    "queued", 1,
                    "leaderMac", leader.getMacAddress(),
                    "taskIds", List.of(saved.getTaskId())
            ));
        }

        // GROUP + WOL: 리더 에이전트를 통해 그룹 전체 WOL 릴레이
        // 리더를 제외한 그룹 내 모든 PC(오프라인 포함)의 MAC 목록을 페이로드에 담아 전달한다.
        if ("GROUP".equals(scope) && "WOL".equals(type)) {
            if (req.getGroupId() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "groupId required"));
            }
            List<PcMaster> leaders = pcMasterRepository.findByGroupIdAndStatus(req.getGroupId(), "ONLINE");
            if (leaders.isEmpty()) {
                return ResponseEntity.accepted().body(Map.of("queued", 0, "message", "no-online-agent"));
            }
            PcMaster leader = leaders.get(0);

            // 리더를 제외한 그룹 전체 PC 목록(오프라인 포함) → targetMacs
            List<String> macs = pcMasterRepository.findByGroupId(req.getGroupId()).stream()
                    .filter(pc -> pc.getMacAddress() != null)
                    .filter(pc -> !pc.getMacAddress().equalsIgnoreCase(leader.getMacAddress()))
                    .map(PcMaster::getMacAddress)
                    .collect(Collectors.toList());

            String payloadJson = req.getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                // ObjectMapper로 안전하게 직렬화 (JSON 인젝션 방지)
                payloadJson = buildGroupWolPayloadJson(macs, req.getBroadcastIp());
                if (payloadJson == null) {
                    return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
                }
            }

            CommandQueue saved = saveWolRelayCommand(leader.getMacAddress(), payloadJson);
            return ResponseEntity.accepted().body(Map.of(
                    "queued", 1,
                    "leaderMac", leader.getMacAddress(),
                    "taskIds", List.of(saved.getTaskId())
            ));
        }

        List<PcMaster> targets = new ArrayList<>();
        if ("SINGLE".equals(scope)) {
            if (req.getMacAddress() == null || req.getMacAddress().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "macAddress required"));
            }
            pcMasterRepository.findById(req.getMacAddress()).ifPresent(targets::add);
        } else if ("GROUP".equals(scope)) {
            if (req.getGroupId() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "groupId required"));
            }
            if ("ALL".equals(role)) {
                targets.addAll(pcMasterRepository.findByGroupId(req.getGroupId()));
            } else {
                targets.addAll(pcMasterRepository.findByGroupIdAndRole(req.getGroupId(), role));
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("message", "scope must be SINGLE or GROUP"));
        }

        List<CommandQueue> commands = new ArrayList<>();
        for (PcMaster pc : targets) {
            if (pc.getMacAddress() == null) continue;
            CommandQueue c = new CommandQueue();
            c.setMacAddress(pc.getMacAddress());
            c.setCommandType(type);
            c.setScriptUrl(req.getScriptUrl());
            c.setPayloadJson(req.getPayloadJson());
            c.setStatus("QUEUED");
            c.setCreatedAt(LocalDateTime.now());
            c.setIsExecuted(false);
            commands.add(c);
        }

        List<CommandQueue> saved = commands.isEmpty() ? List.of() : commandQueueRepository.saveAll(commands);
        List<Long> taskIds = saved.stream()
                .map(CommandQueue::getTaskId)
                .filter(Objects::nonNull)
                .toList();

        // 대기 중인 에이전트에 즉시 푸시 (GROUP 800대도 커밋 후 일괄 알림)
        List<String> savedMacs = saved.stream()
                .map(CommandQueue::getMacAddress)
                .filter(Objects::nonNull)
                .toList();
        if (!savedMacs.isEmpty()) {
            notifyWaitingAgentsAfterCommit(savedMacs);
        }

        return ResponseEntity.accepted().body(Map.of(
                "queued", taskIds.size(),
                "taskIds", taskIds
        ));
    }

    /**
     * 명령 수신 확인 (POST /api/v1/agent/commands/{taskId}/ack)
     * 에이전트가 명령을 받았음을 서버에 알립니다.
     * 
     * @param taskId 명령 ID
     * @return 확인 응답
     */
    @PostMapping("/agent/commands/{taskId}/ack")
    @Transactional
    @Operation(
        summary = "에이전트 명령 수신 확인",
        description = "에이전트가 명령을 받았음을 서버에 알립니다. " +
                      "명령 상태를 QUEUED에서 ACKED로 변경합니다."
    )
    @ApiResponse(responseCode = "200", description = "확인 성공")
    @ApiResponse(responseCode = "404", description = "taskId를 찾을 수 없음")
    public ResponseEntity<?> ack(
            @Parameter(description = "명령 ID") @PathVariable Long taskId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Optional<CommandQueue> opt = commandQueueRepository.findById(taskId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        CommandQueue c = opt.get();
        String agentMac = resolveAgentMac(request);
        if (agentMac == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing agent identity"));
        }
        if (!isCommandOwner(c, agentMac)) {
            return ResponseEntity.status(403).body(Map.of("error", "Command owner mismatch"));
        }

        String currentStatus = normalizeStatus(c.getStatus());
        if (isFinalCommandStatus(currentStatus)) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "alreadyFinal", true,
                    "commandStatus", currentStatus
            ));
        }
        if ("ACKED".equals(currentStatus)) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "alreadyAcked", true
            ));
        }
        if (!"DELIVERED".equals(currentStatus)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Command is not in DELIVERED state",
                    "commandStatus", currentStatus
            ));
        }

        LocalDateTime ackedAt = LocalDateTime.now();
        int updated = commandQueueRepository.markAckedIfDelivered(taskId, ackedAt);
        if (updated == 0) {
            String latestStatus = fetchCurrentStatus(taskId);
            if (isFinalCommandStatus(latestStatus)) {
                return ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "alreadyFinal", true,
                        "commandStatus", latestStatus
                ));
            }
            if ("ACKED".equals(latestStatus)) {
                return ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "alreadyAcked", true
                ));
            }
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Command state changed before ACK update",
                    "commandStatus", latestStatus
            ));
        }
        if (c.getDeliveredAt() != null) {
            long ackLatencyMs = Duration.between(c.getDeliveredAt(), ackedAt).toMillis();
            operationalMetricsService.recordCommandAckLatencyMillis(ackLatencyMs);
        }
        auditService.log(agentMac, "CMD_ACK", "COMMAND", String.valueOf(taskId));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 명령 실행 결과 보고 (POST /api/v1/agent/commands/{taskId}/result)
     * 에이전트가 명령 실행 결과(종료 코드, 표준 출력, 표준 오류)를 서버에 보고합니다.
     * 
     * @param taskId 명령 ID
     * @param body 실행 결과 (exitCode, stdout, stderr)
     * @return 확인 응답
     */
    @PostMapping("/agent/commands/{taskId}/result")
    @Transactional
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
        Optional<CommandQueue> opt = commandQueueRepository.findById(taskId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        CommandQueue c = opt.get();
        String agentMac = resolveAgentMac(request);
        if (agentMac == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing agent identity"));
        }
        if (!isCommandOwner(c, agentMac)) {
            return ResponseEntity.status(403).body(Map.of("error", "Command owner mismatch"));
        }

        String currentStatus = normalizeStatus(c.getStatus());
        if (isFinalCommandStatus(currentStatus)) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "alreadyFinal", true,
                    "commandStatus", currentStatus
            ));
        }
        if (!"ACKED".equals(currentStatus) && !"DELIVERED".equals(currentStatus)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Command is not in ACKED/DELIVERED state",
                    "commandStatus", currentStatus
            ));
        }

        Object exit = body.get("exitCode");
        Integer exitCode = exit instanceof Number ? ((Number) exit).intValue() : null;
        // stdout/stderr 기본 제한을 두되, 원격 파일 탐색/수집 결과는 필요한 범위까지 허용한다.
        int outputLimit = resolveOutputLimit(c.getCommandType());
        String stdout = body.get("stdout") == null ? null : truncate(body.get("stdout").toString(), outputLimit);
        String stderr = body.get("stderr") == null ? null : truncate(body.get("stderr").toString(), outputLimit);
        LocalDateTime completedAt = LocalDateTime.now();
        String newStatus = exitCode != null && exitCode == 0 ? "COMPLETED" : "FAILED";
        boolean success = "COMPLETED".equals(newStatus);
        int updated = commandQueueRepository.markResultIfInProgress(taskId, newStatus, exitCode, stdout, stderr, completedAt);
        if (updated == 0) {
            String latestStatus = fetchCurrentStatus(taskId);
            if (isFinalCommandStatus(latestStatus)) {
                return ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "alreadyFinal", true,
                        "commandStatus", latestStatus
                ));
            }
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Command state changed before RESULT update",
                    "commandStatus", latestStatus
            ));
        }

        CommandQueue persisted = commandQueueRepository.findById(taskId).orElse(null);
        if (persisted == null) {
            return ResponseEntity.notFound().build();
        }
        schedulerService.armPostExecutionVerification(persisted, completedAt);
        handleAgentUpdateVerification(persisted, exitCode, stdout, stderr);
        commandQueueRepository.save(persisted);

        long resultLatencyMs = -1L;
        if (persisted.getAckedAt() != null) {
            resultLatencyMs = Duration.between(persisted.getAckedAt(), completedAt).toMillis();
        }
        operationalMetricsService.recordCommandResultLatencyMillis(resultLatencyMs, success);
        reportFileDistributionResult(persisted, exitCode, stderr);

        String actor = agentMac;
        try {
            String detail = objectMapper.writeValueAsString(Map.of("exitCode", String.valueOf(exitCode), "status", newStatus));
            auditService.log(actor, "CMD_RESULT", "COMMAND", String.valueOf(taskId), detail, null);
        } catch (Exception ex) {
            auditService.log(actor, "CMD_RESULT", "COMMAND", String.valueOf(taskId));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private void handleAgentUpdateVerification(CommandQueue command,
                                               Integer exitCode,
                                               String stdout,
                                               String stderr) {
        if (command == null || command.getCommandType() == null
                || !"AGENT_UPDATE".equalsIgnoreCase(command.getCommandType())) {
            return;
        }
        LocalDateTime now = command.getCompletedAt() == null ? LocalDateTime.now() : command.getCompletedAt();
        if (exitCode != null && exitCode == 0) {
            command.setVerificationStatus("PENDING_REBOOT_CONFIRM");
            command.setVerificationStartedAt(now);
            command.setVerificationDeadlineAt(null);
            command.setVerificationObservedOfflineAt(null);
            command.setVerificationCompletedAt(null);

            String targetVersion = extractPayloadText(command.getPayloadJson(), "targetVersion");
            String versionText = targetVersion == null || targetVersion.isBlank()
                    ? "unknown"
                    : targetVersion;
            String message = "[업데이트 확인 필요] " + command.getMacAddress()
                    + " 에이전트 업데이트 완료. 저장 후 재부팅하고 버전(" + versionText + ") 적용 여부를 확인하세요."
                    + " (명령 ID: " + command.getTaskId() + ")";
            eventPublisher.publishEvent(new AlertEvent(
                    this,
                    command.getMacAddress(),
                    message,
                    "WARNING",
                    "UPDATE_REBOOT_REQUIRED"
            ));
            return;
        }

        command.setVerificationStatus("FAILED");
        command.setVerificationCompletedAt(now);
        command.setVerificationDeadlineAt(now);
        command.setVerificationObservedOfflineAt(null);
        String failReason = stderr == null || stderr.isBlank()
                ? "agent update command failed"
                : stderr;
        command.setStderr(appendLine(command.getStderr(), failReason));
    }

    private void reportFileDistributionResult(CommandQueue command,
                                              Integer exitCode,
                                              String stderr) {
        if (command == null) {
            return;
        }
        Long jobId = extractPayloadLong(command.getPayloadJson(), "jobId");
        if (jobId == null) {
            return;
        }
        boolean success = exitCode != null && exitCode == 0;
        String error = success ? null : (stderr == null || stderr.isBlank() ? "command failed" : stderr);
        try {
            fileDistributionOrchestrator.reportAgentResult(jobId, command.getMacAddress(), success, error);
        } catch (Exception ex) {
            log.warn("Failed to report file-distribution result: taskId={}, jobId={}, mac={}, message={}",
                    command.getTaskId(), jobId, command.getMacAddress(), ex.getMessage());
        }
    }

    private String extractPayloadText(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node == null || !node.has(field) || node.get(field).isNull()) {
                return null;
            }
            String value = node.get(field).asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private Long extractPayloadLong(String payloadJson, String field) {
        String text = extractPayloadText(payloadJson, field);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String appendLine(String current, String line) {
        if (line == null || line.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return line;
        }
        if (current.contains(line)) {
            return current;
        }
        return current + System.lineSeparator() + line;
    }

    private String resolveAgentMac(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute("agentMac");
        if (value == null) {
            return null;
        }
        String mac = value.toString().trim();
        return mac.isBlank() ? null : mac;
    }

    private boolean isCommandOwner(CommandQueue command, String agentMac) {
        String commandMac = canonicalMac(command == null ? null : command.getMacAddress());
        String callerMac = canonicalMac(agentMac);
        if (commandMac == null || callerMac == null) {
            return false;
        }
        return commandMac.equals(callerMac);
    }

    private String canonicalMac(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) {
            return null;
        }
        String normalized = macAddress.replace(":", "")
                .replace("-", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isFinalCommandStatus(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status);
    }

    private String fetchCurrentStatus(Long taskId) {
        return commandQueueRepository.findById(taskId)
                .map(CommandQueue::getStatus)
                .map(this::normalizeStatus)
                .orElse("");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private int resolveOutputLimit(String commandType) {
        if (commandType == null || commandType.isBlank()) {
            return DEFAULT_RESULT_TEXT_LIMIT;
        }
        String normalized = commandType.trim().toUpperCase(Locale.ROOT);
        if ("LIST_FILES".equals(normalized)) {
            return LIST_FILES_RESULT_TEXT_LIMIT;
        }
        if ("COLLECT_FILES".equals(normalized)) {
            return COLLECT_FILES_RESULT_TEXT_LIMIT;
        }
        return DEFAULT_RESULT_TEXT_LIMIT;
    }

    /**
     * IP 기반 단일 명령 발행 (POST /api/v1/commands/to-ip)
     * IP 주소로 특정 PC를 찾아 명령을 큐에 삽입한다.
     * PC가 등록되어 있지 않으면 404를 반환한다.
     */
    @PostMapping("/commands/to-ip")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "IP 기반 PC 명령 발행",
               description = "IP 주소로 PC를 조회하여 명령을 발행합니다.")
    @Transactional
    public ResponseEntity<Map<String, Object>> enqueueToIp(@RequestBody Map<String, Object> body) {
        String ipAddress = body.containsKey("ipAddress") ? body.get("ipAddress").toString().trim() : null;
        String type = body.containsKey("type") ? body.get("type").toString().toUpperCase(java.util.Locale.ROOT) : null;
        if (ipAddress == null || ipAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ipAddress is required"));
        }
        if (type == null || type.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "type is required"));
        }

        // PCON → WOL 변환
        if ("PCON".equals(type)) {
            type = "WOL";
        }

        Optional<PcMaster> pcOpt = pcMasterRepository.findByIpAddress(ipAddress);

        // WOL: 대상 PC의 그룹 내 온라인 리더를 통해 WOL_RELAY 전송
        if ("WOL".equals(type)) {
            PcMaster targetPc = pcOpt.orElse(null);
            Long groupId = targetPc != null ? targetPc.getGroupId() : null;
            String targetMac = targetPc != null ? targetPc.getMacAddress() : null;

            if (groupId == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "대상 PC의 그룹을 찾을 수 없습니다."));
            }

            List<PcMaster> leaders = pcMasterRepository.findByGroupIdAndStatus(groupId, "ONLINE");
            if (leaders.isEmpty()) {
                return ResponseEntity.accepted().body(Map.of("queued", 0, "message", "no-online-agent"));
            }

            PcMaster leader = leaders.get(0);
            String broadcastIp = body.containsKey("broadcastIp") ? body.get("broadcastIp").toString() : null;
            String payloadJson = buildWolPayloadJson(targetMac, broadcastIp);
            if (payloadJson == null) {
                return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
            }

            CommandQueue saved = saveWolRelayCommand(leader.getMacAddress(), payloadJson);
            return ResponseEntity.accepted().body(Map.of(
                    "queued", 1,
                    "macAddress", leader.getMacAddress(),
                    "taskId", saved.getTaskId()
            ));
        }

        // 일반 명령: 대상 PC에 직접 전달
        if (pcOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "PC not found for IP: " + ipAddress));
        }
        PcMaster pc = pcOpt.get();
        CommandQueue c = new CommandQueue();
        c.setMacAddress(pc.getMacAddress());
        c.setCommandType(type);
        c.setScriptUrl(body.containsKey("scriptUrl") ? body.get("scriptUrl").toString() : null);
        c.setPayloadJson(body.containsKey("payloadJson") ? body.get("payloadJson").toString() : null);
        c.setStatus("QUEUED");
        c.setCreatedAt(LocalDateTime.now());
        c.setIsExecuted(false);
        CommandQueue saved = commandQueueRepository.save(c);
        notifyWaitingAgentsAfterCommit(List.of(pc.getMacAddress()));
        return ResponseEntity.accepted().body(Map.of(
                "queued", 1,
                "macAddress", pc.getMacAddress(),
                "taskId", saved.getTaskId()
        ));
    }

    /**
     * 명령 목록 조회 (GET /api/v1/commands)
     * ?status=QUEUED,DELIVERED,ACKED  → 해당 상태 필터 (기본: 진행 중 전체)
     * ?taskId=123                     → 단일 명령 조회
     * ?page=0&size=20                 → 페이지네이션 (기본: page=0, size=20)
     */
    @GetMapping("/commands")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "명령 목록 조회", description = "진행 중이거나 완료된 명령 목록을 조회합니다. 페이지네이션을 지원합니다.")
    public ResponseEntity<?> listCommands(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long taskId,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        if (taskId != null) {
            return commandQueueRepository.findById(taskId)
                .map(c -> ResponseEntity.ok(toCommandView(c,
                    pcMasterRepository.findById(c.getMacAddress()).orElse(null),
                    null)))
                    .orElse(ResponseEntity.notFound().build());
        }

        List<String> statuses = status != null && !status.isBlank()
            ? Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList())
                : List.of("QUEUED", "DELIVERED", "ACKED");

        Page<CommandQueue> pageResult = commandQueueRepository.findByStatusInOrderByCreatedAtDesc(
                statuses,
                PageRequest.of(Math.max(0, page), Math.max(1, size))
        );

        Map<String, PcMaster> pcByMac = pcMasterRepository.findAllById(
                        pageResult.getContent().stream()
                                .map(CommandQueue::getMacAddress)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(PcMaster::getMacAddress, pc -> pc));

        Map<Long, String> groupNameById = classroomGroupRepository.findAllById(
                        pcByMac.values().stream()
                                .map(PcMaster::getGroupId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName));

        Page<Map<String, Object>> viewPage = pageResult.map(c ->
                toCommandView(c, pcByMac.get(c.getMacAddress()), groupNameById));

        return ResponseEntity.ok(viewPage);
    }

    private Map<String, Object> toCommandView(CommandQueue c, PcMaster pc, Map<Long, String> groupNameById) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", c.getTaskId());
        row.put("macAddress", c.getMacAddress());
        row.put("commandType", c.getCommandType());
        row.put("status", c.getStatus());
        row.put("payloadJson", c.getPayloadJson());
        row.put("scriptUrl", c.getScriptUrl());
        row.put("createdAt", c.getCreatedAt());
        row.put("deliveredAt", c.getDeliveredAt());
        row.put("ackedAt", c.getAckedAt());
        row.put("completedAt", c.getCompletedAt());
        row.put("exitCode", c.getExitCode());
        row.put("stdout", c.getStdout());
        row.put("stderr", c.getStderr());
        row.put("retryCount", c.getRetryCount());
        row.put("isExecuted", c.getIsExecuted());
        row.put("verificationStatus", c.getVerificationStatus());
        row.put("verificationStartedAt", c.getVerificationStartedAt());
        row.put("verificationDeadlineAt", c.getVerificationDeadlineAt());
        row.put("verificationObservedOfflineAt", c.getVerificationObservedOfflineAt());
        row.put("verificationCompletedAt", c.getVerificationCompletedAt());
        row.put("scmateResetStatus", c.getScmateResetStatus());
        row.put("scmateResetRequestedAt", c.getScmateResetRequestedAt());
        row.put("scmateResetCompletedAt", c.getScmateResetCompletedAt());

        if (pc != null) {
            row.put("pcName", pc.getPcName());
            row.put("ipAddress", pc.getIpAddress());
            row.put("groupId", pc.getGroupId());
            row.put("pcStatus", pc.getStatus());
            row.put("role", pc.getRole());
            if (pc.getGroupId() != null && groupNameById != null) {
                row.put("groupName", groupNameById.get(pc.getGroupId()));
            }
        }
        return row;
    }

}

