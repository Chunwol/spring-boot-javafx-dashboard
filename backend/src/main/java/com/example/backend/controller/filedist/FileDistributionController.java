package com.example.backend.controller.filedist;

import com.example.backend.model.filedist.FileDistributionEvent;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.security.FileDistributionRequestSecurity;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/file-distribution")
public class FileDistributionController {

    private final FileDistributionOrchestrator orchestrator;
    private final FileDistributionRequestSecurity requestSecurity;

    public FileDistributionController(FileDistributionOrchestrator orchestrator,
                                      FileDistributionRequestSecurity requestSecurity) {
        this.orchestrator = orchestrator;
        this.requestSecurity = requestSecurity;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs")
    public Map<String, Object> createJob(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        String uploadId = String.valueOf(body.getOrDefault("uploadId", ""));
        String originalFilename = String.valueOf(body.getOrDefault("originalFilename", "unknown.bin"));
        int totalChunks = toInt(body.get("totalChunks"), 1);
        int waveSize = toInt(body.get("waveSize"), 20);
        boolean canaryOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("canaryOnly", false)));
        Long groupId = parseOptionalLong(body.get("groupId"), "groupId");

        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be > 0");
        }

        String canonical = "createJob|" + uploadId + "|" + originalFilename + "|" + totalChunks + "|" + waveSize + "|" + canaryOnly + "|" + groupId;
        requestSecurity.validateOrThrow(request, canonical);

        FileDistributionJob job = orchestrator.createJob(uploadId, originalFilename, totalChunks, waveSize, canaryOnly, groupId);
        return Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "uploadId", job.getUploadId()
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/cancel")
    public Map<String, Object> cancelJob(@PathVariable Long jobId,
                                         HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "cancelJob|" + jobId);
        orchestrator.cancelJob(jobId);
        return Map.of("jobId", jobId, "status", "CANCELLED");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/waves/{wave}/cancel")
    public Map<String, Object> cancelWave(@PathVariable Long jobId,
                                          @PathVariable int wave,
                                          HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "cancelWave|" + jobId + "|" + wave);
        orchestrator.cancelWave(jobId, wave);
        return Map.of("jobId", jobId, "wave", wave, "status", "CANCELLED");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/waves/{wave}/start")
    public Map<String, Object> startWave(@PathVariable Long jobId,
                                         @PathVariable int wave,
                                         HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "startWave|" + jobId + "|" + wave);
        orchestrator.startWave(jobId, wave);
        return Map.of("jobId", jobId, "wave", wave, "status", "IN_PROGRESS");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/retry-failed")
    public Map<String, Object> retryFailed(@PathVariable Long jobId,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        Integer wave = null;
        if (body != null && body.get("wave") != null && !String.valueOf(body.get("wave")).isBlank()) {
            wave = parseRequiredInt(body.get("wave"), "wave");
        }
        requestSecurity.validateOrThrow(request, "retryFailed|" + jobId + "|" + wave);
        return orchestrator.retryFailedAgents(jobId, wave);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/rollback")
    public Map<String, Object> rollback(@PathVariable Long jobId,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Long rollbackFromJobId = parseRequiredLong(body.get("rollbackFromJobId"), "rollbackFromJobId");
        int waveSize = toInt(body.get("waveSize"), 20);
        boolean canaryOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("canaryOnly", false)));
        Long groupId = parseOptionalLong(body.get("groupId"), "groupId");

        requestSecurity.validateOrThrow(request,
                "rollback|" + jobId + "|" + rollbackFromJobId + "|" + waveSize + "|" + canaryOnly + "|" + groupId);

        FileDistributionJob rollbackJob = orchestrator.rollbackToJob(jobId, rollbackFromJobId, waveSize, canaryOnly, groupId);
        return Map.of(
                "sourceJobId", jobId,
                "rollbackFromJobId", rollbackFromJobId,
                "newJobId", rollbackJob.getId(),
                "status", rollbackJob.getStatus()
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{jobId}/agents/{agentId}/result")
    public Map<String, Object> reportAgentResult(@PathVariable Long jobId,
                                                 @PathVariable String agentId,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        boolean success = Boolean.parseBoolean(String.valueOf(body.getOrDefault("success", false)));
        String errorMessage = body.get("errorMessage") == null ? null : String.valueOf(body.get("errorMessage"));
        requestSecurity.validateOrThrow(request, "reportAgentResult|" + jobId + "|" + agentId + "|" + success);
        orchestrator.reportAgentResult(jobId, agentId, success, errorMessage);
        return Map.of("jobId", jobId, "agentId", agentId, "accepted", true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJobStatus(@PathVariable Long jobId) {
        return orchestrator.getJobStatus(jobId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/jobs/{jobId}/events")
    public List<Map<String, Object>> getEvents(@PathVariable Long jobId) {
        List<FileDistributionEvent> events = orchestrator.getEvents(jobId);
        return events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("eventType", e.getEventType());
            m.put("detailJson", e.getDetailJson());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/jobs")
    public List<Map<String, Object>> getRecentJobs() {
        return orchestrator.getRecentJobs();
    }

    private int toInt(Object value, int def) {
        if (value == null) return def;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return def;
        }
    }

    private Long parseRequiredLong(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a number");
        }
    }

    private Long parseOptionalLong(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a number");
        }
    }

    private Integer parseRequiredInt(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a number");
        }
    }
}
