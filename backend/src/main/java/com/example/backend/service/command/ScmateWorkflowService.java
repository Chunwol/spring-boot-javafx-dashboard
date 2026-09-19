package com.example.backend.service.command;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.example.backend.util.AgentVersionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScmateWorkflowService {

    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final CommandPollRegistry commandPollRegistry;
    private final HeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;

    @Value("${app.scmate.cli-username:}")
    private String scmateCliUsername;

    @Value("${app.scmate.cli-password:}")
    private String scmateCliPassword;

    @Value("${app.heartbeat.timeout-minutes:2}")
    private long heartbeatTimeoutMinutes;

    @Transactional
    public synchronized Map<String, Object> perform(String macAddress, String rawAction) {
        return perform(macAddress, rawAction, "BASELINE");
    }

    @Transactional
    public synchronized Map<String, Object> perform(String macAddress, String rawAction, String rawRecoveryPoint) {
        return perform(macAddress, rawAction, rawRecoveryPoint, null);
    }

    @Transactional
    public synchronized Map<String, Object> perform(String macAddress, String rawAction,
                                                     String rawRecoveryPoint, String rawPath) {
        PcMaster pc = pcMasterRepository.findById(macAddress)
                .orElseThrow(() -> new ResourceNotFoundException("PC를 찾을 수 없습니다: " + macAddress));
        String action = rawAction.trim().toUpperCase(Locale.ROOT);
        String recoveryPoint = normalizeRecoveryPoint(rawRecoveryPoint);
        requireOnline(pc);
        ensureWorkflowId(pc);

        if ("REFRESH_STATUS_DETAIL".equals(action)) {
            requireAgentVersion(pc, 1, 1, 35, "SCMATE 상세 상태 조회");
            requireCredential();
            pc.setScmateWorkflowStage("STATUS_DETAIL_REQUESTED");
            pc.setScmateError(null);
            CommandQueue command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
            return saveNotifyAndRespond(pc, command, action, recoveryPoint);
        }
        if ("TEACHER".equalsIgnoreCase(pc.getRole())) {
            throw new ConflictException("학생 PC에서만 SCMATE 작업을 직접 실행할 수 있습니다.");
        }
        if ("LIST_EXCEPTION_FOLDERS".equals(action)
                || "ADD_EXCEPTION_FOLDER".equals(action)
                || "REMOVE_EXCEPTION_FOLDER".equals(action)) {
            requireScmateAvailable(pc);
            requireAgentVersion(pc, 1, 1, 70, "SCMATE 예외 폴더 관리");
            requireCredential();
            String path = normalizeExceptionPath(action, rawPath);
            pc.setScmateError(null);
            CommandQueue command = queue(pc, "SCMATE_CONTROL",
                    exceptionFolderPayload(action, recoveryPoint, path));
            return saveNotifyAndRespond(pc, command, action, recoveryPoint);
        }
        if ("INSTALL_RECOVERY_MODULE".equals(action)) {
            return installRecoveryModule(pc, action, recoveryPoint);
        }
        if ("ENABLE_PROTECTION".equals(action)) {
            return enableProtection(pc, action, recoveryPoint);
        }

        CommandQueue command;
        switch (action) {
            case "REQUEST_OPERATOR" -> {
                requireScmateAvailable(pc);
                requireAgentVersion(pc, 1, 1, 39, "SCMATE 모드 변경");
                requireCredential();
                recordWorkflowBootTime(pc);
                pc.setScmateWorkflowStage("OPERATOR_REQUESTED");
                command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
            }
            case "REBOOT_FOR_BASELINE", "REBOOT_TO_OPERATOR" -> {
                requireAgentVersion(pc, 1, 1, 27, "SCMATE 시점 저장 준비");
                requireSystemBootTime(pc);
                recordWorkflowBootTime(pc);
                pc.setScmateWorkflowStage("WAITING_REBOOT");
                command = queue(pc, "REBOOT", rebootPayload(pc, action, recoveryPoint));
            }
            case "UPDATE_BASELINE" -> {
                requireScmateAvailable(pc);
                requireAgentVersion(pc, 1, 1, 35, "SCMATE 시점 저장");
                requireStableManual(pc);
                if (pc.getScmateWorkflowStartBootTimeEpochSeconds() == null
                        || pc.getSystemBootTimeEpochSeconds() == null
                        || Objects.equals(pc.getScmateWorkflowStartBootTimeEpochSeconds(),
                                pc.getSystemBootTimeEpochSeconds())) {
                    throw new ConflictException("시점 저장 전 재부팅이 확인되지 않아 기준 시점을 저장할 수 없습니다.");
                }
                requireCredential();
                pc.setScmateWorkflowStage("BASELINE_REQUESTED");
                command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
            }
            case "REQUEST_PROTECTED" -> {
                requireScmateAvailable(pc);
                requireAgentVersion(pc, 1, 1, 39, "SCMATE 모드 변경");
                requireCredential();
                recordWorkflowBootTime(pc);
                pc.setScmateWorkflowStage("PROTECTION_REQUESTED");
                command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
            }
            case "REBOOT_TO_PROTECTED" -> {
                requireAgentVersion(pc, 1, 1, 27, "SCMATE 보호 모드 재부팅");
                requireSystemBootTime(pc);
                recordWorkflowBootTime(pc);
                pc.setScmateWorkflowStage("WAITING_PROTECTED_REBOOT");
                command = queue(pc, "REBOOT", rebootPayload(pc, action, recoveryPoint));
            }
            case "RESTORE_BASELINE" -> {
                requireScmateAvailable(pc);
                requireAgentVersion(pc, 1, 1, 35, "시스템 복구");
                requireSystemBootTime(pc);
                requireCredential();
                recordWorkflowBootTime(pc);
                pc.setScmateWorkflowStage("RESTORE_REQUESTED");
                pc.setScmateError(null);
                command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
            }
            default -> throw new ConflictException("지원하지 않는 SCMATE 작업입니다: " + action);
        }

        return saveNotifyAndRespond(pc, command, action, recoveryPoint);
    }

    private Map<String, Object> installRecoveryModule(PcMaster pc, String action, String recoveryPoint) {
        if (!"NOT_INSTALLED".equalsIgnoreCase(pc.getScmateAvailabilityReason())) {
            throw new ConflictException("복구모듈 설치안됨 상태인 PC에서만 설치할 수 있습니다.");
        }
        if ("INSTALL_PENDING_REBOOT".equals(pc.getScmateWorkflowStage())) {
            throw new ConflictException("복구모듈 설치가 완료되어 재부팅을 기다리고 있습니다.");
        }
        requireAgentVersion(pc, 1, 1, 28, "SCMATE 복구모듈 설치");
        recordWorkflowBootTime(pc);
        pc.setScmateWorkflowStage("INSTALL_REQUESTED");
        pc.setScmateError(null);
        CommandQueue command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
        return saveNotifyAndRespond(pc, command, action, recoveryPoint);
    }

    private Map<String, Object> enableProtection(PcMaster pc, String action, String recoveryPoint) {
        if (!"PROTECT_DISABLED".equalsIgnoreCase(pc.getScmateAvailabilityReason())) {
            throw new ConflictException("보호 비활성 상태인 PC에서만 보호를 활성화할 수 있습니다.");
        }
        requireAgentVersion(pc, 1, 1, 40, "SCMATE 보호 활성화");
        requireCredential();
        pc.setScmateWorkflowStage("PROTECTION_ENABLE_REQUESTED");
        pc.setScmateError(null);
        CommandQueue command = queue(pc, "SCMATE_CONTROL", payload(action, recoveryPoint));
        return saveNotifyAndRespond(pc, command, action, recoveryPoint);
    }

    private Map<String, Object> saveNotifyAndRespond(PcMaster pc, CommandQueue command,
                                                      String action, String recoveryPoint) {
        pcMasterRepository.save(pc);
        notifyWaitingAgentAfterCommit(pc.getMacAddress());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", command.getTaskId());
        response.put("workflowId", pc.getScmateWorkflowId());
        response.put("stage", pc.getScmateWorkflowStage());
        response.put("action", action);
        response.put("recoveryPoint", recoveryPoint);
        response.put("macAddress", pc.getMacAddress());
        return response;
    }

    @Transactional
    public void onCommandResult(CommandQueue command, boolean success, String stdout, String stderr) {
        if (!"SCMATE_CONTROL".equalsIgnoreCase(command.getCommandType())) {
            return;
        }
        pcMasterRepository.findById(command.getMacAddress()).ifPresent(pc -> {
            String action = payloadAction(command.getPayloadJson());
            String recoveryPoint = payloadRecoveryPoint(command.getPayloadJson());
            boolean resumesOperatorRequest = "REQUEST_OPERATOR".equals(
                    payloadText(command.getPayloadJson(), "resumeAction"));
            if (!success) {
                String error = trim(stderr, 255);
                if ("ENABLE_PROTECTION".equals(action)
                        && resumesOperatorRequest
                        && error != null
                        && error.startsWith("SCMATE_PROTECTION_NOT_DISABLED:")) {
                    pc.setScmateWorkflowStage("OPERATOR_ENABLE_PENDING");
                    pc.setScmateError(null);
                } else {
                    pc.setScmateWorkflowStage("FAILED");
                    pc.setScmateError(error != null
                            ? error
                            : ("RESTORE_BASELINE".equals(action)
                                     ? "시스템 복구 명령 실행에 실패했습니다."
                                     : "SCMATE 명령 실행에 실패했습니다."));
                }
            } else {
                JsonNode detail = parseResult(stdout);
                if (detail != null) {
                    applyScmateDetail(pc, detail);
                }
                if ("UPDATE_BASELINE".equals(action)) {
                    LocalDateTime createdAt = findRecoveryPointCreatedAt(detail, recoveryPoint);
                    if (createdAt == null) {
                        pc.setScmateWorkflowStage("RECOVERY_POINT_UNVERIFIED");
                        pc.setScmateError(recoveryPoint + " 시점의 createdAt을 에이전트 결과에서 확인하지 못했습니다.");
                    } else {
                        saveRecoveryPointCreatedAt(pc, recoveryPoint, createdAt);
                        pc.setScmateSelectedPoint(recoveryPoint);
                        pc.setScmateWorkflowStage("BASELINE_UPDATED");
                        pc.setScmateError(null);
                    }
                } else if ("REFRESH_STATUS_DETAIL".equals(action)) {
                    pc.setScmateWorkflowStage(detail == null
                            ? "STATUS_DETAIL_UNVERIFIED"
                            : "STATUS_DETAIL_UPDATED");
                    pc.setScmateError(detail == null
                            ? "에이전트 상세 상태 결과를 확인하지 못했습니다."
                            : null);
                } else if ("INSTALL_RECOVERY_MODULE".equals(action)) {
                    pc.setScmateWorkflowStage("INSTALL_PENDING_REBOOT");
                    pc.setScmateError(null);
                } else if ("ENABLE_PROTECTION".equals(action)) {
                    pc.setScmateWorkflowStage(resumesOperatorRequest
                            ? "OPERATOR_ENABLE_PENDING"
                            : "PROTECTION_ENABLE_PENDING");
                    pc.setScmateError(null);
                }
            }
            pcMasterRepository.save(pc);
        });
    }

    @Transactional
    public void onCommandCancelled(CommandQueue command) {
        if (!isScmateWorkflowCommand(command)) {
            return;
        }
        pcMasterRepository.findById(command.getMacAddress()).ifPresent(pc -> {
            pc.setScmateWorkflowStage("CANCELLED");
            pc.setScmateError(null);
            pcMasterRepository.save(pc);
        });
    }

    private CommandQueue queue(PcMaster pc, String type, String payloadJson) {
        List<CommandQueue> active = commandQueueRepository
                .findByMacAddressAndStatusInOrderByCreatedAtAsc(
                        pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"));
        if (!active.isEmpty()) {
            throw new ConflictException("대상 PC에 이미 진행 중인 명령이 있습니다.");
        }
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType(type);
        command.setPayloadJson(payloadJson);
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        command.setIsExecuted(false);
        return commandQueueRepository.save(command);
    }

    private void requireOnline(PcMaster pc) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1L, heartbeatTimeoutMinutes));
        if (!"ONLINE".equalsIgnoreCase(pc.getStatus())
                || pc.getLastSeen() == null
                || pc.getLastSeen().isBefore(cutoff)) {
            throw new ConflictException("온라인 PC에서만 SCMATE를 제어할 수 있습니다.");
        }
    }

    private boolean isScmateWorkflowCommand(CommandQueue command) {
        if (command == null) {
            return false;
        }
        if ("SCMATE_CONTROL".equalsIgnoreCase(command.getCommandType())) {
            return true;
        }
        if (!"REBOOT".equalsIgnoreCase(command.getCommandType())) {
            return false;
        }
        try {
            JsonNode payload = objectMapper.readTree(command.getPayloadJson() == null ? "{}" : command.getPayloadJson());
            return payload.hasNonNull("scmateWorkflowId");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requireScmateAvailable(PcMaster pc) {
        if (!Boolean.TRUE.equals(pc.getScmateAvailable())) {
            throw new ConflictException("대상 PC에서 SCMATE 상태가 확인되지 않았습니다.");
        }
    }

    private void requireStableManual(PcMaster pc) {
        if (!"MANUAL".equalsIgnoreCase(pc.getScmateSchedule())
                || Boolean.TRUE.equals(pc.getScmateTransitionPending())) {
            throw new ConflictException("SCMATE가 안정된 MANUAL 스케줄이 아닙니다.");
        }
    }

    private void requireSystemBootTime(PcMaster pc) {
        if (pc.getSystemBootTimeEpochSeconds() == null) {
            throw new ConflictException("현재 시스템 부팅 시각을 확인할 수 없어 작업을 시작할 수 없습니다.");
        }
    }

    private void ensureWorkflowId(PcMaster pc) {
        if (pc.getScmateWorkflowId() == null || pc.getScmateWorkflowId().isBlank()) {
            pc.setScmateWorkflowId(UUID.randomUUID().toString());
        }
    }

    private void recordWorkflowBootTime(PcMaster pc) {
        pc.setScmateWorkflowStartBootTimeEpochSeconds(pc.getSystemBootTimeEpochSeconds());
    }

    private void requireCredential() {
        if (scmateCliUsername == null || scmateCliUsername.isBlank()
                || scmateCliPassword == null || scmateCliPassword.isBlank()) {
            throw new ConflictException("서버에 SCMATE 제어 자격증명이 설정되지 않았습니다.");
        }
    }

    private void requireAgentVersion(PcMaster pc, int major, int minor, int patch, String operation) {
        if (!AgentVersionUtil.isAtLeast(pc.getAgentVersion(), major, minor, patch)) {
            throw new ConflictException(operation + "은(는) 에이전트 "
                    + major + "." + minor + "." + patch + " 이상에서만 사용할 수 있습니다.");
        }
    }

    private String payload(String action, String recoveryPoint) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "action", action,
                    "recoveryPoint", recoveryPoint));
        } catch (Exception e) {
            throw new IllegalStateException("SCMATE 명령 생성에 실패했습니다.", e);
        }
    }

    private String exceptionFolderPayload(String action, String recoveryPoint, String path) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("action", action);
            value.put("recoveryPoint", recoveryPoint);
            if (path != null) value.put("path", path);
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("SCMATE 예외 폴더 명령 생성에 실패했습니다.", e);
        }
    }

    private String normalizeExceptionPath(String action, String rawPath) {
        if ("LIST_EXCEPTION_FOLDERS".equals(action)) return null;
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.matches("(?i)^[a-z]:\\\\[^\\r\\n\\u0000\"*?<>|]{1,237}$")) {
            throw new ConflictException("절대 Windows 폴더 경로를 입력해 주세요.");
        }
        while (path.length() > 3 && (path.endsWith("\\") || path.endsWith("/"))) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String rebootPayload(PcMaster pc, String action, String recoveryPoint) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "scmateWorkflowId", pc.getScmateWorkflowId(),
                    "scmateAction", action,
                    "action", action,
                    "recoveryPoint", recoveryPoint));
        } catch (Exception e) {
            throw new IllegalStateException("SCMATE 재부팅 명령 생성에 실패했습니다.", e);
        }
    }

    private String payloadAction(String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return node.path("action").asText("").toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private String payloadRecoveryPoint(String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return normalizeRecoveryPoint(node.path("recoveryPoint").asText("BASELINE"));
        } catch (Exception e) {
            return "BASELINE";
        }
    }

    private String payloadText(String payloadJson, String field) {
        try {
            String value = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson)
                    .path(field).asText("");
            return value.trim().toUpperCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeRecoveryPoint(String recoveryPoint) {
        String normalized = recoveryPoint == null || recoveryPoint.isBlank()
                ? "BASELINE"
                : recoveryPoint.trim().toUpperCase(Locale.ROOT);
        if (!List.of("BASELINE", "SNAP1", "SNAP2").contains(normalized)) {
            throw new ConflictException("지원하지 않는 복구 시점입니다: " + normalized);
        }
        return normalized;
    }

    private JsonNode parseResult(String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        try {
            return objectMapper.readTree(stdout);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyScmateDetail(PcMaster pc, JsonNode root) {
        JsonNode bootTime = findField(root, "systemBootTimeEpochSeconds");
        if (bootTime != null && bootTime.canConvertToLong()) {
            pc.setSystemBootTimeEpochSeconds(bootTime.longValue());
        }
        JsonNode available = findField(root, "scmateAvailable", "available");
        if (available != null && available.isBoolean()) pc.setScmateAvailable(available.booleanValue());
        String availabilityReason = findText(root, "scmateAvailabilityReason", "availabilityReason");
        if (availabilityReason != null) pc.setScmateAvailabilityReason(normalizeToken(availabilityReason));
        String schedule = findText(root, "scmateSchedule", "schedule");
        if (schedule != null) pc.setScmateSchedule(normalizeToken(schedule));
        String mode = findText(root, "scmateMode", "mode");
        if (mode != null || schedule != null) pc.setScmateMode(normalizeMode(mode, schedule));
        String selectedPoint = findSelectedPoint(root);
        if (selectedPoint != null) pc.setScmateSelectedPoint(normalizeReportedRecoveryPoint(selectedPoint));
        String productVariant = findText(root, "scmateProductVariant", "productVariant");
        if (productVariant != null) pc.setScmateProductVariant(trim(productVariant, 80));
        String stateSource = findText(root, "scmateStateSource", "stateSource");
        if (stateSource != null) pc.setScmateStateSource(normalizeToken(stateSource));
        JsonNode transitionPending = findField(root, "scmateTransitionPending", "transitionPending");
        if (transitionPending != null && transitionPending.isBoolean()) {
            pc.setScmateTransitionPending(transitionPending.booleanValue());
        }
        JsonNode rawState = findField(root, "scmateRawState", "rawState", "raw");
        if (rawState != null && rawState.canConvertToLong()) pc.setScmateRawState(rawState.longValue());

        LocalDateTime baseline = findRecoveryPointCreatedAt(root, "BASELINE");
        LocalDateTime snap1 = findRecoveryPointCreatedAt(root, "SNAP1");
        LocalDateTime snap2 = findRecoveryPointCreatedAt(root, "SNAP2");
        if (baseline != null) pc.setScmateBaselineCreatedAt(baseline);
        if (snap1 != null) pc.setScmateSnap1CreatedAt(snap1);
        if (snap2 != null) pc.setScmateSnap2CreatedAt(snap2);
    }

    private String normalizeMode(String mode, String schedule) {
        String normalizedSchedule = normalizeToken(schedule);
        if ("MANUAL".equals(normalizedSchedule)) return "OPERATOR";
        if (List.of("RESTART", "DAILY_FIRST_BOOT", "WEEKLY").contains(normalizedSchedule)) return "PROTECTED";
        String normalizedMode = normalizeToken(mode);
        if ("OPERATOR".equals(normalizedMode) || "PROTECTED".equals(normalizedMode)) return normalizedMode;
        return "UNKNOWN";
    }

    private String normalizeToken(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeReportedRecoveryPoint(String recoveryPoint) {
        String normalized = normalizeToken(recoveryPoint);
        if (normalized == null || "UNKNOWN".equals(normalized)) return "UNKNOWN";
        return normalizeRecoveryPoint(normalized);
    }

    private String findSelectedPoint(JsonNode root) {
        JsonNode selected = findField(root, "scmateSelectedPoint", "selectedPoint");
        if (selected == null) return null;
        if (selected.isTextual()) return selected.asText();
        if (selected.isObject()) {
            JsonNode name = firstField(selected, "recoveryPoint", "point", "name", "id");
            return name == null ? null : name.asText(null);
        }
        return null;
    }

    private LocalDateTime findRecoveryPointCreatedAt(JsonNode root, String recoveryPoint) {
        if (root == null) return null;
        String prefix = switch (recoveryPoint) {
            case "SNAP1" -> "snap1";
            case "SNAP2" -> "snap2";
            default -> "baseline";
        };
        JsonNode direct = findField(root, "scmate" + Character.toUpperCase(prefix.charAt(0))
                + prefix.substring(1) + "CreatedAt", prefix + "CreatedAt");
        LocalDateTime parsed = parseCreatedAt(direct);
        if (parsed != null) return parsed;

        for (JsonNode candidate : candidateNodes(root)) {
            JsonNode selected = candidate.get("selectedPoint");
            if (selected != null && selected.isObject()) {
                String selectedName = textFromObject(selected, "recoveryPoint", "point", "name", "id");
                if (selectedName == null || recoveryPoint.equalsIgnoreCase(selectedName)) {
                    parsed = parseCreatedAt(selected.get("createdAt"));
                    if (parsed != null) return parsed;
                }
            }
            for (String containerName : List.of("recoveryPoints", "points", "snapshots")) {
                JsonNode container = candidate.get(containerName);
                parsed = findCreatedAtInContainer(container, recoveryPoint);
                if (parsed != null) return parsed;
            }
            JsonNode pointNode = candidate.get(recoveryPoint);
            if (pointNode == null) pointNode = candidate.get(recoveryPoint.toLowerCase(Locale.ROOT));
            parsed = parseCreatedAt(pointNode == null ? null : pointNode.get("createdAt"));
            if (parsed != null) return parsed;
        }
        return null;
    }

    private LocalDateTime findCreatedAtInContainer(JsonNode container, String recoveryPoint) {
        if (container == null) return null;
        if (container.isObject()) {
            JsonNode point = container.get(recoveryPoint);
            if (point == null) point = container.get(recoveryPoint.toLowerCase(Locale.ROOT));
            return parseCreatedAt(point == null ? null : point.get("createdAt"));
        }
        if (container.isArray()) {
            for (JsonNode point : container) {
                String name = textFromObject(point, "recoveryPoint", "point", "name", "id");
                if (recoveryPoint.equalsIgnoreCase(name)) {
                    LocalDateTime parsed = parseCreatedAt(point.get("createdAt"));
                    if (parsed != null) return parsed;
                }
            }
        }
        return null;
    }

    private LocalDateTime parseCreatedAt(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) {
            long epoch = value.longValue();
            Instant instant = epoch > 100_000_000_000L
                    ? Instant.ofEpochMilli(epoch)
                    : Instant.ofEpochSecond(epoch);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        String text = value.asText(null);
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.trim());
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text.trim()).toLocalDateTime();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(text.trim()), ZoneId.systemDefault());
                } catch (DateTimeParseException ignoredInstant) {
                    return null;
                }
            }
        }
    }

    private List<JsonNode> candidateNodes(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        JsonNode details = root.get("details");
        if (details != null && details.isObject()) nodes.add(details);
        nodes.add(root);
        for (String field : List.of("detail", "status", "after", "result")) {
            JsonNode node = root.get(field);
            if (node != null && node.isObject()) nodes.add(node);
        }
        return nodes;
    }

    private JsonNode findField(JsonNode root, String... names) {
        if (root == null) return null;
        for (JsonNode candidate : candidateNodes(root)) {
            JsonNode field = firstField(candidate, names);
            if (field != null && !field.isNull()) return field;
        }
        return null;
    }

    private JsonNode firstField(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return null;
        for (String name : names) {
            JsonNode field = node.get(name);
            if (field != null) return field;
        }
        return null;
    }

    private String findText(JsonNode root, String... names) {
        JsonNode value = findField(root, names);
        return value == null || !value.isValueNode() ? null : value.asText(null);
    }

    private String textFromObject(JsonNode node, String... names) {
        JsonNode value = firstField(node, names);
        return value == null ? null : value.asText(null);
    }

    private void saveRecoveryPointCreatedAt(PcMaster pc, String recoveryPoint, LocalDateTime createdAt) {
        switch (recoveryPoint) {
            case "SNAP1" -> pc.setScmateSnap1CreatedAt(createdAt);
            case "SNAP2" -> pc.setScmateSnap2CreatedAt(createdAt);
            default -> {
                pc.setScmateBaselineCreatedAt(createdAt);
                pc.setScmateBaselineUpdatedAt(createdAt);
            }
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void notifyWaitingAgentAfterCommit(String macAddress) {
        Runnable push = () -> {
            Map<String, Object> command = null;
            try {
                var waiter = commandPollRegistry.take(canonicalMac(macAddress));
                if (waiter == null) return;
                command = heartbeatService.dispatchNextCommand(macAddress);
                if (command != null) {
                    boolean delivered = waiter.setResult(ResponseEntity.ok()
                            .cacheControl(org.springframework.http.CacheControl.noStore())
                            .body(Map.of("command", command)));
                    if (!delivered) {
                        heartbeatService.requeueDeliveredIfUnacked(commandTaskId(command));
                    }
                }
            } catch (RuntimeException e) {
                if (command != null) {
                    heartbeatService.requeueDeliveredIfUnacked(commandTaskId(command));
                }
                log.warn("SCMATE 명령 즉시 전달 실패, 다음 에이전트 폴링에서 재시도합니다: mac={}, cause={}",
                        canonicalMac(macAddress), e.getClass().getSimpleName());
            }
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            push.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push.run();
            }
        });
    }

    private String canonicalMac(String macAddress) {
        return macAddress.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    private Long commandTaskId(Map<String, Object> command) {
        Object value = command.get("taskId");
        return value instanceof Number number ? number.longValue() : null;
    }
}
