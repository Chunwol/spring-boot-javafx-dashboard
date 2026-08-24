package com.example.backend.service.filedist;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.dto.filedist.PeerTransferCreateRequest;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.filedist.FileDistributionAgentStatus;
import com.example.backend.model.filedist.FileDistributionEvent;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.filedist.FileDistributionAgentStatusRepository;
import com.example.backend.repository.filedist.FileDistributionEventRepository;
import com.example.backend.repository.filedist.FileDistributionJobRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.command.CommandLifecycleService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class PeerTransferService {

    public static final String PACKAGE_TYPE = "PC_COPY";
    public static final String SOURCE_MODE = "PC_DIRECT";
    private static final List<String> ACTIVE_JOB_STATUSES = List.of("QUEUED", "IN_PROGRESS");
    private static final Set<String> TERMINAL_COMMAND_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:\\\\(?:.*)?$");
    private static final Pattern PARENT_SEGMENT = Pattern.compile("(?:^|\\\\)\\.\\.(?:\\\\|$)");

    private final FileDistributionJobRepository jobRepository;
    private final FileDistributionAgentStatusRepository targetRepository;
    private final FileDistributionEventRepository eventRepository;
    private final PcMasterRepository pcRepository;
    private final CommandQueueRepository commandRepository;
    private final CommandLifecycleService commandLifecycleService;
    private final PeerTransferScriptArtifactService scriptArtifactService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final Set<Long> runningJobs = ConcurrentHashMap.newKeySet();

    @Value("${app.peer-transfer.poll-interval-seconds:30}")
    private long pollIntervalSeconds;

    @Value("${app.peer-transfer.command-timeout-minutes:10}")
    private long commandTimeoutMinutes;

    @Value("${app.peer-transfer.job-timeout-hours:24}")
    private long jobTimeoutHours;

    public PeerTransferService(FileDistributionJobRepository jobRepository,
                               FileDistributionAgentStatusRepository targetRepository,
                               FileDistributionEventRepository eventRepository,
                               PcMasterRepository pcRepository,
                               CommandQueueRepository commandRepository,
                               CommandLifecycleService commandLifecycleService,
                               PeerTransferScriptArtifactService scriptArtifactService,
                               @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                               ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.targetRepository = targetRepository;
        this.eventRepository = eventRepository;
        this.pcRepository = pcRepository;
        this.commandRepository = commandRepository;
        this.commandLifecycleService = commandLifecycleService;
        this.scriptArtifactService = scriptArtifactService;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FileDistributionJob create(PeerTransferCreateRequest request) {
        String sourceMac = canonicalMac(request.sourceMac());
        String sourcePath = validatePath(request.sourcePath(), "sourcePath");
        String targetPath = validatePath(request.targetPath(), "targetPath");
        List<String> targetMacs = normalizeTargetMacs(request.targetMacs(), sourceMac);
        Map<String, PcMaster> pcs = findPcs(concat(sourceMac, targetMacs));
        PcMaster source = requireOnlinePc(pcs.get(sourceMac), "source PC", sourceMac);
        for (String targetMac : targetMacs) {
            requireOnlinePc(pcs.get(targetMac), "target PC", targetMac);
        }
        if (source.getIpAddress() == null || source.getIpAddress().isBlank()) {
            throw new InvalidRequestException("source PC IP address is unavailable");
        }
        if (jobRepository.existsBySourceAgentIdIgnoreCaseAndStatusIn(sourceMac, ACTIVE_JOB_STATUSES)) {
            throw new ConflictException("source PC already has an active peer transfer");
        }

        FileDistributionJob job = new FileDistributionJob();
        job.setUploadId("peer-" + UUID.randomUUID());
        job.setOriginalFilename(lastPathSegment(sourcePath));
        job.setTotalChunks(1);
        job.setStatus("QUEUED");
        job.setCreatedAt(LocalDateTime.now());
        job.setSourceMode(SOURCE_MODE);
        job.setUpdatePackageType(PACKAGE_TYPE);
        job.setRampUpFactor(1.0);
        job.setSourceAgentId(sourceMac);
        job.setSourcePath(sourcePath);
        job.setTargetPath(targetPath);
        job.setOverwriteExisting(request.overwriteExisting());
        job.setPeerPort(18_100 + Math.floorMod(UUID.randomUUID().hashCode(), 700));
        FileDistributionJob saved = jobRepository.save(job);

        List<FileDistributionAgentStatus> targets = new ArrayList<>();
        for (String targetMac : targetMacs) {
            FileDistributionAgentStatus target = new FileDistributionAgentStatus();
            target.setJob(saved);
            target.setAgentId(targetMac);
            target.setWave(0);
            target.setStatus("QUEUED");
            target.setVerifiedFiles(0L);
            target.setVerifiedBytes(0L);
            targets.add(target);
        }
        targetRepository.saveAll(targets);
        addEvent(saved, "PEER_TRANSFER_CREATED", Map.of(
                "sourceMac", sourceMac,
                "sourcePath", sourcePath,
                "targetPath", targetPath,
                "targetCount", targetMacs.size(),
                "overwriteExisting", request.overwriteExisting()
        ));
        scheduleAfterCommit(saved.getId());
        return saved;
    }

    @Transactional
    public Map<String, Object> cancel(Long jobId) {
        FileDistributionJob job = requirePeerJob(jobId);
        if (isTerminalJob(job.getStatus())) {
            return Map.of("jobId", jobId, "status", job.getStatus());
        }
        job.setStatus("CANCELLED");
        job.setCancelledAt(LocalDateTime.now());
        jobRepository.save(job);
        List<FileDistributionAgentStatus> targets = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId);
        for (FileDistributionAgentStatus target : targets) {
            if (!"COMPLETED".equals(target.getStatus())) {
                target.setStatus("CANCELLED");
                target.setCompletedAt(LocalDateTime.now());
            }
        }
        targetRepository.saveAll(targets);
        addEvent(job, "PEER_TRANSFER_CANCELLED", Map.of("targetCount", targets.size()));
        taskExecutor.execute(() -> stopRemoteWorkers(jobId));
        return Map.of("jobId", jobId, "status", "CANCELLED");
    }

    @Transactional
    public Map<String, Object> retryFailed(Long jobId) {
        FileDistributionJob job = requirePeerJob(jobId);
        if (!isTerminalJob(job.getStatus()) || "CANCELLED".equals(job.getStatus())) {
            throw new ConflictException("peer transfer must be failed before retry");
        }
        List<FileDistributionAgentStatus> failed = targetRepository.findByJobIdAndStatusOrderByWaveAscIdAsc(jobId, "FAILED");
        if (failed.isEmpty()) {
            return Map.of("jobId", jobId, "resetCount", 0, "status", job.getStatus());
        }
        for (FileDistributionAgentStatus target : failed) {
            target.setStatus("QUEUED");
            target.setStartedAt(null);
            target.setCompletedAt(null);
            target.setErrorMessage(null);
            target.setLauncherTaskId(null);
            target.setAuditTaskId(null);
        }
        targetRepository.saveAll(failed);
        job.setStatus("QUEUED");
        job.setCompletedAt(null);
        job.setErrorMessage(null);
        jobRepository.save(job);
        addEvent(job, "PEER_TRANSFER_RETRIED", Map.of("resetCount", failed.size()));
        scheduleAfterCommit(jobId);
        return Map.of("jobId", jobId, "resetCount", failed.size(), "status", "QUEUED");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedJobs() {
        for (FileDistributionJob job : jobRepository.findByUpdatePackageTypeAndStatusIn(PACKAGE_TYPE, ACTIVE_JOB_STATUSES)) {
            schedule(job.getId());
        }
    }

    private void scheduleAfterCommit(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(jobId);
                }
            });
        } else {
            schedule(jobId);
        }
    }

    private void schedule(Long jobId) {
        taskExecutor.execute(() -> run(jobId));
    }

    private void run(Long jobId) {
        if (!runningJobs.add(jobId)) {
            return;
        }
        try {
            FileDistributionJob job = requirePeerJob(jobId);
            if (isTerminalJob(job.getStatus())) {
                return;
            }
            job.setStatus("IN_PROGRESS");
            jobRepository.save(job);
            addEvent(job, "PEER_TRANSFER_STARTED", Map.of("port", job.getPeerPort()));

            PcMaster source = requireOnlinePc(findPc(job.getSourceAgentId()), "source PC", job.getSourceAgentId());
            List<FileDistributionAgentStatus> allTargets = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId);
            List<FileDistributionAgentStatus> pendingTargets = allTargets.stream()
                    .filter(target -> !"COMPLETED".equals(target.getStatus()))
                    .toList();
            Map<String, PcMaster> targetPcs = findPcs(pendingTargets.stream().map(FileDistributionAgentStatus::getAgentId).toList());
            for (FileDistributionAgentStatus target : pendingTargets) {
                requireOnlinePc(targetPcs.get(canonicalMac(target.getAgentId())), "target PC", target.getAgentId());
            }

            String token = job.getUploadId().replace("peer-", "").replace("-", "");
            String targetIps = pendingTargets.stream()
                    .map(target -> targetPcs.get(canonicalMac(target.getAgentId())))
                    .map(PcMaster::getIpAddress)
                    .filter(Objects::nonNull)
                    .distinct()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            Map<String, String> common = new LinkedHashMap<>();
            common.put("JOB_ID", String.valueOf(jobId));
            common.put("PORT", String.valueOf(job.getPeerPort()));
            common.put("TOKEN", token);
            common.put("SOURCE_PATH_B64", base64(job.getSourcePath()));
            common.put("TARGET_PATH_B64", base64(job.getTargetPath()));
            common.put("TARGET_IPS_CSV", targetIps);
            common.put("SOURCE_IP", source.getIpAddress());
            common.put("OVERWRITE", Boolean.toString(Boolean.TRUE.equals(job.getOverwriteExisting())));

            ScriptArtifact sourceLaunch = scriptArtifactService.create("source-launch.ps1", "peer-source-" + jobId + ".ps1", common);
            ScriptArtifact targetLaunch = scriptArtifactService.create("target-launch.ps1", "peer-target-" + jobId + ".ps1", common);
            ScriptArtifact targetAudit = scriptArtifactService.create("target-audit.ps1", "peer-audit-" + jobId + ".ps1", common);

            Long sourceTaskId = enqueueSingle(source.getMacAddress(), sourceLaunch);
            CommandQueue sourceResult = waitForCommands(List.of(sourceTaskId), commandTimeout()).getFirst();
            if (!commandSucceeded(sourceResult)) {
                throw new IllegalStateException("source launcher failed: " + commandError(sourceResult));
            }

            if (!pendingTargets.isEmpty()) {
                List<Long> launcherTaskIds = enqueueMulti(pendingTargets.stream().map(FileDistributionAgentStatus::getAgentId).toList(), targetLaunch);
                Map<String, CommandQueue> launcherCommands = byMac(waitForCommands(launcherTaskIds, commandTimeout()));
                for (FileDistributionAgentStatus target : pendingTargets) {
                    CommandQueue command = launcherCommands.get(canonicalMac(target.getAgentId()));
                    target.setLauncherTaskId(command == null ? null : command.getTaskId());
                    target.setStartedAt(LocalDateTime.now());
                    if (commandSucceeded(command)) {
                        target.setStatus("IN_PROGRESS");
                        target.setErrorMessage(null);
                    } else {
                        target.setStatus("FAILED");
                        target.setCompletedAt(LocalDateTime.now());
                        target.setErrorMessage(commandError(command));
                    }
                }
                targetRepository.saveAll(pendingTargets);
            }

            LocalDateTime deadline = LocalDateTime.now().plusHours(Math.max(1, jobTimeoutHours));
            while (LocalDateTime.now().isBefore(deadline)) {
                job = requirePeerJob(jobId);
                if ("CANCELLED".equals(job.getStatus())) {
                    return;
                }
                List<FileDistributionAgentStatus> active = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId).stream()
                        .filter(target -> "QUEUED".equals(target.getStatus()) || "IN_PROGRESS".equals(target.getStatus()))
                        .toList();
                if (active.isEmpty()) {
                    finishJob(jobId);
                    return;
                }
                List<Long> auditTaskIds = enqueueMulti(active.stream().map(FileDistributionAgentStatus::getAgentId).toList(), targetAudit);
                Map<String, CommandQueue> auditCommands = byMac(waitForCommands(auditTaskIds, commandTimeout()));
                for (FileDistributionAgentStatus target : active) {
                    CommandQueue command = auditCommands.get(canonicalMac(target.getAgentId()));
                    target.setAuditTaskId(command == null ? null : command.getTaskId());
                    if (!commandSucceeded(command)) {
                        target.setStatus("FAILED");
                        target.setCompletedAt(LocalDateTime.now());
                        target.setErrorMessage(commandError(command));
                    } else {
                        Map<String, Object> detail = parseJsonLine(command.getStdout());
                        String state = String.valueOf(detail.getOrDefault("state", "missing")).toLowerCase(Locale.ROOT);
                        target.setVerifiedFiles(longValue(detail.get("verifiedFiles")));
                        target.setVerifiedBytes(longValue(detail.get("verifiedBytes")));
                        if ("completed".equals(state)) {
                            target.setStatus("COMPLETED");
                            target.setCompletedAt(LocalDateTime.now());
                            target.setErrorMessage(null);
                        } else if ("failed".equals(state)) {
                            target.setStatus("FAILED");
                            target.setCompletedAt(LocalDateTime.now());
                            target.setErrorMessage(String.valueOf(detail.getOrDefault("error", "target worker failed")));
                        } else {
                            target.setStatus("IN_PROGRESS");
                        }
                    }
                }
                targetRepository.saveAll(active);
                addProgressEvent(jobId);
                sleep(Duration.ofSeconds(Math.max(5, pollIntervalSeconds)));
            }
            throw new IllegalStateException("peer transfer timed out");
        } catch (Exception ex) {
            failJob(jobId, ex.getMessage());
        } finally {
            cleanupSource(jobId);
            runningJobs.remove(jobId);
        }
    }

    private void finishJob(Long jobId) {
        FileDistributionJob job = requirePeerJob(jobId);
        List<FileDistributionAgentStatus> targets = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId);
        long failed = targets.stream().filter(target -> "FAILED".equals(target.getStatus())).count();
        job.setStatus(failed == 0 ? "COMPLETED" : "FAILED");
        job.setCompletedAt(LocalDateTime.now());
        job.setErrorMessage(failed == 0 ? null : failed + " target(s) failed");
        jobRepository.save(job);
        addEvent(job, failed == 0 ? "PEER_TRANSFER_COMPLETED" : "PEER_TRANSFER_FAILED", Map.of(
                "completed", targets.size() - failed,
                "failed", failed,
                "verifiedBytes", targets.stream().map(FileDistributionAgentStatus::getVerifiedBytes).filter(Objects::nonNull).mapToLong(Long::longValue).sum()
        ));
    }

    private void failJob(Long jobId, String message) {
        FileDistributionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || "CANCELLED".equals(job.getStatus())) {
            return;
        }
        List<FileDistributionAgentStatus> targets = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId);
        for (FileDistributionAgentStatus target : targets) {
            if (!"COMPLETED".equals(target.getStatus()) && !"FAILED".equals(target.getStatus())) {
                target.setStatus("FAILED");
                target.setCompletedAt(LocalDateTime.now());
                target.setErrorMessage(message == null ? "peer transfer failed" : message);
            }
        }
        targetRepository.saveAll(targets);
        job.setStatus("FAILED");
        job.setCompletedAt(LocalDateTime.now());
        job.setErrorMessage(message == null ? "peer transfer failed" : message);
        jobRepository.save(job);
        addEvent(job, "PEER_TRANSFER_FAILED", Map.of("error", job.getErrorMessage()));
    }

    private void addProgressEvent(Long jobId) {
        FileDistributionJob job = requirePeerJob(jobId);
        List<FileDistributionAgentStatus> targets = targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId);
        addEvent(job, "PEER_TRANSFER_PROGRESS", Map.of(
                "completed", targets.stream().filter(target -> "COMPLETED".equals(target.getStatus())).count(),
                "failed", targets.stream().filter(target -> "FAILED".equals(target.getStatus())).count(),
                "inProgress", targets.stream().filter(target -> "IN_PROGRESS".equals(target.getStatus())).count(),
                "verifiedBytes", targets.stream().map(FileDistributionAgentStatus::getVerifiedBytes).filter(Objects::nonNull).mapToLong(Long::longValue).sum()
        ));
    }

    private void stopRemoteWorkers(Long jobId) {
        FileDistributionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        Map<String, String> values = basicReplacements(job);
        try {
            ScriptArtifact cancel = scriptArtifactService.create("target-cancel.ps1", "peer-cancel-" + jobId + ".ps1", values);
            enqueueMulti(targetRepository.findByJobIdOrderByWaveAscIdAsc(jobId).stream().map(FileDistributionAgentStatus::getAgentId).toList(), cancel);
        } catch (Exception ignored) {
        }
        cleanupSource(jobId);
    }

    private void cleanupSource(Long jobId) {
        FileDistributionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getSourceAgentId() == null) {
            return;
        }
        try {
            ScriptArtifact cleanup = scriptArtifactService.create("source-cleanup.ps1", "peer-cleanup-" + jobId + ".ps1", basicReplacements(job));
            enqueueSingle(job.getSourceAgentId(), cleanup);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> basicReplacements(FileDistributionJob job) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("JOB_ID", String.valueOf(job.getId()));
        values.put("PORT", String.valueOf(job.getPeerPort() == null ? 18100 : job.getPeerPort()));
        values.put("TOKEN", job.getUploadId() == null ? "missing" : job.getUploadId().replace("peer-", "").replace("-", ""));
        values.put("SOURCE_PATH_B64", base64(job.getSourcePath()));
        values.put("TARGET_PATH_B64", base64(job.getTargetPath()));
        values.put("TARGET_IPS_CSV", "");
        values.put("SOURCE_IP", findPc(job.getSourceAgentId()) == null ? "127.0.0.1" : findPc(job.getSourceAgentId()).getIpAddress());
        values.put("OVERWRITE", Boolean.toString(Boolean.TRUE.equals(job.getOverwriteExisting())));
        return values;
    }

    private Long enqueueSingle(String macAddress, ScriptArtifact artifact) {
        CommandRequestDto request = commandRequest("SINGLE", List.of(), macAddress, artifact);
        Map<String, Object> response = commandLifecycleService.enqueue(request);
        List<Long> taskIds = taskIds(response);
        if (taskIds.size() != 1) {
            throw new IllegalStateException("command was not queued for " + macAddress);
        }
        return taskIds.getFirst();
    }

    private List<Long> enqueueMulti(List<String> macAddresses, ScriptArtifact artifact) {
        if (macAddresses.isEmpty()) {
            return List.of();
        }
        CommandRequestDto request = commandRequest("MULTI", macAddresses, null, artifact);
        Map<String, Object> response = commandLifecycleService.enqueue(request);
        List<Long> taskIds = taskIds(response);
        if (taskIds.size() != new LinkedHashSet<>(macAddresses).size()) {
            throw new IllegalStateException("commands queued for " + taskIds.size() + "/" + macAddresses.size() + " targets");
        }
        return taskIds;
    }

    private CommandRequestDto commandRequest(String scope,
                                             List<String> targetMacs,
                                             String macAddress,
                                             ScriptArtifact artifact) {
        CommandRequestDto request = new CommandRequestDto();
        request.setScope(scope);
        request.setRole("ALL");
        request.setType("RUN_PS1");
        request.setMacAddress(macAddress);
        request.setTargetMacs(targetMacs);
        request.setScriptUrl("/api/v1/scripts/" + artifact.getId() + "/download");
        request.setPayloadJson("{\"runMode\":\"FOREGROUND\",\"windowMode\":\"HIDDEN\"}");
        request.setIsAdminOperation(true);
        return request;
    }

    private List<CommandQueue> waitForCommands(List<Long> taskIds, Duration timeout) {
        LocalDateTime deadline = LocalDateTime.now().plus(timeout);
        while (LocalDateTime.now().isBefore(deadline)) {
            List<CommandQueue> commands = commandRepository.findAllById(taskIds);
            if (commands.size() == taskIds.size() && commands.stream().allMatch(command -> TERMINAL_COMMAND_STATUSES.contains(command.getStatus()))) {
                return commands;
            }
            sleep(Duration.ofSeconds(2));
        }
        return commandRepository.findAllById(taskIds);
    }

    private Map<String, CommandQueue> byMac(Collection<CommandQueue> commands) {
        Map<String, CommandQueue> result = new LinkedHashMap<>();
        for (CommandQueue command : commands) {
            result.put(canonicalMac(command.getMacAddress()), command);
        }
        return result;
    }

    private boolean commandSucceeded(CommandQueue command) {
        return command != null && "COMPLETED".equals(command.getStatus()) && Integer.valueOf(0).equals(command.getExitCode());
    }

    private String commandError(CommandQueue command) {
        if (command == null) {
            return "command result missing";
        }
        if (command.getStderr() != null && !command.getStderr().isBlank()) {
            return command.getStderr();
        }
        return "command status=" + command.getStatus() + ", exitCode=" + command.getExitCode();
    }

    private Map<String, Object> parseJsonLine(String stdout) {
        if (stdout == null) {
            return Map.of();
        }
        String[] lines = stdout.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) {
                continue;
            }
            try {
                return objectMapper.readValue(line, new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private List<Long> taskIds(Map<String, Object> response) {
        Object raw = response.get("taskIds");
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().map(value -> Long.parseLong(String.valueOf(value))).toList();
    }

    private FileDistributionJob requirePeerJob(Long jobId) {
        FileDistributionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("peer transfer job not found: " + jobId));
        if (!PACKAGE_TYPE.equalsIgnoreCase(job.getUpdatePackageType())) {
            throw new InvalidRequestException("job is not a peer transfer: " + jobId);
        }
        return job;
    }

    private PcMaster requireOnlinePc(PcMaster pc, String label, String macAddress) {
        if (pc == null) {
            throw new ResourceNotFoundException(label + " not found: " + macAddress);
        }
        if (!"ONLINE".equalsIgnoreCase(pc.getStatus())) {
            throw new ConflictException(label + " is offline: " + macAddress);
        }
        if (pc.getBrokenNote() != null && !pc.getBrokenNote().isBlank()) {
            throw new ConflictException(label + " is marked broken: " + macAddress);
        }
        if (!isIpv4(pc.getIpAddress())) {
            throw new InvalidRequestException(label + " has no valid IPv4 address: " + macAddress);
        }
        return pc;
    }

    private PcMaster findPc(String macAddress) {
        return findPcs(List.of(macAddress)).get(canonicalMac(macAddress));
    }

    private Map<String, PcMaster> findPcs(Collection<String> macAddresses) {
        Set<String> wanted = macAddresses.stream().filter(Objects::nonNull).map(this::canonicalMac).collect(java.util.stream.Collectors.toSet());
        Map<String, PcMaster> result = new LinkedHashMap<>();
        for (PcMaster pc : pcRepository.findAll()) {
            String mac = canonicalMac(pc.getMacAddress());
            if (wanted.contains(mac)) {
                result.put(mac, pc);
            }
        }
        return result;
    }

    private List<String> normalizeTargetMacs(List<String> rawTargetMacs, String sourceMac) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTargetMacs) {
            String mac = canonicalMac(raw);
            if (mac.equals(sourceMac)) {
                throw new InvalidRequestException("source PC cannot also be a target PC");
            }
            normalized.add(mac);
        }
        if (normalized.isEmpty()) {
            throw new InvalidRequestException("at least one target PC is required");
        }
        return List.copyOf(normalized);
    }

    private String validatePath(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (!WINDOWS_ABSOLUTE_PATH.matcher(value).matches() || PARENT_SEGMENT.matcher(value).find()) {
            throw new InvalidRequestException(field + " must be an absolute Windows path without '..'");
        }
        if (value.startsWith("\\\\") || value.startsWith("\\\\?\\") || value.startsWith("\\\\.\\")) {
            throw new InvalidRequestException(field + " cannot be a UNC or device path");
        }
        if (value.substring(2).contains(":")) {
            throw new InvalidRequestException(field + " contains an alternate data stream");
        }
        if (value.matches("^[A-Za-z]:\\\\$")) {
            throw new InvalidRequestException(field + " cannot be a drive root");
        }
        return value;
    }

    private String canonicalMac(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }

    private boolean isIpv4(String raw) {
        if (raw == null) {
            return false;
        }
        String[] parts = raw.trim().split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255 || !String.valueOf(value).equals(part)) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private List<String> concat(String source, List<String> targets) {
        List<String> values = new ArrayList<>();
        values.add(source);
        values.addAll(targets);
        return values;
    }

    private String lastPathSegment(String path) {
        String normalized = path.replace('/', '\\').replaceAll("\\\\+$", "");
        int index = normalized.lastIndexOf('\\');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private Duration commandTimeout() {
        return Duration.ofMinutes(Math.max(1, commandTimeoutMinutes));
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("peer transfer interrupted", ex);
        }
    }

    private boolean isTerminalJob(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private void addEvent(FileDistributionJob job, String type, Map<String, ?> detail) {
        try {
            FileDistributionEvent event = new FileDistributionEvent();
            event.setJob(job);
            event.setEventType(type);
            event.setDetailJson(objectMapper.writeValueAsString(detail));
            event.setCreatedAt(LocalDateTime.now());
            eventRepository.save(event);
        } catch (Exception ignored) {
        }
    }
}
