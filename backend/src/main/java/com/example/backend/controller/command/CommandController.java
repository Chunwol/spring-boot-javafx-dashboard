package com.example.backend.controller.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.scheduler.SchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
/**
 * 원격 명령 관리 API
 * PC에 종료, 재부팅, WOL, PS1 스크립트 실행 등의 명령을 발행하고 결과를 수집합니다.
 * 
 * 명령 흐름:
 * 1. 관리자가 /commands에 명령 요청 (SINGLE 또는 GROUP 스코프)
 * 2. 서버가 CommandQueue에 저장
 * 3. 에이전트가 다음 하트비트 때 명령을 받음
 * 4. 에이전트가 실행 후 /agent/commands/{taskId}/result로 결과 보고
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Commands", description = "PC 원격 제어 및 명령 관리 API")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SchedulerService schedulerService;

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
            String payloadJson = req.getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                try {
                    String broadcastIp = (req.getBroadcastIp() != null && !req.getBroadcastIp().isBlank())
                            ? req.getBroadcastIp() : "255.255.255.255";
                    Map<String, String> wolPayload = new LinkedHashMap<>();
                    wolPayload.put("targetMacs", targetPc.getMacAddress());
                    wolPayload.put("broadcastIp", broadcastIp);
                    payloadJson = objectMapper.writeValueAsString(wolPayload);
                } catch (Exception e) {
                    return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
                }
            }

            CommandQueue c = new CommandQueue();
            c.setMacAddress(leader.getMacAddress());
            c.setCommandType("WOL_RELAY");
            c.setScriptUrl(null);
            c.setPayloadJson(payloadJson);
            c.setStatus("QUEUED");
            c.setCreatedAt(LocalDateTime.now());
            c.setIsExecuted(false);
            CommandQueue saved = commandQueueRepository.save(c);

            return ResponseEntity.accepted().body(Map.of(
                    "queued", 1,
                    "leaderMac", leader.getMacAddress(),
                    "taskIds", List.of(saved.getTaskId())
            ));
        }

        // Special-case: WOL via relay agent (one command to leader)
        if ("GROUP".equals(scope) && "WOL".equals(type)) {
            if (req.getGroupId() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "groupId required"));
            }
            List<PcMaster> leaders = pcMasterRepository.findByGroupIdAndStatus(req.getGroupId(), "ONLINE");
            if (leaders.isEmpty()) {
                return ResponseEntity.accepted().body(Map.of("queued", 0, "message", "no-online-agent"));
            }
            PcMaster leader = leaders.get(0);

            // targets: all PCs in group excluding leader (include offline too)
            List<PcMaster> all = pcMasterRepository.findByGroupId(req.getGroupId());
            List<String> macs = new ArrayList<>();
            for (PcMaster pc : all) {
                if (pc.getMacAddress() == null) continue;
                if (pc.getMacAddress().equalsIgnoreCase(leader.getMacAddress())) continue;
                macs.add(pc.getMacAddress());
            }
            String payloadJson = req.getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                try {
                    // ObjectMapper로 안전하게 직렬화 (JSON 인젝션 방지)
                    // broadcastIp: 요청에 명시된 값 또는 서브넷 브로드캐스트로 계산 가능하도록 전달
                    String broadcastIp = (req.getBroadcastIp() != null && !req.getBroadcastIp().isBlank())
                            ? req.getBroadcastIp() : "255.255.255.255";
                    Map<String, String> wolPayload = new LinkedHashMap<>();
                    wolPayload.put("targetMacs", String.join(",", macs));
                    wolPayload.put("broadcastIp", broadcastIp);
                    payloadJson = objectMapper.writeValueAsString(wolPayload);
                } catch (Exception e) {
                    return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
                }
            }
            CommandQueue c = new CommandQueue();
            c.setMacAddress(leader.getMacAddress());
            c.setCommandType("WOL_RELAY");
            c.setScriptUrl(null);
            c.setPayloadJson(payloadJson);
            c.setStatus("QUEUED");
            c.setCreatedAt(LocalDateTime.now());
            c.setIsExecuted(false);
            CommandQueue saved = commandQueueRepository.save(c);

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
        c.setStatus("ACKED");
        c.setAckedAt(LocalDateTime.now());
        commandQueueRepository.save(c);
        String agentMac = (String) request.getAttribute("agentMac");
        auditService.log(agentMac != null ? agentMac : c.getMacAddress(),
                "CMD_ACK", "COMMAND", String.valueOf(taskId));
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

        Object exit = body.get("exitCode");
        Integer exitCode = exit instanceof Number ? ((Number) exit).intValue() : null;
        // stdout/stderr 4KB 제한 (보안: 대용량 업로드 방지)
        String stdout = body.get("stdout") == null ? null : truncate(body.get("stdout").toString(), 4096);
        String stderr = body.get("stderr") == null ? null : truncate(body.get("stderr").toString(), 4096);
        c.setExitCode(exitCode);
        c.setStdout(stdout);
        c.setStderr(stderr);
        c.setCompletedAt(LocalDateTime.now());
        String newStatus = exitCode != null && exitCode == 0 ? "COMPLETED" : "FAILED";
        c.setStatus(newStatus);
        c.setIsExecuted(true);
        schedulerService.armPostExecutionVerification(c, c.getCompletedAt());
        commandQueueRepository.save(c);

        String agentMac = (String) request.getAttribute("agentMac");
        String actor = agentMac != null ? agentMac : c.getMacAddress();
        try {
            String detail = objectMapper.writeValueAsString(Map.of("exitCode", String.valueOf(exitCode), "status", newStatus));
            auditService.log(actor, "CMD_RESULT", "COMMAND", String.valueOf(taskId), detail, null);
        } catch (Exception ex) {
            auditService.log(actor, "CMD_RESULT", "COMMAND", String.valueOf(taskId));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
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
            String payloadJson;
            try {
                String broadcastIp = body.containsKey("broadcastIp")
                        ? body.get("broadcastIp").toString() : "255.255.255.255";
                payloadJson = objectMapper.writeValueAsString(Map.of(
                        "targetMacs", targetMac != null ? targetMac : "",
                        "broadcastIp", broadcastIp
                ));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("message", "페이로드 생성 실패"));
            }

            CommandQueue c = new CommandQueue();
            c.setMacAddress(leader.getMacAddress());
            c.setCommandType("WOL_RELAY");
            c.setPayloadJson(payloadJson);
            c.setStatus("QUEUED");
            c.setCreatedAt(LocalDateTime.now());
            c.setIsExecuted(false);
            CommandQueue saved = commandQueueRepository.save(c);
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

    /**
     * SQLite에 숫자형 epoch 문자열로 남은 시간값을 datetime text로 정규화한다.
     * 일부 데이터에서 JDBC Timestamp 파싱 오류가 발생하는 것을 방지한다.
     */
    private void normalizeCommandQueueTimestamps() {
        String[] columns = {
                "created_at",
                "delivered_at",
                "acked_at",
                "completed_at",
                "scmate_reset_requested_at",
                "scmate_reset_completed_at"
        };

        for (String column : columns) {
            String sql = "UPDATE command_queue " +
                    "SET " + column + " = datetime(" +
                    "CASE " +
                    "WHEN length(trim(CAST(" + column + " AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(" + column + " AS TEXT)), 1, 10) AS INTEGER) " +
                    "ELSE CAST(trim(CAST(" + column + " AS TEXT)) AS INTEGER) " +
                    "END, 'unixepoch') " +
                    "WHERE " + column + " IS NOT NULL " +
                    "AND trim(CAST(" + column + " AS TEXT)) <> '' " +
                    "AND trim(CAST(" + column + " AS TEXT)) GLOB '[0-9]*' " +
                    "AND trim(CAST(" + column + " AS TEXT)) NOT LIKE '%-%'";
            try {
                jdbcTemplate.update(sql);
            } catch (Exception e) {
                // SQLite 외 DB에서는 문법 차이가 있을 수 있으므로 조회 자체는 계속 진행한다.
                log.debug("Timestamp normalization skipped for column {}: {}", column, e.getMessage());
            }
        }
    }
}

