package com.example.backend.service.command;

import com.example.backend.common.exception.ApplicationException;
import com.example.backend.common.exception.ConflictException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.event.AlertEvent;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.example.backend.service.scheduler.SchedulerService;
import com.example.backend.service.status.OperationalMetricsService;
import com.example.backend.util.IpRangeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PC 원격 명령의 전체 라이프사이클을 관리하는 서비스.
 *
 * <p>명령 발행(enqueue, enqueueToIp), 수신 확인(ack), 결과 보고(result),
 * 명령 목록 조회(listCommands)를 담당합니다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommandLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CommandLifecycleService.class);
    private static final int DEFAULT_RESULT_TEXT_LIMIT = 4_096;
    private static final int LIST_FILES_RESULT_TEXT_LIMIT = 256 * 1024;
    private static final int COLLECT_FILES_RESULT_TEXT_LIMIT = 32 * 1024;
    private static final Duration POWER_COMMAND_DEDUP_WINDOW = Duration.ofMinutes(2);

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final ClassroomIpRangeRepository classroomIpRangeRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SchedulerService schedulerService;
    private final FileDistributionOrchestrator fileDistributionOrchestrator;
    private final ApplicationEventPublisher eventPublisher;
    private final OperationalMetricsService operationalMetricsService;
    private final HeartbeatService heartbeatService;
    private final CommandPollRegistry commandPollRegistry;
    private final RouterWolService routerWolService;
    private final ScmateWorkflowService scmateWorkflowService;

    // ── 명령 발행 ────────────────────────────────────────────────────────────

    /**
     * 단일 PC 또는 그룹에 명령을 발행한다.
     * WOL 명령은 같은 강의실을 우선하고, 없으면 같은 /24의 온라인 에이전트를 통해 릴레이된다.
     */
    @Transactional
    public synchronized Map<String, Object> enqueue(CommandRequestDto req) {
        String scope = req.getScope() == null ? "" : req.getScope().toUpperCase(Locale.ROOT);
        String role = req.getRole() == null ? "ALL" : req.getRole().toUpperCase(Locale.ROOT);
        String type = req.getType() == null ? "" : req.getType().toUpperCase(Locale.ROOT);
        if ("PCON".equals(type)) {
            type = "WOL";
        }

        // SINGLE + WOL: 같은 강의실 우선, 같은 /24의 온라인 에이전트를 차선으로 사용
        if ("SINGLE".equals(scope) && "WOL".equals(type)) {
            if (req.getMacAddress() == null || req.getMacAddress().isBlank()) {
                throw new InvalidRequestException("macAddress required for SINGLE+WOL");
            }
            PcMaster targetPc = pcMasterRepository.findById(req.getMacAddress())
                    .orElseThrow(() -> new ResourceNotFoundException("target pc not found: " + req.getMacAddress()));
            if (targetPc.getGroupId() == null) {
                throw new InvalidRequestException("target groupId not resolved");
            }
            List<ClassroomIpRange> ranges = classroomIpRangeRepository.findByGroupId(targetPc.getGroupId());
            WolRoute route = resolveWolRoute(targetPc, ranges, req.getBroadcastIp());
            List<PcMaster> relays = pcMasterRepository.findByStatus("ONLINE");
            PcMaster relay = chooseWolRelay(relays, targetPc.getGroupId(), route, targetPc.getMacAddress());
            if (relay != null) {
                String payloadJson = buildWolPayloadJson(
                        targetPc.getMacAddress(), route.broadcastIp(), req.getPayloadJson());
                CommandQueue saved = saveWolRelayCommand(relay.getMacAddress(), payloadJson);
                if (saved == null) {
                    return duplicatePowerCommandResponse();
                }
                return Map.of(
                        "queued", 1,
                        "leaderMac", relay.getMacAddress(),
                        "broadcastIp", route.broadcastIp(),
                        "taskIds", List.of(saved.getTaskId()));
            }
            RouterWolService.Result routerResult = routerWolService.wake(
                    targetPc.getIpAddress(), List.of(targetPc.getMacAddress()));
            return directRouterWolResponse(routerResult, 1, "no-online-agent");
        }

        // GROUP + WOL: 대상별 /24에 속한 온라인 에이전트를 통해 WOL 전송
        if ("GROUP".equals(scope) && "WOL".equals(type)) {
            if (req.getGroupId() == null) {
                throw new InvalidRequestException("groupId required for GROUP+WOL");
            }
            List<PcMaster> relays = pcMasterRepository.findByStatus("ONLINE");
            String payloadJson = req.getPayloadJson();
            if (payloadJson != null && !payloadJson.isBlank()) {
                String payloadBroadcastIp = extractPayloadText(payloadJson, "broadcastIp");
                WolRoute payloadRoute = new WolRoute(
                        "payload",
                        payloadBroadcastIp,
                        slash24PrefixOrNull(payloadBroadcastIp));
                PcMaster relay = chooseWolRelay(relays, req.getGroupId(), payloadRoute, null);
                if (relay != null) {
                    CommandQueue saved = saveWolRelayCommand(relay.getMacAddress(), payloadJson);
                    if (saved == null) {
                        return duplicatePowerCommandResponse();
                    }
                    return Map.of("queued", 1, "leaderMac", relay.getMacAddress(), "taskIds", List.of(saved.getTaskId()));
                }
                List<String> payloadTargetMacs = extractTargetMacs(payloadJson);
                PcMaster routerTarget = findTargetPc(req.getGroupId(), payloadTargetMacs);
                RouterWolService.Result routerResult = routerWolService.wake(
                        routerTarget == null ? null : routerTarget.getIpAddress(), payloadTargetMacs);
                return directRouterWolResponse(
                        routerResult, payloadTargetMacs.size(), "no-online-agent-in-target-subnet");
            }

            List<ClassroomIpRange> ranges = classroomIpRangeRepository.findByGroupId(req.getGroupId());
            Map<String, WolRelayBatch> batches = new LinkedHashMap<>();
            Set<String> requestedMacs = normalizeTargetMacs(req.getTargetMacs());
            for (PcMaster pc : pcMasterRepository.findByGroupId(req.getGroupId())) {
                if (!isValidMac(pc.getMacAddress())) {
                    continue;
                }
                if (!requestedMacs.isEmpty() && !requestedMacs.contains(canonicalMac(pc.getMacAddress()))) {
                    continue;
                }
                if (requestedMacs.isEmpty() && isOnline(pc)) {
                    continue;
                }
                WolRoute route = resolveWolRoute(pc, ranges, req.getBroadcastIp());
                String key = route.key() + "|" + route.broadcastIp();
                batches.computeIfAbsent(key, ignored -> new WolRelayBatch(route, pc.getIpAddress()))
                        .targetMacs().add(pc.getMacAddress());
            }

            List<CommandQueue> savedCommands = new ArrayList<>();
            List<String> relayMacs = new ArrayList<>();
            List<String> routerGateways = new ArrayList<>();
            int duplicateSkipped = 0;
            int acceptedTargets = 0;
            int duplicateTargets = 0;
            int routerSignals = 0;
            int routerAttempts = 0;
            String routerMessage = null;
            for (WolRelayBatch batch : batches.values()) {
                PcMaster relay = chooseWolRelay(relays, req.getGroupId(), batch.route(), null);
                String relayMac = relay == null ? null : relay.getMacAddress();
                List<String> targetMacs = batch.targetMacs().stream()
                        .filter(mac -> relayMac == null || !mac.equalsIgnoreCase(relayMac))
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                if (targetMacs.isEmpty()) {
                    continue;
                }
                if (relay != null) {
                    CommandQueue saved = saveWolRelayCommand(
                            relayMac,
                            buildGroupWolPayloadJson(targetMacs, batch.route().broadcastIp()));
                    if (saved == null) {
                        duplicateSkipped++;
                        duplicateTargets += targetMacs.size();
                        continue;
                    }
                    savedCommands.add(saved);
                    relayMacs.add(relayMac);
                    acceptedTargets += targetMacs.size();
                    continue;
                }

                RouterWolService.Result routerResult = routerWolService.wake(batch.targetIp(), targetMacs);
                routerAttempts += routerResult.attempts();
                routerMessage = routerResult.message();
                if (routerResult.sent()) {
                    routerSignals++;
                    acceptedTargets += targetMacs.size();
                    if (routerResult.gateway() != null) {
                        routerGateways.add(routerResult.gateway());
                    }
                } else if (routerResult.duplicate()) {
                    duplicateSkipped++;
                    duplicateTargets += targetMacs.size();
                }
            }

            int requestedTargets = batches.values().stream()
                    .mapToInt(batch -> batch.targetMacs().size())
                    .sum();
            int failedTargets = Math.max(0, requestedTargets - acceptedTargets - duplicateTargets);

            int queuedRoutes = savedCommands.size() + routerSignals;
            if (queuedRoutes == 0) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("queued", 0);
                response.put("taskIds", List.of());
                response.put("duplicateSkipped", duplicateSkipped);
                response.put("acceptedTargets", acceptedTargets);
                response.put("duplicateTargets", duplicateTargets);
                response.put("failedTargets", failedTargets);
                response.put("message", duplicateSkipped > 0
                        ? "duplicate-power-command-in-progress"
                        : batches.isEmpty() ? "no-offline-target" : "no-online-agent-in-target-subnet");
                putRouterMetadata(response, routerSignals, routerAttempts, routerGateways, routerMessage);
                return response;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("queued", queuedRoutes);
            response.put("leaderMacs", relayMacs);
            response.put("taskIds", savedCommands.stream().map(CommandQueue::getTaskId).toList());
            response.put("duplicateSkipped", duplicateSkipped);
            response.put("acceptedTargets", acceptedTargets);
            response.put("duplicateTargets", duplicateTargets);
            response.put("failedTargets", failedTargets);
            putRouterMetadata(response, routerSignals, routerAttempts, routerGateways, routerMessage);
            return response;
        }
        if ("MULTI".equals(scope) && "WOL".equals(type)) {
            throw new InvalidRequestException("MULTI WOL must be issued as GROUP requests");
        }

        // 일반 명령: SINGLE, GROUP 또는 명시적 MAC 목록(MULTI) 스코프
        List<PcMaster> targets = new ArrayList<>();
        int requestedTargets = 0;
        if ("SINGLE".equals(scope)) {
            if (req.getMacAddress() == null || req.getMacAddress().isBlank()) {
                throw new InvalidRequestException("macAddress required for SINGLE scope");
            }
            pcMasterRepository.findById(req.getMacAddress()).ifPresent(targets::add);
            requestedTargets = 1;
        } else if ("GROUP".equals(scope)) {
            if (req.getGroupId() == null) {
                throw new InvalidRequestException("groupId required for GROUP scope");
            }
            if ("ALL".equals(role)) {
                targets.addAll(pcMasterRepository.findByGroupId(req.getGroupId()));
            } else {
                targets.addAll(pcMasterRepository.findByGroupIdAndRole(req.getGroupId(), role));
            }
            requestedTargets = targets.size();
        } else if ("MULTI".equals(scope)) {
            Set<String> requestedMacs = normalizeTargetMacs(req.getTargetMacs());
            if (requestedMacs.isEmpty()) {
                throw new InvalidRequestException("targetMacs required for MULTI scope");
            }
            requestedTargets = requestedMacs.size();
            targets.addAll(pcMasterRepository.findAll().stream()
                    .filter(pc -> requestedMacs.contains(canonicalMac(pc.getMacAddress())))
                    .toList());
        } else {
            throw new InvalidRequestException("scope must be SINGLE, GROUP or MULTI");
        }

        List<CommandQueue> commands = new ArrayList<>();
        int duplicateSkipped = 0;
        int offlineSkipped = 0;
        final String finalType = type;
        for (PcMaster pc : targets) {
            if (pc.getMacAddress() == null) continue;
            if (isPowerOffCommand(finalType) && !isOnline(pc)) {
                offlineSkipped++;
                continue;
            }
            if (isPowerCommand(finalType)
                    && hasRecentEquivalentPowerCommand(pc.getMacAddress(), finalType, req.getPayloadJson())) {
                duplicateSkipped++;
                continue;
            }
            CommandQueue c = new CommandQueue();
            c.setMacAddress(pc.getMacAddress());
            c.setCommandType(finalType);
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
        List<String> savedMacs = saved.stream()
                .map(CommandQueue::getMacAddress)
                .filter(Objects::nonNull)
                .toList();
        if (!savedMacs.isEmpty()) {
            notifyWaitingAgentsAfterCommit(savedMacs);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("queued", taskIds.size());
        response.put("taskIds", taskIds);
        response.put("duplicateSkipped", duplicateSkipped);
        response.put("offlineSkipped", offlineSkipped);
        response.put("requested", requestedTargets);
        response.put("notFoundSkipped", Math.max(0, requestedTargets - targets.size()));
        return response;
    }

    /**
     * IP 주소로 특정 PC를 찾아 명령을 발행한다.
     */
    @Transactional
    public Map<String, Object> enqueueToIp(String ipAddress, String rawType, String scriptUrl,
                                           String payloadJson, String broadcastIp) {
        if (ipAddress == null || ipAddress.isBlank()) {
            throw new InvalidRequestException("ipAddress is required");
        }
        if (rawType == null || rawType.isBlank()) {
            throw new InvalidRequestException("type is required");
        }
        String type = rawType.toUpperCase(Locale.ROOT);
        if ("PCON".equals(type)) {
            type = "WOL";
        }

        Optional<PcMaster> pcOpt = pcMasterRepository.findByIpAddress(ipAddress);

        // WOL: 같은 강의실 우선, 같은 /24의 온라인 에이전트를 차선으로 사용
        if ("WOL".equals(type)) {
            PcMaster targetPc = pcOpt.orElse(null);
            Long groupId = targetPc != null ? targetPc.getGroupId() : null;
            if (groupId == null) {
                throw new InvalidRequestException("대상 PC의 그룹을 찾을 수 없습니다.");
            }
            List<PcMaster> relays = pcMasterRepository.findByStatus("ONLINE");
            List<ClassroomIpRange> ranges = classroomIpRangeRepository.findByGroupId(groupId);
            WolRoute route = resolveWolRoute(targetPc, ranges, broadcastIp);
            PcMaster relay = chooseWolRelay(relays, groupId, route, targetPc.getMacAddress());
            if (relay != null) {
                String wolPayload = buildWolPayloadJson(targetPc.getMacAddress(), route.broadcastIp());
                CommandQueue saved = saveWolRelayCommand(relay.getMacAddress(), wolPayload);
                if (saved == null) {
                    return duplicatePowerCommandResponse();
                }
                return Map.of(
                        "queued", 1,
                        "macAddress", relay.getMacAddress(),
                        "broadcastIp", route.broadcastIp(),
                        "taskId", saved.getTaskId());
            }
            RouterWolService.Result routerResult = routerWolService.wake(
                    targetPc.getIpAddress(), List.of(targetPc.getMacAddress()));
            Map<String, Object> response = directRouterWolResponse(routerResult, 1, "no-online-agent");
            response.put("macAddress", targetPc.getMacAddress());
            response.put("broadcastIp", route.broadcastIp());
            return response;
        }

        // 일반 명령도 공통 발행 경로를 사용해 오프라인 및 중복 전원 명령을 동일하게 차단한다.
        PcMaster pc = pcOpt.orElseThrow(() ->
                new ResourceNotFoundException("PC not found for IP: " + ipAddress));
        CommandRequestDto request = new CommandRequestDto();
        request.setScope("SINGLE");
        request.setMacAddress(pc.getMacAddress());
        request.setType(type);
        request.setScriptUrl(scriptUrl);
        request.setPayloadJson(payloadJson);
        Map<String, Object> result = new LinkedHashMap<>(enqueue(request));
        result.put("macAddress", pc.getMacAddress());
        return result;
    }

    // ── 명령 수신 확인 / 결과 보고 ──────────────────────────────────────────

    /**
     * 에이전트가 명령을 수신했음을 확인한다. DELIVERED → ACKED 전환.
     */
    @Transactional
    public Map<String, Object> ack(Long taskId, String agentMac) {
        CommandQueue c = commandQueueRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
        if (!isCommandOwner(c, agentMac)) {
            throw new ApplicationException("FORBIDDEN", "Command owner mismatch", 403);
        }

        String currentStatus = normalizeStatus(c.getStatus());
        if (isFinalCommandStatus(currentStatus)) {
            return Map.of("status", "ok", "alreadyFinal", true, "commandStatus", currentStatus);
        }
        if ("ACKED".equals(currentStatus)) {
            return Map.of("status", "ok", "alreadyAcked", true);
        }
        if (!"DELIVERED".equals(currentStatus)) {
            throw new ConflictException("Command is not in DELIVERED state: " + currentStatus);
        }

        LocalDateTime ackedAt = LocalDateTime.now();
        int updated = commandQueueRepository.markAckedIfDelivered(taskId, ackedAt);
        if (updated == 0) {
            String latestStatus = fetchCurrentStatus(taskId);
            if (isFinalCommandStatus(latestStatus)) {
                return Map.of("status", "ok", "alreadyFinal", true, "commandStatus", latestStatus);
            }
            if ("ACKED".equals(latestStatus)) {
                return Map.of("status", "ok", "alreadyAcked", true);
            }
            throw new ConflictException("Command state changed before ACK update: " + latestStatus);
        }
        if (c.getDeliveredAt() != null) {
            operationalMetricsService.recordCommandAckLatencyMillis(
                    Duration.between(c.getDeliveredAt(), ackedAt).toMillis());
        }
        auditService.log(agentMac, "CMD_ACK", "COMMAND", String.valueOf(taskId));
        return Map.of("status", "ok");
    }

    /**
     * 에이전트가 명령 실행 결과를 보고한다. ACKED/DELIVERED → COMPLETED/FAILED 전환.
     */
    @Transactional
    public Map<String, Object> result(Long taskId, String agentMac, Map<String, Object> body) {
        CommandQueue c = commandQueueRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
        if (!isCommandOwner(c, agentMac)) {
            throw new ApplicationException("FORBIDDEN", "Command owner mismatch", 403);
        }

        String currentStatus = normalizeStatus(c.getStatus());
        if (isFinalCommandStatus(currentStatus)) {
            return Map.of("status", "ok", "alreadyFinal", true, "commandStatus", currentStatus);
        }
        if (!"ACKED".equals(currentStatus) && !"DELIVERED".equals(currentStatus)) {
            throw new ConflictException("Command is not in ACKED/DELIVERED state: " + currentStatus);
        }

        Object exit = body.get("exitCode");
        Integer exitCode = exit instanceof Number ? ((Number) exit).intValue() : null;
        int outputLimit = resolveOutputLimit(c.getCommandType());
        String stdout = body.get("stdout") == null ? null : truncate(body.get("stdout").toString(), outputLimit);
        String stderr = body.get("stderr") == null ? null : truncate(body.get("stderr").toString(), outputLimit);
        LocalDateTime completedAt = LocalDateTime.now();
        String newStatus = exitCode != null && exitCode == 0 ? "COMPLETED" : "FAILED";
        boolean success = "COMPLETED".equals(newStatus);

        if (success && isScmateRestoreCommand(c)) {
            // ShdCmd can return before Windows actually restarts. Keep the command in-flight
            // until HeartbeatService observes a different OS boot time and a stable SCMATE state.
            c.setStatus("ACKED");
            if (c.getAckedAt() == null) {
                c.setAckedAt(completedAt);
            }
            c.setExitCode(exitCode);
            c.setStdout(stdout);
            c.setStderr(stderr);
            c.setIsExecuted(false);
            commandQueueRepository.save(c);
            return Map.of("status", "ok", "awaitingAgentRestart", true, "commandStatus", "ACKED");
        }

        int updated = commandQueueRepository.markResultIfInProgress(taskId, newStatus, exitCode, stdout, stderr, completedAt);
        if (updated == 0) {
            String latestStatus = fetchCurrentStatus(taskId);
            if (isFinalCommandStatus(latestStatus)) {
                return Map.of("status", "ok", "alreadyFinal", true, "commandStatus", latestStatus);
            }
            throw new ConflictException("Command state changed before RESULT update: " + latestStatus);
        }

        CommandQueue persisted = commandQueueRepository.findById(taskId).orElse(null);
        if (persisted == null) {
            throw new ResourceNotFoundException("taskId not found after update: " + taskId);
        }
        schedulerService.armPostExecutionVerification(persisted, completedAt);
        handleAgentUpdateVerification(persisted, exitCode, stdout, stderr);
        commandQueueRepository.save(persisted);

        if (persisted.getAckedAt() != null) {
            operationalMetricsService.recordCommandResultLatencyMillis(
                    Duration.between(persisted.getAckedAt(), completedAt).toMillis(), success);
        } else {
            operationalMetricsService.recordCommandResultLatencyMillis(-1L, success);
        }
        reportFileDistributionResult(persisted, exitCode, stderr);
        scmateWorkflowService.onCommandResult(persisted, success, stdout, stderr);

        try {
            String detail = objectMapper.writeValueAsString(
                    Map.of("exitCode", String.valueOf(exitCode), "status", newStatus));
            auditService.log(agentMac, "CMD_RESULT", "COMMAND", String.valueOf(taskId), detail, null);
        } catch (Exception ex) {
            auditService.log(agentMac, "CMD_RESULT", "COMMAND", String.valueOf(taskId));
        }
        return Map.of("status", "ok");
    }

    // ── 명령 조회 ────────────────────────────────────────────────────────────

    /**
     * 단일 명령을 taskId로 조회한다.
     */
    public Map<String, Object> findCommandById(Long taskId) {
        return commandQueueRepository.findById(taskId)
                .map(c -> toCommandView(c, pcMasterRepository.findById(c.getMacAddress()).orElse(null), null))
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
    }

    public List<Map<String, Object>> findCommandsByIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        List<Long> orderedIds = taskIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, CommandQueue> commandsById = commandQueueRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(CommandQueue::getTaskId, command -> command));
        Map<String, PcMaster> pcByMac = pcMasterRepository.findAllById(
                        commandsById.values().stream()
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
        return orderedIds.stream()
                .map(commandsById::get)
                .filter(Objects::nonNull)
                .map(command -> toCommandView(command, pcByMac.get(command.getMacAddress()), groupNameById))
                .toList();
    }

    /**
     * 아직 에이전트에 전달되지 않은 명령을 관리자가 취소한다.
     * 모든 명령 종류에 공통 적용되며, 이미 전달/실행된 명령은 실제 실행과
     * 서버 상태가 어긋나지 않도록 취소하지 않는다.
     */
    @Transactional
    public Map<String, Object> cancelQueuedCommand(Long taskId) {
        CommandQueue command = commandQueueRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
        String currentStatus = normalizeStatus(command.getStatus());
        if ("CANCELLED".equals(currentStatus)) {
            return Map.of(
                    "taskId", taskId,
                    "status", "CANCELLED",
                    "alreadyCancelled", true);
        }
        if (!"QUEUED".equals(currentStatus)) {
            throw new ConflictException("Only QUEUED commands can be cancelled: " + currentStatus);
        }

        LocalDateTime cancelledAt = LocalDateTime.now();
        String reason = "cancelled by administrator before delivery";
        int updated = commandQueueRepository.cancelQueuedIfPending(taskId, cancelledAt, reason);
        if (updated == 0) {
            String latestStatus = fetchCurrentStatus(taskId);
            if ("CANCELLED".equals(latestStatus)) {
                return Map.of(
                        "taskId", taskId,
                        "status", "CANCELLED",
                        "alreadyCancelled", true);
            }
            throw new ConflictException("Command state changed before cancellation: " + latestStatus);
        }

        CommandQueue cancelled = commandQueueRepository.findById(taskId).orElse(command);
        cancelled.setStatus("CANCELLED");
        cancelled.setCompletedAt(cancelledAt);
        cancelled.setIsExecuted(true);
        cancelled.setStderr(reason);
        reportFileDistributionResult(cancelled, null, reason);
        scmateWorkflowService.onCommandCancelled(cancelled);
        auditService.log("admin", "CMD_CANCEL", "COMMAND", String.valueOf(taskId));
        return Map.of(
                "taskId", taskId,
                "status", "CANCELLED",
                "completedAt", cancelledAt);
    }

    /**
     * 상태별 명령 목록을 페이지네이션으로 조회한다.
     */
    public Page<Map<String, Object>> listCommands(String status, int page, int size) {
        List<String> statuses = status != null && !status.isBlank()
                ? Arrays.stream(status.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(s -> s.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toList())
                : List.of("QUEUED", "DELIVERED", "ACKED");

        Page<CommandQueue> pageResult = commandQueueRepository.findByStatusInOrderByCreatedAtDesc(
                statuses, PageRequest.of(Math.max(0, page), Math.max(1, size)));

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

        return pageResult.map(c -> toCommandView(c, pcByMac.get(c.getMacAddress()), groupNameById));
    }

    // ── WOL 헬퍼 ────────────────────────────────────────────────────────────

    private String buildWolPayloadJson(String targetMac, String broadcastIp) {
        return buildWolPayloadJson(targetMac, broadcastIp, null);
    }

    private String buildWolPayloadJson(String targetMac, String broadcastIp, String existingPayloadJson) {
        String bcast = (broadcastIp != null && !broadcastIp.isBlank()) ? broadcastIp : "255.255.255.255";
        try {
            var payload = objectMapper.createObjectNode();
            if (existingPayloadJson != null && !existingPayloadJson.isBlank()) {
                var existing = objectMapper.readTree(existingPayloadJson);
                if (existing != null && existing.isObject()) {
                    payload.setAll((com.fasterxml.jackson.databind.node.ObjectNode) existing);
                }
            }
            payload.put("targetMacs", targetMac != null ? targetMac : "");
            payload.put("broadcastIp", bcast);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("WOL 페이로드 직렬화 실패: targetMac={}, broadcastIp={}", targetMac, bcast);
            return null;
        }
    }

    private String buildGroupWolPayloadJson(List<String> targetMacs, String broadcastIp) {
        return buildWolPayloadJson(String.join(",", targetMacs), broadcastIp);
    }

    private Map<String, Object> directRouterWolResponse(RouterWolService.Result result,
                                                         int requestedTargets,
                                                         String noAgentMessage) {
        Map<String, Object> response = new LinkedHashMap<>();
        boolean sent = result != null && result.sent();
        boolean duplicate = result != null && result.duplicate();
        response.put("queued", sent ? 1 : 0);
        response.put("taskIds", List.of());
        response.put("acceptedTargets", sent ? requestedTargets : 0);
        response.put("duplicateSkipped", duplicate ? 1 : 0);
        response.put("duplicateTargets", duplicate ? requestedTargets : 0);
        response.put("failedTargets", sent || duplicate ? 0 : requestedTargets);
        response.put("message", sent
                ? "router-wol-sent"
                : duplicate ? "duplicate-power-command-in-progress" : noAgentMessage);
        putRouterMetadata(
                response,
                sent ? 1 : 0,
                result == null ? 0 : result.attempts(),
                result == null || result.gateway() == null ? List.of() : List.of(result.gateway()),
                result == null ? "router-wol-unavailable" : result.message());
        return response;
    }

    private void putRouterMetadata(Map<String, Object> response,
                                   int routerSignals,
                                   int routerAttempts,
                                   List<String> routerGateways,
                                   String routerMessage) {
        if (routerSignals == 0 && routerAttempts == 0 && routerMessage == null) {
            return;
        }
        response.put("routerSignals", routerSignals);
        response.put("routerAttempts", routerAttempts);
        response.put("routerGateways", routerGateways == null
                ? List.of()
                : routerGateways.stream().filter(Objects::nonNull).distinct().toList());
        if (routerMessage != null) {
            response.put("routerMessage", routerMessage);
        }
    }

    private List<String> extractTargetMacs(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return List.of();
        }
        try {
            var root = objectMapper.readTree(payloadJson);
            if (root == null || !root.has("targetMacs") || root.get("targetMacs").isNull()) {
                return List.of();
            }
            var node = root.get("targetMacs");
            List<String> macs = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> macs.add(item.asText()));
            } else {
                macs.addAll(Arrays.asList(node.asText().split(",")));
            }
            return macs.stream()
                    .map(String::trim)
                    .filter(this::isValidMac)
                    .distinct()
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private PcMaster findTargetPc(Long groupId, List<String> targetMacs) {
        Set<String> requested = normalizeTargetMacs(targetMacs);
        if (groupId == null || requested.isEmpty()) {
            return null;
        }
        return pcMasterRepository.findByGroupId(groupId).stream()
                .filter(pc -> requested.contains(canonicalMac(pc.getMacAddress())))
                .findFirst()
                .orElse(null);
    }

    private WolRoute resolveWolRoute(PcMaster pc, List<ClassroomIpRange> ranges, String explicitBroadcastIp) {
        String ip = pc == null ? null : pc.getIpAddress();
        String slash24Prefix = slash24PrefixOrNull(ip);
        if (explicitBroadcastIp != null && !explicitBroadcastIp.isBlank()) {
            String broadcast = explicitBroadcastIp.trim();
            return new WolRoute("explicit:" + broadcast, broadcast, slash24Prefix);
        }
        if (IpRangeUtil.isIpv4(ip)) {
            for (ClassroomIpRange range : ranges == null ? List.<ClassroomIpRange>of() : ranges) {
                if (IpRangeUtil.containsIp(ip, range.getCidr(), range.getStartIp(), range.getEndIp())) {
                    String broadcast = IpRangeUtil.broadcastFromCidr(range.getCidr());
                    if (broadcast == null) {
                        broadcast = ipv4Slash24Broadcast(ip);
                    }
                    String key = "range:" + (range.getId() != null ? range.getId() : rangeKey(range));
                    return new WolRoute(key, broadcast, slash24Prefix);
                }
            }
            String prefix = ipv4Slash24Prefix(ip);
            return new WolRoute("slash24:" + prefix, prefix + ".255", prefix);
        }
        return new WolRoute("global", "255.255.255.255", null);
    }

    private PcMaster chooseWolRelay(List<PcMaster> relays,
                                    Long targetGroupId,
                                    WolRoute route,
                                    String avoidMac) {
        if (relays == null || relays.isEmpty()) {
            return null;
        }
        String targetPrefix = route == null ? null : route.slash24Prefix();
        List<PcMaster> eligible = relays.stream()
                .filter(pc -> !sameMac(pc.getMacAddress(), avoidMac))
                .filter(pc -> targetPrefix == null
                        ? Objects.equals(pc.getGroupId(), targetGroupId)
                        : targetPrefix.equals(slash24PrefixOrNull(pc.getIpAddress())))
                .toList();
        Optional<PcMaster> sameGroup = eligible.stream()
                .filter(pc -> Objects.equals(pc.getGroupId(), targetGroupId))
                .findFirst();
        if (sameGroup.isPresent()) {
            return sameGroup.get();
        }
        return eligible.stream()
                .findFirst()
                .orElse(null);
    }

    private Set<String> normalizeTargetMacs(List<String> targetMacs) {
        if (targetMacs == null || targetMacs.isEmpty()) {
            return Set.of();
        }
        return targetMacs.stream()
                .map(this::canonicalMac)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isValidMac(String mac) {
        return mac != null && mac.trim().length() == 17;
    }

    private boolean sameMac(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String ipv4Slash24Prefix(String ip) {
        String[] parts = ip.trim().split("\\.");
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private String slash24PrefixOrNull(String ip) {
        return IpRangeUtil.isIpv4(ip) ? ipv4Slash24Prefix(ip) : null;
    }

    private String ipv4Slash24Broadcast(String ip) {
        return ipv4Slash24Prefix(ip) + ".255";
    }

    private String rangeKey(ClassroomIpRange range) {
        return String.join(":",
                String.valueOf(range.getGroupId()),
                String.valueOf(range.getCidr()),
                String.valueOf(range.getStartIp()),
                String.valueOf(range.getEndIp()));
    }

    private record WolRoute(String key, String broadcastIp, String slash24Prefix) {}

    private record WolRelayBatch(WolRoute route, String targetIp, List<String> targetMacs) {
        private WolRelayBatch(WolRoute route, String targetIp) {
            this(route, targetIp, new ArrayList<>());
        }
    }

    private CommandQueue saveWolRelayCommand(String leaderMac, String payloadJson) {
        if (hasRecentEquivalentPowerCommand(leaderMac, "WOL_RELAY", payloadJson)) {
            return null;
        }
        CommandQueue c = new CommandQueue();
        c.setMacAddress(leaderMac);
        c.setCommandType("WOL_RELAY");
        c.setPayloadJson(payloadJson);
        c.setStatus("QUEUED");
        c.setCreatedAt(LocalDateTime.now());
        c.setIsExecuted(false);
        CommandQueue saved = commandQueueRepository.save(c);
        notifyWaitingAgentsAfterCommit(List.of(leaderMac));
        return saved;
    }

    private boolean hasRecentEquivalentPowerCommand(String macAddress, String commandType, String payloadJson) {
        return commandQueueRepository.countRecentEquivalentPowerCommand(
                macAddress,
                commandType,
                payloadJson,
                LocalDateTime.now().minus(POWER_COMMAND_DEDUP_WINDOW)) > 0;
    }

    private boolean isPowerCommand(String commandType) {
        return isPowerOffCommand(commandType) || "WOL_RELAY".equals(commandType);
    }

    private boolean isPowerOffCommand(String commandType) {
        return "SHUTDOWN".equals(commandType) || "REBOOT".equals(commandType);
    }

    private boolean isOnline(PcMaster pc) {
        return pc != null && ("ONLINE".equalsIgnoreCase(pc.getStatus()) || "IDLE".equalsIgnoreCase(pc.getStatus()));
    }

    private Map<String, Object> duplicatePowerCommandResponse() {
        return Map.of(
                "queued", 0,
                "duplicateSkipped", 1,
                "message", "duplicate-power-command-in-progress");
    }

    // ── Long-poll 헬퍼 ───────────────────────────────────────────────────────

    /**
     * 트랜잭션 커밋 후 대기 중인 에이전트에 명령을 푸시한다.
     */
    void notifyWaitingAgentsAfterCommit(List<String> macAddresses) {
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

    private void pushCommandsToWaiters(List<String> macAddresses) {
        for (String mac : macAddresses) {
            if (mac == null) continue;
            String canonical = canonicalMac(mac);
            var waiter = commandPollRegistry.take(canonical);
            if (waiter == null) continue;
            Map<String, Object> cmd = null;
            try {
                cmd = heartbeatService.dispatchNextCommand(mac);
                if (cmd != null) {
                    boolean delivered = waiter.setResult(ResponseEntity.ok()
                            .cacheControl(org.springframework.http.CacheControl.noStore())
                            .body(Map.of("command", cmd)));
                    if (!delivered) {
                        heartbeatService.requeueDeliveredIfUnacked(commandTaskId(cmd));
                    }
                }
            } catch (Exception e) {
                if (cmd != null) {
                    heartbeatService.requeueDeliveredIfUnacked(commandTaskId(cmd));
                }
                log.debug("대기 에이전트 푸시 실패 mac={}: {}", mac, e.getMessage());
            }
        }
    }

    private Long commandTaskId(Map<String, Object> command) {
        Object value = command.get("taskId");
        return value instanceof Number number ? number.longValue() : null;
    }

    // ── 에이전트 업데이트 / 파일 배포 ────────────────────────────────────────

    private void handleAgentUpdateVerification(CommandQueue command,
                                               Integer exitCode, String stdout, String stderr) {
        if (!isAgentUpdateCommand(command)) {
            return;
        }
        LocalDateTime now = command.getCompletedAt() == null ? LocalDateTime.now() : command.getCompletedAt();
        if (exitCode != null && exitCode == 0) {
            command.setVerificationStatus("PENDING_AGENT_RESTART");
            command.setVerificationStartedAt(now);
            command.setVerificationDeadlineAt(now.plusMinutes(5));
            command.setVerificationObservedOfflineAt(null);
            command.setVerificationCompletedAt(null);
            String targetVersion = extractPayloadText(command.getPayloadJson(), "targetVersion");
            String versionText = targetVersion == null || targetVersion.isBlank() ? "unknown" : targetVersion;
            boolean fullBundle = "AGENT_BUNDLE_UPDATE".equalsIgnoreCase(command.getCommandType());
            eventPublisher.publishEvent(new AlertEvent(
                    this,
                    command.getMacAddress(),
                    "[업데이트 확인 중] " + command.getMacAddress()
                            + (fullBundle ? " 전체 번들 교체가 예약되어" : " 에이전트 JAR 교체가 완료되어")
                            + " 워치독 재시작과 버전(" + versionText
                            + ") 적용 여부를 확인 중입니다. PC 재부팅은 필요하지 않습니다. (명령 ID: "
                            + command.getTaskId() + ")",
                    "WARNING",
                    "UPDATE_RESTART_PENDING"
            ));
            return;
        }
        command.setVerificationStatus("FAILED");
        command.setVerificationCompletedAt(now);
        command.setVerificationDeadlineAt(now);
        command.setVerificationObservedOfflineAt(null);
        String failReason = stderr == null || stderr.isBlank() ? "agent update command failed" : stderr;
        command.setStderr(appendLine(command.getStderr(), failReason));
    }

    private void reportFileDistributionResult(CommandQueue command, Integer exitCode, String stderr) {
        if (command == null) return;
        Long jobId = extractPayloadLong(command.getPayloadJson(), "jobId");
        if (jobId == null) return;
        boolean success = exitCode != null && exitCode == 0;
        if (success && isAgentUpdateCommand(command)) {
            // 업데이트는 새 프로세스의 버전/번들 heartbeat가 확인된 뒤 SchedulerService가 성공 처리한다.
            return;
        }
        String error = success ? null : (stderr == null || stderr.isBlank() ? "command failed" : stderr);
        try {
            fileDistributionOrchestrator.reportAgentResult(jobId, command.getMacAddress(), success, error);
        } catch (Exception ex) {
            log.warn("Failed to report file-distribution result: taskId={}, jobId={}, mac={}",
                    command.getTaskId(), jobId, command.getMacAddress());
        }
    }

    private boolean isAgentUpdateCommand(CommandQueue command) {
        if (command == null || command.getCommandType() == null) return false;
        return "AGENT_UPDATE".equalsIgnoreCase(command.getCommandType())
                || "AGENT_BUNDLE_UPDATE".equalsIgnoreCase(command.getCommandType());
    }

    private boolean isScmateRestoreCommand(CommandQueue command) {
        return command != null
                && "SCMATE_CONTROL".equalsIgnoreCase(command.getCommandType())
                && "RESTORE_BASELINE".equalsIgnoreCase(
                        extractPayloadText(command.getPayloadJson(), "action"));
    }

    // ── 뷰 매핑 ─────────────────────────────────────────────────────────────

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

    // ── 유틸리티 ─────────────────────────────────────────────────────────────

    private boolean isCommandOwner(CommandQueue command, String agentMac) {
        String commandMac = canonicalMac(command == null ? null : command.getMacAddress());
        String callerMac = canonicalMac(agentMac);
        return commandMac != null && callerMac != null && commandMac.equals(callerMac);
    }

    private String canonicalMac(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) return null;
        String normalized = macAddress.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isFinalCommandStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String fetchCurrentStatus(Long taskId) {
        return commandQueueRepository.findById(taskId)
                .map(CommandQueue::getStatus)
                .map(this::normalizeStatus)
                .orElse("");
    }

    private int resolveOutputLimit(String commandType) {
        if (commandType == null || commandType.isBlank()) return DEFAULT_RESULT_TEXT_LIMIT;
        return switch (commandType.trim().toUpperCase(Locale.ROOT)) {
            case "LIST_FILES" -> LIST_FILES_RESULT_TEXT_LIMIT;
            case "COLLECT_FILES" -> COLLECT_FILES_RESULT_TEXT_LIMIT;
            default -> DEFAULT_RESULT_TEXT_LIMIT;
        };
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private String extractPayloadText(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank() || field == null) return null;
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node == null || !node.has(field) || node.get(field).isNull()) return null;
            String value = node.get(field).asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private Long extractPayloadLong(String payloadJson, String field) {
        String text = extractPayloadText(payloadJson, field);
        if (text == null) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String appendLine(String current, String line) {
        if (line == null || line.isBlank()) return current;
        if (current == null || current.isBlank()) return line;
        if (current.contains(line)) return current;
        return current + System.lineSeparator() + line;
    }
}
