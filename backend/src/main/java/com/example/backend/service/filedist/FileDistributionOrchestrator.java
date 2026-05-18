package com.example.backend.service.filedist;

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
import com.example.backend.repository.script.ScriptArtifactRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 대용량 파일/에이전트 업데이트 Wave 오케스트레이션 서비스.
 * - Wave별 점진 확장(ramp-up)
 * - Wave 시작 시 AGENT_UPDATE 명령 자동 큐잉
 * - AGENT_UPDATE 결과를 Job 상태에 자동 반영
 * - 실패 임계치 초과 시 중단, 안정 버전 롤백 Job 생성 지원
 */
@Service
public class FileDistributionOrchestrator {
    private static final String SOURCE_MODE_SERVER_ONLY = "SERVER_ONLY";
    private static final String SOURCE_MODE_HYBRID_PEER = "HYBRID_PEER";

    private final FileDistributionJobRepository jobRepo;
    private final FileDistributionAgentStatusRepository agentStatusRepo;
    private final FileDistributionEventRepository eventRepo;
    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final ScriptArtifactRepository scriptArtifactRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.file-distribution.fail-threshold:0.20}")
    private double failThreshold;

    @Value("${app.file-distribution.default-wave-size:20}")
    private int defaultWaveSize;

    @Value("${app.file-distribution.max-wave-size:100}")
    private int maxWaveSize;

    @Value("${app.file-distribution.default-ramp-up-factor:1.8}")
    private double defaultRampUpFactor;

    @Value("${app.file-distribution.peer-port:18777}")
    private int peerPort;

    @Value("${app.file-distribution.max-peer-candidates:20}")
    private int maxPeerCandidates;

    @Value("${app.file-distribution.peer-shared-secret:change-me-peer-shared-secret}")
    private String peerSharedSecret;

    @Value("${app.file-distribution.auto-rollback-enabled:true}")
    private boolean autoRollbackEnabled;

    @Value("${app.file-distribution.auto-catchup-on-heartbeat:true}")
    private boolean autoCatchupOnHeartbeat;

    @Value("${app.file-distribution.auto-catchup-failure-cooldown-seconds:300}")
    private long autoCatchupFailureCooldownSeconds;

    public FileDistributionOrchestrator(FileDistributionJobRepository jobRepo,
                                        FileDistributionAgentStatusRepository agentStatusRepo,
                                        FileDistributionEventRepository eventRepo,
                                        PcMasterRepository pcMasterRepository,
                                        CommandQueueRepository commandQueueRepository,
                                        ScriptArtifactRepository scriptArtifactRepository,
                                        ObjectMapper objectMapper) {
        this.jobRepo = jobRepo;
        this.agentStatusRepo = agentStatusRepo;
        this.eventRepo = eventRepo;
        this.pcMasterRepository = pcMasterRepository;
        this.commandQueueRepository = commandQueueRepository;
        this.scriptArtifactRepository = scriptArtifactRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FileDistributionJob createJob(String uploadId,
                                         String originalFilename,
                                         int totalChunks,
                                         int waveSize,
                                         boolean canaryOnly,
                                         Long groupId) {
        return createJob(uploadId, originalFilename, totalChunks, waveSize, canaryOnly, groupId,
                null, null, null, null, null);
    }

    @Transactional
    public FileDistributionJob createJob(String uploadId,
                                         String originalFilename,
                                         int totalChunks,
                                         int waveSize,
                                         boolean canaryOnly,
                                         Long groupId,
                                         Long artifactId,
                                         String targetVersion,
                                         String sourceMode,
                                         Double rampUpFactor) {
        return createJob(uploadId, originalFilename, totalChunks, waveSize, canaryOnly, groupId,
                artifactId, targetVersion, sourceMode, rampUpFactor, null);
    }

    @Transactional
    public FileDistributionJob createJob(String uploadId,
                                         String originalFilename,
                                         int totalChunks,
                                         int waveSize,
                                         boolean canaryOnly,
                                         Long groupId,
                                         Long artifactId,
                                         String targetVersion,
                                         String sourceMode,
                                         Double rampUpFactor,
                                         List<String> explicitTargetAgentIds) {
        FileDistributionJob job = new FileDistributionJob();
        job.setUploadId(uploadId);
        job.setOriginalFilename(originalFilename);
        job.setTotalChunks(totalChunks);
        job.setStatus("QUEUED");
        job.setCreatedAt(LocalDateTime.now());
        job.setArtifactId(artifactId);
        job.setTargetVersion(normalizeTargetVersion(targetVersion));
        job.setSourceMode(normalizeSourceMode(sourceMode));
        job.setRampUpFactor(resolveRampUpFactor(rampUpFactor));

        FileDistributionJob saved = jobRepo.save(job);

        orchestrateWaves(saved.getId(), waveSize, canaryOnly, groupId, explicitTargetAgentIds);
        Map<String, Object> createdEvent = new LinkedHashMap<>();
        createdEvent.put("waveSize", waveSize);
        createdEvent.put("canaryOnly", canaryOnly);
        createdEvent.put("groupId", groupId);
        createdEvent.put("explicitTargetCount", explicitTargetAgentIds == null ? 0 : explicitTargetAgentIds.size());
        createdEvent.put("artifactId", artifactId);
        createdEvent.put("targetVersion", saved.getTargetVersion());
        createdEvent.put("sourceMode", saved.getSourceMode());
        createdEvent.put("rampUpFactor", saved.getRampUpFactor());
        addEvent(saved, "CREATED", toJson(createdEvent));
        return saved;
    }

    /**
     * 배포 Job을 Wave 단위로 오케스트레이션 (초기 Canary → 전체)
     */
    @Transactional
    public void orchestrateWaves(Long jobId, int waveSize, boolean canaryOnly, Long groupId) {
        orchestrateWaves(jobId, waveSize, canaryOnly, groupId, null);
    }

    @Transactional
    public void orchestrateWaves(Long jobId,
                                 int waveSize,
                                 boolean canaryOnly,
                                 Long groupId,
                                 List<String> explicitTargetAgentIds) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();

        int initialWaveSize = normalizeWaveSize(waveSize);
        double rampFactor = resolveRampUpFactor(job.getRampUpFactor());

        List<String> allAgentIds = resolveTargetAgentIds(explicitTargetAgentIds, groupId);
        int total = allAgentIds.size();

        if (total == 0) {
            job.setStatus("COMPLETED");
            job.setCompletedAt(LocalDateTime.now());
            jobRepo.save(job);
            addEvent(job, "COMPLETED", "{\"reason\":\"NO_TARGETS\"}");
            return;
        }

        List<Integer> waveSizes = buildWaveSizes(total, initialWaveSize, rampFactor, canaryOnly);

        int cursor = 0;
        for (int wave = 0; wave < waveSizes.size(); wave++) {
            int size = waveSizes.get(wave);
            int to = Math.min(cursor + size, total);
            List<String> agentIds = allAgentIds.subList(cursor, to);
            for (String agentId : agentIds) {
                FileDistributionAgentStatus status = new FileDistributionAgentStatus();
                status.setJob(job);
                status.setAgentId(agentId);
                status.setWave(wave);
                status.setStatus("QUEUED");
                agentStatusRepo.save(status);
            }
            cursor = to;
        }

        job.setStatus("IN_PROGRESS");
        jobRepo.save(job);

        addEvent(job, "WAVE_PLAN", toJson(Map.of(
                "totalTargets", total,
                "waveSizes", waveSizes,
                "initialWaveSize", initialWaveSize,
                "rampUpFactor", rampFactor,
                "canaryOnly", canaryOnly
        )));

        startWave(jobId, 0);
    }

    /**
     * Wave별 상태/진행률/실패/취소/재시도 등 상태 관리
     */
    @Transactional
    public void updateWaveStatus(Long jobId, int wave, String status) {
        List<FileDistributionAgentStatus> list = agentStatusRepo.findByJobIdAndWaveOrderByIdAsc(jobId, wave);
        for (FileDistributionAgentStatus s : list) {
            if ("CANCELLED".equals(status)
                    && ("COMPLETED".equals(s.getStatus()) || "FAILED".equals(s.getStatus()))) {
                continue;
            }
            s.setStatus(status);
            if ("IN_PROGRESS".equals(status)) {
                s.setStartedAt(LocalDateTime.now());
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                s.setCompletedAt(LocalDateTime.now());
            }
            agentStatusRepo.save(s);
        }
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        addEvent(job, "WAVE_STATUS", "{\"wave\":" + wave + ",\"status\":\"" + status + "\"}");
    }

    @Transactional
    public void startWave(Long jobId, int wave) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        List<FileDistributionAgentStatus> list = agentStatusRepo.findByJobIdAndWaveOrderByIdAsc(jobId, wave);
        List<FileDistributionAgentStatus> activated = new ArrayList<>();
        for (FileDistributionAgentStatus s : list) {
            if ("QUEUED".equals(s.getStatus())) {
                s.setStatus("IN_PROGRESS");
                s.setStartedAt(LocalDateTime.now());
                s.setCompletedAt(null);
                agentStatusRepo.save(s);
                activated.add(s);
            }
        }

        if (!"CANCELLED".equals(job.getStatus()) && !"FAILED".equals(job.getStatus()) && !"COMPLETED".equals(job.getStatus())) {
            job.setStatus("IN_PROGRESS");
            jobRepo.save(job);
        }
        addEvent(job, "WAVE_STARTED", "{\"wave\":" + wave + ",\"targets\":" + activated.size() + "}");

        dispatchWaveCommands(job, wave, activated);
    }

    /**
     * Wave별 취소/재시도/확장 등 제어 API
     */
    @Transactional
    public void cancelWave(Long jobId, int wave) {
        updateWaveStatus(jobId, wave, "CANCELLED");
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        addEvent(job, "WAVE_CANCELLED", "{\"wave\":" + wave + "}");
    }

    @Transactional
    public Map<String, Object> retryFailedAgents(Long jobId, Integer wave) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        if ("CANCELLED".equals(job.getStatus())) {
            throw new IllegalStateException("Cancelled job cannot be retried");
        }

        List<FileDistributionAgentStatus> failed = (wave == null)
                ? agentStatusRepo.findByJobIdAndStatusOrderByWaveAscIdAsc(jobId, "FAILED")
                : agentStatusRepo.findByJobIdAndWaveAndStatusOrderByIdAsc(jobId, wave, "FAILED");

        int resetCount = 0;
        Set<Integer> touchedWaves = new TreeSet<>();
        for (FileDistributionAgentStatus s : failed) {
            s.setStatus("QUEUED");
            s.setErrorMessage(null);
            s.setStartedAt(null);
            s.setCompletedAt(null);
            agentStatusRepo.save(s);
            resetCount++;
            touchedWaves.add(s.getWave());
        }

        for (Integer w : touchedWaves) {
            startWave(jobId, w);
        }

        if (resetCount > 0) {
            job.setStatus("IN_PROGRESS");
            job.setErrorMessage(null);
            job.setCompletedAt(null);
            jobRepo.save(job);
        }

        addEvent(job, "RETRY_FAILED", "{\"wave\":" + (wave == null ? "null" : wave) + ",\"count\":" + resetCount + "}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("retried", resetCount);
        out.put("waves", touchedWaves);
        return out;
    }

    @Transactional
    public FileDistributionJob rollbackToJob(Long sourceJobId,
                                             Long rollbackFromJobId,
                                             int waveSize,
                                             boolean canaryOnly,
                                             Long groupId) {
        FileDistributionJob source = jobRepo.findById(sourceJobId).orElseThrow();
        FileDistributionJob stable = jobRepo.findById(rollbackFromJobId).orElseThrow();
        List<String> sourceTargets = resolveSourceTargetAgentIds(source.getId());
        List<String> explicitTargets = (groupId == null && !sourceTargets.isEmpty()) ? sourceTargets : null;

        FileDistributionJob rollbackJob = createJob(
                stable.getUploadId(),
                stable.getOriginalFilename(),
                Math.max(1, stable.getTotalChunks() == null ? 1 : stable.getTotalChunks()),
                waveSize,
                canaryOnly,
                groupId,
                stable.getArtifactId(),
                stable.getTargetVersion(),
                stable.getSourceMode(),
                stable.getRampUpFactor(),
                explicitTargets
        );
        addEvent(rollbackJob, "ROLLBACK_CREATED", toJson(Map.of(
                "sourceJobId", source.getId(),
                "rollbackFromJobId", stable.getId(),
                "explicitTargetCount", explicitTargets == null ? 0 : explicitTargets.size()
        )));
        return rollbackJob;
    }

    @Transactional
    public Optional<Long> tryAutoCatchUpOnHeartbeat(PcMaster pc) {
        if (!autoCatchupOnHeartbeat || pc == null) {
            return Optional.empty();
        }
        if (pc.getMacAddress() == null || pc.getMacAddress().isBlank()) {
            return Optional.empty();
        }
        if (!"ONLINE".equalsIgnoreCase(pc.getStatus())) {
            return Optional.empty();
        }

        Optional<FileDistributionJob> baselineOpt = resolveAutoCatchupBaselineJob();
        if (baselineOpt.isEmpty()) {
            return Optional.empty();
        }
        FileDistributionJob baseline = baselineOpt.get();
        String targetVersion = normalizeTargetVersion(baseline.getTargetVersion());
        if (targetVersion == null || targetVersion.isBlank() || baseline.getArtifactId() == null) {
            return Optional.empty();
        }

        String currentVersion = pc.getAgentVersion() == null ? null : pc.getAgentVersion().trim();
        if (currentVersion != null && !currentVersion.isBlank() && targetVersion.equalsIgnoreCase(currentVersion)) {
            return Optional.empty();
        }

        if (hasBlockingRecentUpdateCommand(pc.getMacAddress(), targetVersion)) {
            return Optional.empty();
        }

        String uploadId = "auto-catchup-" + baseline.getId() + "-" + pc.getMacAddress().replace(":", "")
                + "-" + System.currentTimeMillis();
        FileDistributionJob catchupJob = createJob(
                uploadId,
                baseline.getOriginalFilename() == null || baseline.getOriginalFilename().isBlank()
                        ? "agent-update-catchup"
                        : baseline.getOriginalFilename(),
                Math.max(1, baseline.getTotalChunks() == null ? 1 : baseline.getTotalChunks()),
                1,
                true,
                null,
                baseline.getArtifactId(),
                targetVersion,
                baseline.getSourceMode(),
                baseline.getRampUpFactor(),
                List.of(pc.getMacAddress())
        );

        addEvent(catchupJob, "AUTO_CATCHUP_CREATED", toJson(Map.of(
                "sourceJobId", baseline.getId(),
                "agentId", pc.getMacAddress(),
                "currentVersion", currentVersion == null ? "" : currentVersion,
                "targetVersion", targetVersion
        )));
        if (!Objects.equals(catchupJob.getId(), baseline.getId())) {
            addEvent(baseline, "AUTO_CATCHUP_ENQUEUED", toJson(Map.of(
                    "newJobId", catchupJob.getId(),
                    "agentId", pc.getMacAddress(),
                    "currentVersion", currentVersion == null ? "" : currentVersion,
                    "targetVersion", targetVersion
            )));
        }
        return Optional.ofNullable(catchupJob.getId());
    }

    @Transactional
    public void cancelJob(Long jobId) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        if ("COMPLETED".equals(job.getStatus()) || "CANCELLED".equals(job.getStatus())) {
            return;
        }
        List<FileDistributionAgentStatus> all = agentStatusRepo.findByJobIdOrderByWaveAscIdAsc(jobId);
        for (FileDistributionAgentStatus s : all) {
            if (!"COMPLETED".equals(s.getStatus()) && !"FAILED".equals(s.getStatus())) {
                s.setStatus("CANCELLED");
                s.setCompletedAt(LocalDateTime.now());
                agentStatusRepo.save(s);
            }
        }
        job.setStatus("CANCELLED");
        job.setCancelledAt(LocalDateTime.now());
        jobRepo.save(job);
        addEvent(job, "CANCELLED", "{}");
    }

    @Transactional
    public void reportAgentResult(Long jobId, String agentId, boolean success, String errorMessage) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        if ("CANCELLED".equals(job.getStatus()) || "FAILED".equals(job.getStatus()) || "COMPLETED".equals(job.getStatus())) {
            return;
        }
        Optional<FileDistributionAgentStatus> statusOpt = agentStatusRepo.findByJobIdAndAgentId(jobId, agentId);
        if (statusOpt.isEmpty()) {
            addEvent(job, "AGENT_RESULT_IGNORED", toJson(Map.of(
                    "agentId", agentId,
                    "reason", "NOT_TARGETED",
                    "success", success
            )));
            return;
        }

        FileDistributionAgentStatus status = statusOpt.get();
        status.setStatus(success ? "COMPLETED" : "FAILED");
        status.setErrorMessage(success ? null : (errorMessage == null ? "unknown" : errorMessage));
        status.setCompletedAt(LocalDateTime.now());
        agentStatusRepo.save(status);

        addEvent(job, "AGENT_RESULT", "{\"agentId\":\"" + agentId + "\",\"success\":" + success + "}");
        evaluateAndAdvance(jobId, status.getWave());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getJobStatus(Long jobId) {
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        List<FileDistributionAgentStatus> all = agentStatusRepo.findByJobIdOrderByWaveAscIdAsc(jobId);
        long total = all.size();
        long completed = all.stream().filter(s -> "COMPLETED".equals(s.getStatus())).count();
        long failed = all.stream().filter(s -> "FAILED".equals(s.getStatus())).count();
        long inProgress = all.stream().filter(s -> "IN_PROGRESS".equals(s.getStatus())).count();
        long cancelled = all.stream().filter(s -> "CANCELLED".equals(s.getStatus())).count();

        Map<String, Object> waveSummary = summarizeWaves(all);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId());
        out.put("uploadId", job.getUploadId());
        out.put("status", job.getStatus());
        out.put("artifactId", job.getArtifactId());
        out.put("targetVersion", job.getTargetVersion());
        out.put("sourceMode", job.getSourceMode());
        out.put("rampUpFactor", job.getRampUpFactor());
        out.put("totalAgents", total);
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("inProgress", inProgress);
        out.put("cancelled", cancelled);
        out.put("progress", total == 0 ? 0.0 : ((double) (completed + failed + cancelled) / (double) total));
        out.put("createdAt", job.getCreatedAt());
        out.put("completedAt", job.getCompletedAt());
        out.put("cancelledAt", job.getCancelledAt());
        out.put("waves", waveSummary);
        return out;
    }

    @Transactional(readOnly = true)
    public List<FileDistributionEvent> getEvents(Long jobId) {
        return eventRepo.findByJobIdOrderByCreatedAtDesc(jobId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentJobs() {
        return jobRepo.findTop50ByOrderByCreatedAtDesc().stream().map(job -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", job.getId());
            row.put("uploadId", job.getUploadId());
            row.put("originalFilename", job.getOriginalFilename());
            row.put("status", job.getStatus());
            row.put("artifactId", job.getArtifactId());
            row.put("targetVersion", job.getTargetVersion());
            row.put("sourceMode", job.getSourceMode());
            row.put("rampUpFactor", job.getRampUpFactor());
            row.put("createdAt", job.getCreatedAt());
            row.put("completedAt", job.getCompletedAt());
            row.put("cancelledAt", job.getCancelledAt());
            row.put("errorMessage", job.getErrorMessage());
            return row;
        }).toList();
    }

    private void dispatchWaveCommands(FileDistributionJob job,
                                      int wave,
                                      List<FileDistributionAgentStatus> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        if (job.getArtifactId() == null) {
            addEvent(job, "WAVE_READY_MANUAL", toJson(Map.of(
                    "wave", wave,
                    "targets", targets.size(),
                    "reason", "artifactId missing"
            )));
            return;
        }

        Optional<ScriptArtifact> artifactOpt = scriptArtifactRepository.findById(job.getArtifactId());
        if (artifactOpt.isEmpty()) {
            String message = "artifact not found: " + job.getArtifactId();
            markWaveFailed(job, targets, message);
            return;
        }
        ScriptArtifact artifact = artifactOpt.get();
        String peerToken = buildPeerToken(job, artifact.getSha256());

        List<Map<String, Object>> peerCandidates = buildPeerCandidates(job, wave, artifact.getSha256(), peerToken);

        int queued = 0;
        int failed = 0;
        for (FileDistributionAgentStatus status : targets) {
            String agentId = status.getAgentId();
            try {
                CommandQueue command = new CommandQueue();
                command.setMacAddress(agentId);
                command.setCommandType("AGENT_UPDATE");
                command.setScriptUrl(null);
                command.setStatus("QUEUED");
                command.setCreatedAt(LocalDateTime.now());
                command.setIsExecuted(false);
                command.setIsAdminOperation(true);
                command.setPayloadJson(buildAgentUpdatePayload(job, wave, agentId, artifact, peerCandidates, peerToken));
                commandQueueRepository.save(command);
                queued++;
            } catch (Exception ex) {
                status.setStatus("FAILED");
                status.setErrorMessage("failed to enqueue update command: " + ex.getMessage());
                status.setCompletedAt(LocalDateTime.now());
                agentStatusRepo.save(status);
                failed++;
            }
        }

        addEvent(job, "WAVE_DISPATCHED", toJson(Map.of(
                "wave", wave,
                "queued", queued,
                "enqueueFailed", failed,
                "sourceMode", job.getSourceMode(),
                "peerCandidates", peerCandidates.size()
        )));

        if (queued == 0) {
            evaluateAndAdvance(job.getId(), wave);
        }
    }

    private String buildAgentUpdatePayload(FileDistributionJob job,
                                           int wave,
                                           String agentId,
                                           ScriptArtifact artifact,
                                           List<Map<String, Object>> peerCandidates,
                                           String peerToken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getId());
        payload.put("wave", wave);
        payload.put("artifactId", artifact.getId());
        payload.put("downloadUrl", "/api/v1/scripts/" + artifact.getId() + "/download");
        payload.put("sha256", artifact.getSha256());
        payload.put("targetVersion", job.getTargetVersion());
        payload.put("sourceMode", normalizeSourceMode(job.getSourceMode()));
        payload.put("peerCandidates", peerCandidates);
        payload.put("peerToken", peerToken);

        PcMaster pc = pcMasterRepository.findById(agentId).orElse(null);
        if (pc != null && pc.getLastAgentSessionId() != null && !pc.getLastAgentSessionId().isBlank()) {
            payload.put("preUpdateSessionId", pc.getLastAgentSessionId());
        }
        return toJson(payload);
    }

    private List<Map<String, Object>> buildPeerCandidates(FileDistributionJob job,
                                                           int currentWave,
                                                           String sha256,
                                                           String peerToken) {
        if (!SOURCE_MODE_HYBRID_PEER.equals(normalizeSourceMode(job.getSourceMode())) || currentWave <= 0) {
            return List.of();
        }

        List<FileDistributionAgentStatus> statuses = agentStatusRepo.findByJobIdOrderByWaveAscIdAsc(job.getId());
        List<Map<String, Object>> peers = new ArrayList<>();
        for (FileDistributionAgentStatus status : statuses) {
            if (!"COMPLETED".equals(status.getStatus())) {
                continue;
            }
            if (status.getWave() == null || status.getWave() >= currentWave) {
                continue;
            }
            PcMaster pc = pcMasterRepository.findById(status.getAgentId()).orElse(null);
            if (pc == null || pc.getIpAddress() == null || pc.getIpAddress().isBlank()) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("macAddress", pc.getMacAddress());
            row.put("ipAddress", pc.getIpAddress());
            row.put("port", peerPort);
            row.put("url", "http://" + pc.getIpAddress() + ":" + peerPort + "/pcagent/update-cache/" + sha256);
            row.put("token", peerToken);
            peers.add(row);

            if (peers.size() >= Math.max(1, maxPeerCandidates)) {
                break;
            }
        }
        return peers;
    }

    private void markWaveFailed(FileDistributionJob job,
                                List<FileDistributionAgentStatus> targets,
                                String message) {
        LocalDateTime now = LocalDateTime.now();
        for (FileDistributionAgentStatus status : targets) {
            status.setStatus("FAILED");
            status.setCompletedAt(now);
            status.setErrorMessage(message);
            agentStatusRepo.save(status);
        }
        job.setStatus("FAILED");
        job.setErrorMessage(message);
        job.setCompletedAt(now);
        jobRepo.save(job);
        addEvent(job, "FAILED", toJson(Map.of(
                "reason", message,
                "targets", targets.size()
        )));
    }

    private String buildPeerToken(FileDistributionJob job, String sha256) {
        String source = (peerSharedSecret == null ? "" : peerSharedSecret)
                + "|" + (job == null ? "" : job.getId())
                + "|" + (sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(source.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return hex.substring(0, Math.min(40, hex.length()));
        } catch (Exception ex) {
            return String.valueOf(Math.abs(source.hashCode()));
        }
    }

    private int normalizeWaveSize(int waveSize) {
        int safe = waveSize <= 0 ? defaultWaveSize : waveSize;
        return Math.min(Math.max(1, safe), Math.max(1, maxWaveSize));
    }

    private String normalizeTargetVersion(String targetVersion) {
        if (targetVersion == null) {
            return null;
        }
        String normalized = targetVersion.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeSourceMode(String sourceMode) {
        if (sourceMode == null || sourceMode.isBlank()) {
            return SOURCE_MODE_SERVER_ONLY;
        }
        String normalized = sourceMode.trim().toUpperCase(Locale.ROOT);
        return SOURCE_MODE_HYBRID_PEER.equals(normalized) ? SOURCE_MODE_HYBRID_PEER : SOURCE_MODE_SERVER_ONLY;
    }

    private double resolveRampUpFactor(Double rampUpFactor) {
        double factor = rampUpFactor == null ? defaultRampUpFactor : rampUpFactor;
        if (Double.isNaN(factor) || Double.isInfinite(factor)) {
            return defaultRampUpFactor;
        }
        return Math.max(1.0, Math.min(3.0, factor));
    }

    private List<Integer> buildWaveSizes(int totalTargets,
                                         int initialWaveSize,
                                         double rampFactor,
                                         boolean canaryOnly) {
        List<Integer> result = new ArrayList<>();
        int remaining = Math.max(0, totalTargets);
        double nextSize = Math.max(1, initialWaveSize);

        while (remaining > 0) {
            int waveSize = Math.max(1, Math.min((int) Math.round(nextSize), Math.max(1, maxWaveSize)));
            waveSize = Math.min(waveSize, remaining);
            result.add(waveSize);
            remaining -= waveSize;

            if (canaryOnly) {
                break;
            }

            nextSize = Math.max(waveSize + 1, nextSize * rampFactor);
            nextSize = Math.min(nextSize, Math.max(1, maxWaveSize));
        }

        return result;
    }

    private Map<String, Object> summarizeWaves(List<FileDistributionAgentStatus> statuses) {
        Map<Integer, Map<String, Long>> waves = new LinkedHashMap<>();
        for (FileDistributionAgentStatus status : statuses) {
            Integer wave = status.getWave() == null ? -1 : status.getWave();
            Map<String, Long> row = waves.computeIfAbsent(wave, k -> {
                Map<String, Long> map = new LinkedHashMap<>();
                map.put("total", 0L);
                map.put("queued", 0L);
                map.put("inProgress", 0L);
                map.put("completed", 0L);
                map.put("failed", 0L);
                map.put("cancelled", 0L);
                return map;
            });
            row.put("total", row.get("total") + 1);
            switch (status.getStatus() == null ? "" : status.getStatus()) {
                case "QUEUED" -> row.put("queued", row.get("queued") + 1);
                case "IN_PROGRESS" -> row.put("inProgress", row.get("inProgress") + 1);
                case "COMPLETED" -> row.put("completed", row.get("completed") + 1);
                case "FAILED" -> row.put("failed", row.get("failed") + 1);
                case "CANCELLED" -> row.put("cancelled", row.get("cancelled") + 1);
                default -> {
                }
            }
        }
        return new LinkedHashMap<>(Map.of("items", waves));
    }

    private List<String> getTargetAgentIdsForJob(Long groupId) {
        List<PcMaster> targets = (groupId == null)
                ? pcMasterRepository.findByStatus("ONLINE")
                : pcMasterRepository.findByGroupIdAndStatus(groupId, "ONLINE");
        return targets.stream()
                .map(PcMaster::getMacAddress)
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<String> resolveTargetAgentIds(List<String> explicitTargetAgentIds, Long groupId) {
        if (explicitTargetAgentIds == null || explicitTargetAgentIds.isEmpty()) {
            return getTargetAgentIdsForJob(groupId);
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String raw : explicitTargetAgentIds) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isBlank()) {
                deduped.add(trimmed);
            }
        }
        return new ArrayList<>(deduped);
    }

    private List<String> resolveSourceTargetAgentIds(Long sourceJobId) {
        List<FileDistributionAgentStatus> statuses = agentStatusRepo.findByJobIdOrderByWaveAscIdAsc(sourceJobId);
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (FileDistributionAgentStatus status : statuses) {
            if (status == null || status.getAgentId() == null || status.getAgentId().isBlank()) {
                continue;
            }
            deduped.add(status.getAgentId().trim());
        }
        return new ArrayList<>(deduped);
    }

    private Optional<FileDistributionJob> resolveAutoCatchupBaselineJob() {
        Optional<FileDistributionJob> active = jobRepo
                .findFirstByStatusInAndArtifactIdIsNotNullAndTargetVersionIsNotNullOrderByCreatedAtDesc(
                        List.of("IN_PROGRESS", "QUEUED"));
        if (active.isPresent()) {
            return active;
        }
        return jobRepo.findFirstByStatusAndArtifactIdIsNotNullAndTargetVersionIsNotNullOrderByCompletedAtDescIdDesc(
                "COMPLETED");
    }

    private boolean hasBlockingRecentUpdateCommand(String macAddress, String targetVersion) {
        if (macAddress == null || macAddress.isBlank() || targetVersion == null || targetVersion.isBlank()) {
            return false;
        }
        List<CommandQueue> recent = commandQueueRepository
                .findTop50ByMacAddressAndCommandTypeOrderByCreatedAtDesc(macAddress, "AGENT_UPDATE");
        LocalDateTime failureCooldown = LocalDateTime.now().minusSeconds(Math.max(30, autoCatchupFailureCooldownSeconds));
        for (CommandQueue cmd : recent) {
            String payloadVersion = extractPayloadText(cmd.getPayloadJson(), "targetVersion");
            if (payloadVersion == null || !targetVersion.equalsIgnoreCase(payloadVersion)) {
                continue;
            }
            String status = cmd.getStatus() == null ? "" : cmd.getStatus().trim().toUpperCase(Locale.ROOT);
            if ("QUEUED".equals(status) || "DELIVERED".equals(status) || "ACKED".equals(status)) {
                return true;
            }
            if ("COMPLETED".equals(status)) {
                String verification = cmd.getVerificationStatus() == null
                        ? ""
                        : cmd.getVerificationStatus().trim().toUpperCase(Locale.ROOT);
                if ("PENDING_REBOOT_CONFIRM".equals(verification)
                        || "CONFIRMED".equals(verification)) {
                    return true;
                }
            }
            if ("FAILED".equals(status)) {
                LocalDateTime createdAt = cmd.getCreatedAt();
                if (createdAt == null || !createdAt.isBefore(failureCooldown)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void triggerAutoRollback(FileDistributionJob failedJob, int failedWave, double failRate) {
        if (!autoRollbackEnabled || failedJob == null) {
            return;
        }
        Optional<FileDistributionJob> stableOpt = jobRepo
                .findFirstByStatusAndArtifactIdIsNotNullAndTargetVersionIsNotNullAndIdLessThanOrderByCompletedAtDescIdDesc(
                        "COMPLETED", failedJob.getId());
        if (stableOpt.isEmpty()) {
            addEvent(failedJob, "AUTO_ROLLBACK_SKIPPED", toJson(Map.of(
                    "reason", "NO_STABLE_JOB",
                    "failedWave", failedWave,
                    "failRate", failRate
            )));
            return;
        }
        FileDistributionJob stable = stableOpt.get();
        if (stable.getArtifactId() == null || stable.getTargetVersion() == null || stable.getTargetVersion().isBlank()) {
            addEvent(failedJob, "AUTO_ROLLBACK_SKIPPED", toJson(Map.of(
                    "reason", "STABLE_JOB_INVALID",
                    "stableJobId", stable.getId()
            )));
            return;
        }
        if (Objects.equals(stable.getArtifactId(), failedJob.getArtifactId())) {
            addEvent(failedJob, "AUTO_ROLLBACK_SKIPPED", toJson(Map.of(
                    "reason", "SAME_ARTIFACT",
                    "stableJobId", stable.getId()
            )));
            return;
        }

        List<String> targets = resolveSourceTargetAgentIds(failedJob.getId());
        if (targets.isEmpty()) {
            addEvent(failedJob, "AUTO_ROLLBACK_SKIPPED", toJson(Map.of(
                    "reason", "NO_TARGETS",
                    "stableJobId", stable.getId()
            )));
            return;
        }

        int rollbackWaveSize = normalizeWaveSize(Math.min(defaultWaveSize, Math.max(1, targets.size())));
        FileDistributionJob rollbackJob = createJob(
                "auto-rollback-" + failedJob.getId() + "-" + System.currentTimeMillis(),
                stable.getOriginalFilename() == null || stable.getOriginalFilename().isBlank()
                        ? "agent-update-rollback"
                        : stable.getOriginalFilename(),
                Math.max(1, stable.getTotalChunks() == null ? 1 : stable.getTotalChunks()),
                rollbackWaveSize,
                false,
                null,
                stable.getArtifactId(),
                stable.getTargetVersion(),
                stable.getSourceMode(),
                stable.getRampUpFactor(),
                targets
        );
        addEvent(rollbackJob, "AUTO_ROLLBACK_CREATED", toJson(Map.of(
                "failedJobId", failedJob.getId(),
                "rollbackFromJobId", stable.getId(),
                "targetCount", targets.size()
        )));
        addEvent(failedJob, "AUTO_ROLLBACK_STARTED", toJson(Map.of(
                "rollbackJobId", rollbackJob.getId(),
                "rollbackFromJobId", stable.getId(),
                "targetCount", targets.size(),
                "failedWave", failedWave,
                "failRate", failRate
        )));
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
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    private void evaluateAndAdvance(Long jobId, int wave) {
        List<FileDistributionAgentStatus> currentWave = agentStatusRepo.findByJobIdAndWaveOrderByIdAsc(jobId, wave);
        if (currentWave.isEmpty()) return;

        boolean allDone = currentWave.stream().allMatch(s ->
                "COMPLETED".equals(s.getStatus()) || "FAILED".equals(s.getStatus()) || "CANCELLED".equals(s.getStatus()));
        if (!allDone) return;

        long failed = currentWave.stream().filter(s -> "FAILED".equals(s.getStatus())).count();
        double failRate = (double) failed / (double) currentWave.size();

        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();
        if (failRate > failThreshold) {
            job.setStatus("FAILED");
            job.setErrorMessage("Wave fail rate too high: " + failRate);
            job.setCompletedAt(LocalDateTime.now());
            jobRepo.save(job);
            addEvent(job, "FAILED", "{\"wave\":" + wave + ",\"failRate\":" + failRate + "}");
            triggerAutoRollback(job, wave, failRate);
            return;
        }

        int nextWave = wave + 1;
        List<FileDistributionAgentStatus> next = agentStatusRepo.findByJobIdAndWaveOrderByIdAsc(jobId, nextWave);
        if (next.isEmpty()) {
            job.setStatus("COMPLETED");
            job.setCompletedAt(LocalDateTime.now());
            jobRepo.save(job);
            addEvent(job, "COMPLETED", "{}");
            return;
        }
        startWave(jobId, nextWave);
    }

    private void addEvent(FileDistributionJob job, String eventType, String detailJson) {
        FileDistributionEvent event = new FileDistributionEvent();
        event.setJob(job);
        event.setEventType(eventType);
        event.setDetailJson(detailJson == null ? "{}" : detailJson);
        event.setCreatedAt(LocalDateTime.now());
        eventRepo.save(event);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
