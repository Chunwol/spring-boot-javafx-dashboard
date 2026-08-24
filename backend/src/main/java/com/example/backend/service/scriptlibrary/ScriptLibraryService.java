package com.example.backend.service.scriptlibrary;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.dto.scriptlibrary.ScriptLibraryExecutionRequest;
import com.example.backend.dto.scriptlibrary.ScriptLibraryRerunRequest;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.model.scriptlibrary.ScriptLibraryExecution;
import com.example.backend.model.scriptlibrary.ScriptLibraryExecutionTarget;
import com.example.backend.model.scriptlibrary.ScriptLibraryTemplate;
import com.example.backend.model.scriptlibrary.ScriptLibraryVersion;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryExecutionRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryExecutionTargetRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryTemplateRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryVersionRepository;
import com.example.backend.service.command.CommandLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScriptLibraryService {
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;
    private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> PS_REQUIREMENTS = Set.of("WINDOWS_POWERSHELL_5_1", "POWERSHELL_7");
    private static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final ScriptLibraryTemplateRepository templateRepository;
    private final ScriptLibraryVersionRepository versionRepository;
    private final ScriptLibraryExecutionRepository executionRepository;
    private final ScriptLibraryExecutionTargetRepository targetRepository;
    private final ScriptLibraryArtifactService artifactService;
    private final PcMasterRepository pcRepository;
    private final CommandQueueRepository commandRepository;
    private final CommandLifecycleService commandLifecycleService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates() {
        return templateRepository.findByActiveTrueOrderByBuiltInKeyDescNameAsc().stream()
                .map(this::templateRow)
                .toList();
    }

    @Transactional
    public Map<String, Object> createTemplate(String name,
                                              String description,
                                              String category,
                                              String riskLevel,
                                              boolean requiresReboot,
                                              boolean requiresOperatorMode,
                                              String powershellRequirement,
                                              String successMarker,
                                              Long inverseTemplateId,
                                              String filename,
                                              byte[] content) {
        validateMetadata(name, description, category, riskLevel, powershellRequirement, successMarker);
        validateScript(filename, content);
        LocalDateTime now = LocalDateTime.now();
        ScriptArtifact artifact = artifactService.store(filename, content);

        ScriptLibraryTemplate template = new ScriptLibraryTemplate();
        applyMetadata(template, name, description, category, riskLevel, requiresReboot,
                requiresOperatorMode, powershellRequirement, successMarker, inverseTemplateId);
        template.setActive(true);
        template.setCurrentVersion(1);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);
        saveVersion(template.getId(), 1, artifact, now);
        return templateRow(template);
    }

    @Transactional
    public Map<String, Object> updateTemplate(Long id,
                                              String name,
                                              String description,
                                              String category,
                                              String riskLevel,
                                              boolean requiresReboot,
                                              boolean requiresOperatorMode,
                                              String powershellRequirement,
                                              String successMarker,
                                              Long inverseTemplateId,
                                              String filename,
                                              byte[] content) {
        ScriptLibraryTemplate template = activeTemplate(id);
        if (template.getBuiltInKey() != null) {
            throw new InvalidRequestException("기본 제공 스크립트는 수정할 수 없습니다.");
        }
        validateMetadata(name, description, category, riskLevel, powershellRequirement, successMarker);
        ScriptLibraryVersion current = currentVersion(template);
        ScriptArtifact artifact;
        if (content != null && content.length > 0) {
            validateScript(filename, content);
            artifact = artifactService.store(filename, content);
        } else {
            artifact = new ScriptArtifact();
            artifact.setId(current.getArtifactId());
            artifact.setSha256(current.getSha256());
        }
        int nextVersion = template.getCurrentVersion() + 1;
        applyMetadata(template, name, description, category, riskLevel, requiresReboot,
                requiresOperatorMode, powershellRequirement, successMarker, inverseTemplateId);
        template.setCurrentVersion(nextVersion);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
        saveVersion(template.getId(), nextVersion, artifact, template.getUpdatedAt());
        return templateRow(template);
    }

    @Transactional
    public Map<String, Object> deleteTemplate(Long id) {
        ScriptLibraryTemplate template = activeTemplate(id);
        if (template.getBuiltInKey() != null) {
            throw new InvalidRequestException("기본 제공 스크립트는 삭제할 수 없습니다.");
        }
        template.setActive(false);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
        return Map.of("id", id, "deleted", true);
    }

    @Transactional
    public Map<String, Object> execute(Long templateId, ScriptLibraryExecutionRequest request) {
        ScriptLibraryTemplate template = activeTemplate(templateId);
        return createExecution(template, currentVersion(template), request.targetMacs(),
                normalizeRunMode(request.runMode()), normalizeWindowMode(request.windowMode()),
                clean(request.arguments()), Boolean.TRUE.equals(request.acknowledged()), null);
    }

    @Transactional
    public Map<String, Object> rerun(Long executionId, ScriptLibraryRerunRequest request) {
        ScriptLibraryExecution source = execution(executionId);
        ScriptLibraryTemplate template = templateRepository.findById(source.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("스크립트를 찾을 수 없습니다."));
        ScriptLibraryVersion version = versionRepository.findById(source.getVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("실행 버전을 찾을 수 없습니다."));
        List<String> macs = request.targetMacs() == null || request.targetMacs().isEmpty()
                ? targetRepository.findByExecutionIdOrderByMacAddressAsc(executionId).stream()
                    .map(ScriptLibraryExecutionTarget::getMacAddress).toList()
                : request.targetMacs();
        return createExecution(template, version, macs,
                request.runMode() == null ? source.getRunMode() : normalizeRunMode(request.runMode()),
                request.windowMode() == null ? source.getWindowMode() : normalizeWindowMode(request.windowMode()),
                request.arguments() == null ? source.getArgumentsText() : clean(request.arguments()),
                Boolean.TRUE.equals(request.acknowledged()), executionId);
    }

    @Transactional
    public Map<String, Object> retryFailed(Long executionId, boolean acknowledged) {
        ScriptLibraryExecution source = execution(executionId);
        Map<Long, CommandQueue> commands = commandMap(executionId);
        ScriptLibraryTemplate template = templateRepository.findById(source.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("스크립트를 찾을 수 없습니다."));
        List<String> failed = targetRepository.findByExecutionIdOrderByMacAddressAsc(executionId).stream()
                .filter(target -> isFailedTarget(commands.get(target.getTaskId()), template.getSuccessMarker()))
                .map(ScriptLibraryExecutionTarget::getMacAddress)
                .toList();
        if (failed.isEmpty()) throw new InvalidRequestException("재실행할 실패 PC가 없습니다.");
        ScriptLibraryVersion version = versionRepository.findById(source.getVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("실행 버전을 찾을 수 없습니다."));
        return createExecution(template, version, failed, source.getRunMode(), source.getWindowMode(),
                source.getArgumentsText(), acknowledged, executionId);
    }

    @Transactional
    public List<Map<String, Object>> recentExecutions(int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        return executionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(this::executionRow)
                .toList();
    }

    @Transactional
    public Map<String, Object> getExecution(Long id) {
        return executionRow(execution(id));
    }

    @Transactional
    public ScriptLibraryTemplate ensureBuiltIn(String builtInKey,
                                               String name,
                                               String description,
                                               String category,
                                               String riskLevel,
                                               boolean requiresReboot,
                                               boolean requiresOperatorMode,
                                               String successMarker,
                                               String filename,
                                               byte[] content) {
        ScriptArtifact artifact = artifactService.store(filename, content);
        Optional<ScriptLibraryTemplate> existing = templateRepository.findByBuiltInKey(builtInKey);
        LocalDateTime now = LocalDateTime.now();
        if (existing.isEmpty()) {
            ScriptLibraryTemplate template = new ScriptLibraryTemplate();
            applyMetadata(template, name, description, category, riskLevel, requiresReboot,
                    requiresOperatorMode, "WINDOWS_POWERSHELL_5_1", successMarker, null);
            template.setBuiltInKey(builtInKey);
            template.setActive(true);
            template.setCurrentVersion(1);
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            template = templateRepository.save(template);
            saveVersion(template.getId(), 1, artifact, now);
            return template;
        }
        ScriptLibraryTemplate template = existing.get();
        ScriptLibraryVersion current = currentVersion(template);
        applyMetadata(template, name, description, category, riskLevel, requiresReboot,
                requiresOperatorMode, "WINDOWS_POWERSHELL_5_1", successMarker, template.getInverseTemplateId());
        template.setActive(true);
        if (!artifact.getSha256().equalsIgnoreCase(current.getSha256())) {
            int next = template.getCurrentVersion() + 1;
            template.setCurrentVersion(next);
            saveVersion(template.getId(), next, artifact, now);
        }
        template.setUpdatedAt(now);
        return templateRepository.save(template);
    }

    @Transactional
    public void linkInverse(Long leftId, Long rightId) {
        ScriptLibraryTemplate left = templateRepository.findById(leftId).orElseThrow();
        ScriptLibraryTemplate right = templateRepository.findById(rightId).orElseThrow();
        if (!Objects.equals(left.getInverseTemplateId(), rightId)) {
            left.setInverseTemplateId(rightId);
            left.setUpdatedAt(LocalDateTime.now());
            templateRepository.save(left);
        }
        if (!Objects.equals(right.getInverseTemplateId(), leftId)) {
            right.setInverseTemplateId(leftId);
            right.setUpdatedAt(LocalDateTime.now());
            templateRepository.save(right);
        }
    }

    private Map<String, Object> createExecution(ScriptLibraryTemplate template,
                                                ScriptLibraryVersion version,
                                                List<String> requestedMacs,
                                                String runMode,
                                                String windowMode,
                                                String arguments,
                                                boolean acknowledged,
                                                Long sourceExecutionId) {
        requireAcknowledgement(template, acknowledged);
        if ("POWERSHELL_7".equals(template.getPowershellRequirement())) {
            throw new InvalidRequestException("현재 RUN_PS1 엔진은 Windows PowerShell 5.1만 지원합니다.");
        }
        List<PcMaster> targets = resolveTargets(requestedMacs, Boolean.TRUE.equals(template.getRequiresOperatorMode()));
        LocalDateTime now = LocalDateTime.now();
        ScriptLibraryExecution execution = new ScriptLibraryExecution();
        execution.setTemplateId(template.getId());
        execution.setVersionId(version.getId());
        execution.setTemplateNameSnapshot(template.getName());
        execution.setVersionNo(version.getVersionNo());
        execution.setScriptSha256(version.getSha256());
        execution.setStatus("QUEUED");
        execution.setRunMode(runMode);
        execution.setWindowMode(windowMode);
        execution.setArgumentsText(arguments);
        execution.setTargetCount(targets.size());
        execution.setSourceExecutionId(sourceExecutionId);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        execution = executionRepository.save(execution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runMode", runMode);
        payload.put("windowMode", windowMode);
        payload.put("scriptLibraryExecutionId", execution.getId());
        payload.put("scriptLibraryVersionId", version.getId());
        if (arguments != null) payload.put("args", arguments);

        CommandRequestDto command = new CommandRequestDto();
        command.setScope("MULTI");
        command.setType("RUN_PS1");
        command.setTargetMacs(targets.stream().map(PcMaster::getMacAddress).toList());
        command.setScriptUrl("/api/v1/scripts/" + version.getArtifactId() + "/download");
        try {
            command.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("실행 정보를 직렬화하지 못했습니다.", ex);
        }
        Map<String, Object> queued = commandLifecycleService.enqueue(command);
        Map<String, Long> tasksByMac = extractTasks(queued);
        if (tasksByMac.size() != targets.size()) {
            throw new IllegalStateException("일부 대상의 RUN_PS1 명령을 생성하지 못했습니다.");
        }
        List<ScriptLibraryExecutionTarget> executionTargets = new ArrayList<>();
        for (PcMaster pc : targets) {
            ScriptLibraryExecutionTarget target = new ScriptLibraryExecutionTarget();
            target.setExecutionId(execution.getId());
            target.setMacAddress(pc.getMacAddress());
            target.setTaskId(tasksByMac.get(canonicalMac(pc.getMacAddress())));
            target.setCreatedAt(now);
            executionTargets.add(target);
        }
        targetRepository.saveAll(executionTargets);
        return executionRow(execution);
    }

    private Map<String, Long> extractTasks(Map<String, Object> response) {
        Map<String, Long> tasks = new LinkedHashMap<>();
        Object rows = response.get("tasks");
        if (!(rows instanceof List<?> list)) return tasks;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> row)) continue;
            Object mac = row.get("macAddress");
            Object taskId = row.get("taskId");
            if (mac != null && taskId instanceof Number number) {
                tasks.put(canonicalMac(mac.toString()), number.longValue());
            }
        }
        return tasks;
    }

    private List<PcMaster> resolveTargets(List<String> requestedMacs, boolean operatorRequired) {
        if (requestedMacs == null || requestedMacs.isEmpty()) {
            throw new InvalidRequestException("대상 PC를 하나 이상 선택하세요.");
        }
        LinkedHashSet<String> canonical = requestedMacs.stream().map(this::canonicalMac)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, PcMaster> found = pcRepository.findAll().stream()
                .collect(Collectors.toMap(pc -> canonicalMac(pc.getMacAddress()), Function.identity(), (a, b) -> a));
        List<String> missing = canonical.stream().filter(mac -> !found.containsKey(mac)).toList();
        if (!missing.isEmpty()) throw new InvalidRequestException("등록되지 않은 대상 PC가 있습니다: " + String.join(", ", missing));
        List<PcMaster> targets = canonical.stream().map(found::get).toList();
        List<String> unavailable = targets.stream()
                .filter(pc -> !"ONLINE".equalsIgnoreCase(pc.getStatus()) || pc.getBrokenNote() != null)
                .map(pc -> pc.getPcName() == null ? pc.getMacAddress() : pc.getPcName()).toList();
        if (!unavailable.isEmpty()) throw new InvalidRequestException("온라인 정상 PC만 실행할 수 있습니다: " + String.join(", ", unavailable));
        if (operatorRequired) {
            List<String> protectedPcs = targets.stream().filter(pc -> !"OPERATOR".equalsIgnoreCase(pc.getScmateMode()))
                    .map(pc -> pc.getPcName() == null ? pc.getMacAddress() : pc.getPcName()).toList();
            if (!protectedPcs.isEmpty()) throw new InvalidRequestException("운영자 모드가 필요한 PC가 있습니다: " + String.join(", ", protectedPcs));
        }
        return targets;
    }

    private Map<String, Object> templateRow(ScriptLibraryTemplate template) {
        ScriptLibraryVersion version = currentVersion(template);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", template.getId());
        row.put("name", template.getName());
        row.put("description", template.getDescription());
        row.put("category", template.getCategory());
        row.put("riskLevel", template.getRiskLevel());
        row.put("requiresReboot", template.getRequiresReboot());
        row.put("requiresOperatorMode", template.getRequiresOperatorMode());
        row.put("powershellRequirement", template.getPowershellRequirement());
        row.put("successMarker", template.getSuccessMarker());
        row.put("builtIn", template.getBuiltInKey() != null);
        row.put("builtInKey", template.getBuiltInKey());
        row.put("inverseTemplateId", template.getInverseTemplateId());
        row.put("currentVersion", template.getCurrentVersion());
        row.put("sha256", version.getSha256());
        row.put("downloadUrl", "/api/v1/scripts/" + version.getArtifactId() + "/download");
        row.put("createdAt", template.getCreatedAt());
        row.put("updatedAt", template.getUpdatedAt());
        return row;
    }

    private Map<String, Object> executionRow(ScriptLibraryExecution execution) {
        ScriptLibraryTemplate template = templateRepository.findById(execution.getTemplateId()).orElse(null);
        String successMarker = template == null ? null : template.getSuccessMarker();
        List<ScriptLibraryExecutionTarget> targets = targetRepository.findByExecutionIdOrderByMacAddressAsc(execution.getId());
        Map<Long, CommandQueue> commands = commandRepository.findAllById(targets.stream().map(ScriptLibraryExecutionTarget::getTaskId).toList())
                .stream().collect(Collectors.toMap(CommandQueue::getTaskId, Function.identity()));
        Map<String, PcMaster> pcs = pcRepository.findAllById(targets.stream().map(ScriptLibraryExecutionTarget::getMacAddress).toList())
                .stream().collect(Collectors.toMap(pc -> canonicalMac(pc.getMacAddress()), Function.identity(), (a, b) -> a));
        List<Map<String, Object>> targetRows = targets.stream().map(target -> {
            CommandQueue command = commands.get(target.getTaskId());
            PcMaster pc = pcs.get(canonicalMac(target.getMacAddress()));
            String status = effectiveTargetStatus(command, successMarker);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("macAddress", target.getMacAddress());
            row.put("pcName", pc == null ? null : pc.getPcName());
            row.put("groupId", pc == null ? null : pc.getGroupId());
            row.put("taskId", target.getTaskId());
            row.put("status", status);
            row.put("exitCode", command == null ? null : command.getExitCode());
            row.put("stdout", command == null ? null : command.getStdout());
            row.put("stderr", command == null ? "명령 이력을 찾을 수 없습니다." : command.getStderr());
            return row;
        }).toList();
        long completed = targetRows.stream().filter(row -> "COMPLETED".equals(row.get("status"))).count();
        long failed = targetRows.stream().filter(row -> Set.of("FAILED", "CANCELLED", "VERIFICATION_FAILED", "MISSING").contains(row.get("status"))).count();
        long active = targetRows.size() - completed - failed;
        String status = active > 0 ? "IN_PROGRESS" : failed == 0 ? "COMPLETED" : completed == 0 ? "FAILED" : "PARTIAL_FAILED";
        if (!status.equals(execution.getStatus())) {
            execution.setStatus(status);
            execution.setUpdatedAt(LocalDateTime.now());
            executionRepository.save(execution);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", execution.getId());
        row.put("templateId", execution.getTemplateId());
        row.put("templateName", execution.getTemplateNameSnapshot());
        row.put("version", execution.getVersionNo());
        row.put("sha256", execution.getScriptSha256());
        row.put("status", status);
        row.put("runMode", execution.getRunMode());
        row.put("windowMode", execution.getWindowMode());
        row.put("arguments", execution.getArgumentsText());
        row.put("targetCount", execution.getTargetCount());
        row.put("completedCount", completed);
        row.put("failedCount", failed);
        row.put("activeCount", active);
        row.put("sourceExecutionId", execution.getSourceExecutionId());
        row.put("createdAt", execution.getCreatedAt());
        row.put("updatedAt", execution.getUpdatedAt());
        row.put("targets", targetRows);
        return row;
    }

    private String effectiveTargetStatus(CommandQueue command, String successMarker) {
        if (command == null) return "MISSING";
        String status = upper(command.getStatus(), "QUEUED");
        if ("COMPLETED".equals(status) && successMarker != null && !successMarker.isBlank()
                && (command.getStdout() == null || !command.getStdout().contains(successMarker))) {
            return "VERIFICATION_FAILED";
        }
        return status;
    }

    private boolean isFailedTarget(CommandQueue command, String successMarker) {
        return Set.of("FAILED", "CANCELLED", "VERIFICATION_FAILED", "MISSING")
                .contains(effectiveTargetStatus(command, successMarker));
    }

    private Map<Long, CommandQueue> commandMap(Long executionId) {
        List<Long> ids = targetRepository.findByExecutionIdOrderByMacAddressAsc(executionId).stream()
                .map(ScriptLibraryExecutionTarget::getTaskId).toList();
        return commandRepository.findAllById(ids).stream().collect(Collectors.toMap(CommandQueue::getTaskId, Function.identity()));
    }

    private void requireAcknowledgement(ScriptLibraryTemplate template, boolean acknowledged) {
        if ((Set.of("HIGH", "CRITICAL").contains(template.getRiskLevel())
                || Boolean.TRUE.equals(template.getRequiresReboot())) && !acknowledged) {
            throw new InvalidRequestException("위험도와 재부팅 영향을 확인해야 실행할 수 있습니다.");
        }
    }

    private ScriptLibraryTemplate activeTemplate(Long id) {
        ScriptLibraryTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("스크립트를 찾을 수 없습니다."));
        if (!Boolean.TRUE.equals(template.getActive())) throw new ResourceNotFoundException("삭제된 스크립트입니다.");
        return template;
    }

    private ScriptLibraryExecution execution(Long id) {
        return executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("스크립트 실행 이력을 찾을 수 없습니다."));
    }

    private ScriptLibraryVersion currentVersion(ScriptLibraryTemplate template) {
        return versionRepository.findByTemplateIdAndVersionNo(template.getId(), template.getCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("현재 스크립트 버전을 찾을 수 없습니다."));
    }

    private void saveVersion(Long templateId, int version, ScriptArtifact artifact, LocalDateTime createdAt) {
        ScriptLibraryVersion row = new ScriptLibraryVersion();
        row.setTemplateId(templateId);
        row.setVersionNo(version);
        row.setArtifactId(artifact.getId());
        row.setSha256(artifact.getSha256());
        row.setCreatedAt(createdAt);
        versionRepository.save(row);
    }

    private void applyMetadata(ScriptLibraryTemplate template,
                               String name,
                               String description,
                               String category,
                               String riskLevel,
                               boolean requiresReboot,
                               boolean requiresOperatorMode,
                               String powershellRequirement,
                               String successMarker,
                               Long inverseTemplateId) {
        template.setName(name.trim());
        template.setDescription(clean(description));
        template.setCategory(category.trim());
        template.setRiskLevel(upper(riskLevel, "MEDIUM"));
        template.setRequiresReboot(requiresReboot);
        template.setRequiresOperatorMode(requiresOperatorMode);
        template.setPowershellRequirement(upper(powershellRequirement, "WINDOWS_POWERSHELL_5_1"));
        template.setSuccessMarker(clean(successMarker));
        template.setInverseTemplateId(inverseTemplateId);
    }

    private void validateMetadata(String name, String description, String category, String riskLevel,
                                  String powershellRequirement, String successMarker) {
        if (name == null || name.isBlank() || name.trim().length() > 120) throw new InvalidRequestException("스크립트 이름은 1~120자여야 합니다.");
        if (description != null && description.length() > 2000) throw new InvalidRequestException("설명은 2000자를 초과할 수 없습니다.");
        if (category == null || category.isBlank() || category.trim().length() > 64) throw new InvalidRequestException("분류는 1~64자여야 합니다.");
        if (!RISKS.contains(upper(riskLevel, ""))) throw new InvalidRequestException("위험도 값이 올바르지 않습니다.");
        if (!PS_REQUIREMENTS.contains(upper(powershellRequirement, ""))) throw new InvalidRequestException("요구 PowerShell 값이 올바르지 않습니다.");
        if (successMarker != null && successMarker.length() > 300) throw new InvalidRequestException("성공 확인 문구는 300자를 초과할 수 없습니다.");
    }

    private void validateScript(String filename, byte[] content) {
        if (content == null || content.length == 0) throw new InvalidRequestException("PS1 파일이 필요합니다.");
        if (content.length > MAX_SCRIPT_BYTES) throw new InvalidRequestException("PS1 파일은 1MB를 초과할 수 없습니다.");
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".ps1")) throw new InvalidRequestException(".ps1 파일만 등록할 수 있습니다.");
        String text = new String(content, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        if (text.isEmpty() || text.indexOf('\0') >= 0) throw new InvalidRequestException("텍스트 PowerShell 스크립트만 등록할 수 있습니다.");
    }

    private String normalizeRunMode(String value) {
        return "BACKGROUND".equalsIgnoreCase(value) ? "BACKGROUND" : "FOREGROUND";
    }

    private String normalizeWindowMode(String value) {
        return "VISIBLE".equalsIgnoreCase(value) ? "VISIBLE" : "HIDDEN";
    }

    private String canonicalMac(String value) {
        return value == null ? "" : value.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    private String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
