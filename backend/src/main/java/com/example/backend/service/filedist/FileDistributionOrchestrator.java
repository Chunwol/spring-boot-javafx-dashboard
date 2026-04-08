package com.example.backend.service.filedist;

import com.example.backend.model.filedist.FileDistributionAgentStatus;
import com.example.backend.model.filedist.FileDistributionEvent;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.filedist.FileDistributionEventRepository;
import com.example.backend.repository.filedist.FileDistributionJobRepository;
import com.example.backend.repository.filedist.FileDistributionAgentStatusRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대용량 파일 배포 Wave/Canary 오케스트레이션 서비스 (초안)
 * - Wave별로 Agent(PC) 그룹을 나누어 순차/동시 배포
 * - 각 Wave 성공률/실패/진행상황 모니터링 및 다음 Wave 자동/수동 진행
 * - Canary(소수 테스트) → 전체 확장 구조 지원
 * - Wave별 취소/재시도/상태조회/이력 기록
 */
@Service
public class FileDistributionOrchestrator {
    private final FileDistributionJobRepository jobRepo;
    private final FileDistributionAgentStatusRepository agentStatusRepo;
    private final FileDistributionEventRepository eventRepo;
    private final PcMasterRepository pcMasterRepository;

    @Value("${app.file-distribution.fail-threshold:0.20}")
    private double failThreshold;

    @Value("${app.file-distribution.default-wave-size:20}")
    private int defaultWaveSize;

    @Value("${app.file-distribution.max-wave-size:100}")
    private int maxWaveSize;

    public FileDistributionOrchestrator(FileDistributionJobRepository jobRepo,
                                        FileDistributionAgentStatusRepository agentStatusRepo,
                                        FileDistributionEventRepository eventRepo,
                                        PcMasterRepository pcMasterRepository) {
        this.jobRepo = jobRepo;
        this.agentStatusRepo = agentStatusRepo;
        this.eventRepo = eventRepo;
        this.pcMasterRepository = pcMasterRepository;
    }

    @Transactional
    public FileDistributionJob createJob(String uploadId,
                                         String originalFilename,
                                         int totalChunks,
                                         int waveSize,
                                         boolean canaryOnly,
                                         Long groupId) {
        FileDistributionJob job = new FileDistributionJob();
        job.setUploadId(uploadId);
        job.setOriginalFilename(originalFilename);
        job.setTotalChunks(totalChunks);
        job.setStatus("QUEUED");
        job.setCreatedAt(LocalDateTime.now());
        FileDistributionJob saved = jobRepo.save(job);

        orchestrateWaves(saved.getId(), waveSize, canaryOnly, groupId);
        addEvent(saved, "CREATED", "{\"waveSize\":" + waveSize + ",\"canaryOnly\":" + canaryOnly + "}");
        return saved;
    }

    /**
     * 배포 Job을 Wave 단위로 오케스트레이션 (초기 Canary → 전체)
     * @param jobId 배포 Job ID
     * @param waveSize 1 Wave당 PC 수
     * @param canaryOnly true면 첫 Wave만 실행
     */
    @Transactional
    public void orchestrateWaves(Long jobId, int waveSize, boolean canaryOnly, Long groupId) {
        if (waveSize <= 0) waveSize = defaultWaveSize;
        waveSize = Math.min(Math.max(1, waveSize), Math.max(1, maxWaveSize));
        List<String> allAgentIds = getTargetAgentIdsForJob(groupId);
        int total = allAgentIds.size();
        FileDistributionJob job = jobRepo.findById(jobId).orElseThrow();

        if (total == 0) {
            job.setStatus("COMPLETED");
            job.setCompletedAt(LocalDateTime.now());
            jobRepo.save(job);
            addEvent(job, "COMPLETED", "{\"reason\":\"NO_TARGETS\"}");
            return;
        }

        int waveCount = (int) Math.ceil((double) total / waveSize);

        for (int wave = 0; wave < waveCount; wave++) {
            int from = wave * waveSize;
            int to = Math.min(from + waveSize, total);
            List<String> agentIds = allAgentIds.subList(from, to);
            for (String agentId : agentIds) {
                FileDistributionAgentStatus status = new FileDistributionAgentStatus();
                status.setJob(job);
                status.setAgentId(agentId);
                status.setWave(wave);
                status.setStatus("QUEUED");
                agentStatusRepo.save(status);
            }
            if (canaryOnly && wave == 0) break;
        }

        job.setStatus("IN_PROGRESS");
        jobRepo.save(job);
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
        for (FileDistributionAgentStatus s : list) {
            if ("QUEUED".equals(s.getStatus())) {
                s.setStatus("IN_PROGRESS");
                s.setStartedAt(LocalDateTime.now());
                s.setCompletedAt(null);
                agentStatusRepo.save(s);
            }
        }
        if (!"CANCELLED".equals(job.getStatus()) && !"FAILED".equals(job.getStatus()) && !"COMPLETED".equals(job.getStatus())) {
            job.setStatus("IN_PROGRESS");
            jobRepo.save(job);
        }
        addEvent(job, "WAVE_STARTED", "{\"wave\":" + wave + "}");
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
        java.util.Set<Integer> touchedWaves = new java.util.TreeSet<>();
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

        FileDistributionJob rollbackJob = createJob(
                stable.getUploadId(),
                stable.getOriginalFilename(),
                Math.max(1, stable.getTotalChunks() == null ? 1 : stable.getTotalChunks()),
                waveSize,
                canaryOnly,
                groupId
        );
        addEvent(rollbackJob, "ROLLBACK_CREATED",
                "{\"sourceJobId\":" + source.getId() + ",\"rollbackFromJobId\":" + stable.getId() + "}");
        return rollbackJob;
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
        FileDistributionAgentStatus status = agentStatusRepo.findByJobIdAndAgentId(jobId, agentId).orElseThrow();
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId());
        out.put("uploadId", job.getUploadId());
        out.put("status", job.getStatus());
        out.put("totalAgents", total);
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("inProgress", inProgress);
        out.put("cancelled", cancelled);
        out.put("progress", total == 0 ? 0.0 : ((double) (completed + failed + cancelled) / (double) total));
        out.put("createdAt", job.getCreatedAt());
        out.put("completedAt", job.getCompletedAt());
        out.put("cancelledAt", job.getCancelledAt());
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
            row.put("createdAt", job.getCreatedAt());
            row.put("completedAt", job.getCompletedAt());
            row.put("cancelledAt", job.getCancelledAt());
            row.put("errorMessage", job.getErrorMessage());
            return row;
        }).toList();
    }

    // --- 내부 메서드 예시 ---
    private List<String> getTargetAgentIdsForJob(Long groupId) {
        List<PcMaster> targets = (groupId == null)
                ? pcMasterRepository.findByStatus("ONLINE")
                : pcMasterRepository.findByGroupIdAndStatus(groupId, "ONLINE");
        return targets.stream().map(PcMaster::getMacAddress).toList();
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
}
