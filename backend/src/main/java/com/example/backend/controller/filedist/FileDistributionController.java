package com.example.backend.controller.filedist;

import com.example.backend.model.filedist.FileDistributionEvent;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.security.FileDistributionRequestSecurity;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 파일 배포 API
 * 에이전트(PC)로의 파일 배포 작업(Job)을 생성하고 관리합니다.
 * 웨이브(Wave) 단위 배포, 롤백, 재시도, 취소를 지원합니다.
 * 모든 쓰기 요청은 {@link FileDistributionRequestSecurity}를 통해 재전송 공격을 방어합니다.
 */
@RestController
@RequestMapping("/api/v1/file-distribution")
@Tag(name = "File Distribution", description = "파일 배포 작업(Job) 관리 API — ADMIN 전용")
public class FileDistributionController {

    private static final Logger log = LoggerFactory.getLogger(FileDistributionController.class);
    private static final Pattern MAC_ADDRESS = Pattern.compile("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$");
    private static final int MAX_EXPLICIT_TARGETS = 2000;

    private final FileDistributionOrchestrator orchestrator;
    private final FileDistributionRequestSecurity requestSecurity;

    public FileDistributionController(FileDistributionOrchestrator orchestrator,
                                      FileDistributionRequestSecurity requestSecurity) {
        this.orchestrator = orchestrator;
        this.requestSecurity = requestSecurity;
    }

    /**
     * 파일 배포 작업 생성.
     * uploadId(청크 업로드 완료 ID), 파일명, 총 청크 수, 웨이브 크기 등을 받아 Job을 생성합니다.
     * canaryOnly=true이면 카나리 에이전트에만 우선 배포합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs")
    @Operation(
        summary = "파일 배포 작업 생성",
        description = "파일 배포 Job을 생성합니다. uploadId는 필수이며, groupId 또는 targetMacs로 대상을 지정하고 waveSize로 배포 단위를 조절합니다."
    )
    @ApiResponse(responseCode = "200", description = "Job 생성 성공 (jobId, status, uploadId 반환)")
    @ApiResponse(responseCode = "400", description = "uploadId 누락 또는 totalChunks 오류")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> createJob(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        String uploadId = String.valueOf(body.getOrDefault("uploadId", ""));
        String originalFilename = String.valueOf(body.getOrDefault("originalFilename", "unknown.bin"));
        int totalChunks = toInt(body.get("totalChunks"), 1);
        int waveSize = toInt(body.get("waveSize"), 5);
        boolean canaryOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("canaryOnly", false)));
        Long groupId = parseOptionalLong(body.get("groupId"), "groupId");
        boolean explicitTargetsRequested = body.containsKey("targetMacs");
        List<String> targetMacs = parseTargetMacs(body.get("targetMacs"), explicitTargetsRequested);
        Long artifactId = parseOptionalLong(body.get("artifactId"), "artifactId");
        String targetVersion = body.get("targetVersion") == null ? null : String.valueOf(body.get("targetVersion"));
        String sourceMode = String.valueOf(body.getOrDefault("sourceMode", "HYBRID_PEER"));
        Double rampUpFactor = parseOptionalDouble(body.get("rampUpFactor"), "rampUpFactor");
        String updatePackageType = String.valueOf(body.getOrDefault("updatePackageType", "JAR"))
                .trim().toUpperCase(java.util.Locale.ROOT);

        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be > 0");
        }
        if (groupId != null && explicitTargetsRequested) {
            throw new IllegalArgumentException("groupId and targetMacs cannot be used together");
        }
        if (!"JAR".equals(updatePackageType) && !"FULL_BUNDLE".equals(updatePackageType)) {
            throw new IllegalArgumentException("updatePackageType must be JAR or FULL_BUNDLE");
        }
        if ("FULL_BUNDLE".equals(updatePackageType)) {
            sourceMode = "SERVER_ONLY";
        }

        // 재전송 공격 방어: 요청의 모든 핵심 파라미터를 정규화 문자열로 서명 검증
        String canonical = "createJob|" + uploadId + "|" + originalFilename + "|" + totalChunks + "|"
                + waveSize + "|" + canaryOnly + "|" + groupId + "|" + artifactId + "|" + targetVersion + "|"
                + sourceMode + "|" + rampUpFactor + "|" + updatePackageType;
        if (explicitTargetsRequested) {
            canonical += "|targetMacs=" + String.join(",", targetMacs);
        }
        requestSecurity.validateOrThrow(request, canonical);

        FileDistributionJob job = orchestrator.createJob(
                uploadId,
                originalFilename,
                totalChunks,
                waveSize,
                canaryOnly,
                groupId,
                artifactId,
                targetVersion,
                sourceMode,
                rampUpFactor,
                updatePackageType,
                explicitTargetsRequested ? targetMacs : null
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        response.put("uploadId", job.getUploadId());
        response.put("artifactId", job.getArtifactId());
        response.put("targetVersion", job.getTargetVersion());
        response.put("sourceMode", job.getSourceMode());
        response.put("rampUpFactor", job.getRampUpFactor());
        response.put("updatePackageType", job.getUpdatePackageType());
        return response;
    }

    private List<String> parseTargetMacs(Object raw, boolean requested) {
        if (!requested) {
            return List.of();
        }
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("targetMacs must contain at least one MAC address");
        }
        if (values.size() > MAX_EXPLICIT_TARGETS) {
            throw new IllegalArgumentException("targetMacs cannot exceed " + MAX_EXPLICIT_TARGETS + " entries");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            String mac = value == null ? "" : String.valueOf(value).trim();
            if (!MAC_ADDRESS.matcher(mac).matches()) {
                throw new IllegalArgumentException("targetMacs contains an invalid MAC address");
            }
            normalized.add(mac.replace('-', ':').toUpperCase(java.util.Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    /**
     * 배포 작업 취소.
     * 진행 중인 웨이브를 포함해 전체 Job을 CANCELLED 상태로 전환합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "배포 작업 취소", description = "지정된 Job과 모든 진행 중인 웨이브를 취소합니다.")
    @ApiResponse(responseCode = "200", description = "취소 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "Job을 찾을 수 없음")
    public Map<String, Object> cancelJob(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId,
            HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "cancelJob|" + jobId);
        orchestrator.cancelJob(jobId);
        log.info("배포 Job {} 취소됨", jobId);
        return Map.of("jobId", jobId, "status", "CANCELLED");
    }

    /**
     * 특정 웨이브 취소.
     * 해당 웨이브에 배정된 에이전트들의 배포를 취소합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/waves/{wave}/cancel")
    @Operation(summary = "웨이브 취소", description = "지정된 Job의 특정 웨이브를 취소합니다.")
    @ApiResponse(responseCode = "200", description = "웨이브 취소 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> cancelWave(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId,
            @Parameter(description = "웨이브 번호 (1부터 시작)") @PathVariable int wave,
            HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "cancelWave|" + jobId + "|" + wave);
        orchestrator.cancelWave(jobId, wave);
        log.info("배포 Job {} Wave {} 취소됨", jobId, wave);
        return Map.of("jobId", jobId, "wave", wave, "status", "CANCELLED");
    }

    /**
     * 특정 웨이브 수동 시작.
     * 자동 진행이 비활성화된 경우 관리자가 수동으로 웨이브를 시작합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/waves/{wave}/start")
    @Operation(summary = "웨이브 수동 시작", description = "지정된 Job의 특정 웨이브를 수동으로 시작합니다.")
    @ApiResponse(responseCode = "200", description = "웨이브 시작 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> startWave(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId,
            @Parameter(description = "웨이브 번호") @PathVariable int wave,
            HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "startWave|" + jobId + "|" + wave);
        orchestrator.startWave(jobId, wave);
        log.info("배포 Job {} Wave {} 수동 시작됨", jobId, wave);
        return Map.of("jobId", jobId, "wave", wave, "status", "IN_PROGRESS");
    }

    /**
     * 실패한 에이전트 재시도.
     * wave를 지정하면 해당 웨이브만, 미지정이면 전체 실패 에이전트를 재시도합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/retry-failed")
    @Operation(
        summary = "실패 에이전트 재시도",
        description = "배포에 실패한 에이전트를 재시도합니다. wave를 지정하면 해당 웨이브만 재시도합니다."
    )
    @ApiResponse(responseCode = "200", description = "재시도 결과 반환")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> retryFailed(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Integer wave = null;
        if (body != null && body.get("wave") != null && !String.valueOf(body.get("wave")).isBlank()) {
            wave = parseRequiredInt(body.get("wave"), "wave");
        }
        requestSecurity.validateOrThrow(request, "retryFailed|" + jobId + "|" + wave);
        log.info("배포 Job {} 실패 에이전트 재시도 (wave={})", jobId, wave);
        return orchestrator.retryFailedAgents(jobId, wave);
    }

    /**
     * 이전 배포 버전으로 롤백.
     * rollbackFromJobId에 해당하는 Job의 파일을 다시 배포하는 새 Job을 생성합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/rollback")
    @Operation(
        summary = "배포 롤백",
        description = "지정된 jobId를 기준으로 rollbackFromJobId의 파일 버전으로 롤백합니다. 새 Job ID가 반환됩니다."
    )
    @ApiResponse(responseCode = "200", description = "롤백 Job 생성 성공")
    @ApiResponse(responseCode = "400", description = "rollbackFromJobId 누락")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> rollback(
            @Parameter(description = "현재 배포 작업 ID") @PathVariable Long jobId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Long rollbackFromJobId = parseRequiredLong(body.get("rollbackFromJobId"), "rollbackFromJobId");
        int waveSize = toInt(body.get("waveSize"), 20);
        boolean canaryOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("canaryOnly", false)));
        Long groupId = parseOptionalLong(body.get("groupId"), "groupId");

        requestSecurity.validateOrThrow(request,
                "rollback|" + jobId + "|" + rollbackFromJobId + "|" + waveSize + "|" + canaryOnly + "|" + groupId);

        FileDistributionJob rollbackJob = orchestrator.rollbackToJob(jobId, rollbackFromJobId, waveSize, canaryOnly, groupId);
        log.info("배포 Job {} 롤백 → rollbackFromJobId={}, 새 Job ID={}", jobId, rollbackFromJobId, rollbackJob.getId());
        return Map.of(
                "sourceJobId", jobId,
                "rollbackFromJobId", rollbackFromJobId,
                "newJobId", rollbackJob.getId(),
                "status", rollbackJob.getStatus()
        );
    }

    /**
     * 에이전트 배포 결과 보고.
     * 에이전트가 파일 수신 결과(성공/실패)를 서버에 보고합니다.
     * 결과에 따라 다음 웨이브 진행 여부가 결정됩니다.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/jobs/{jobId}/agents/{agentId}/result")
    @Operation(
        summary = "에이전트 배포 결과 보고",
        description = "에이전트가 파일 수신 성공/실패를 서버에 보고합니다. success=false 시 errorMessage를 포함하세요."
    )
    @ApiResponse(responseCode = "200", description = "결과 수신 완료")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public Map<String, Object> reportAgentResult(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId,
            @Parameter(description = "에이전트 ID (MAC 주소 또는 식별자)") @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        boolean success = Boolean.parseBoolean(String.valueOf(body.getOrDefault("success", false)));
        String errorMessage = body.get("errorMessage") == null ? null : String.valueOf(body.get("errorMessage"));
        requestSecurity.validateOrThrow(request, "reportAgentResult|" + jobId + "|" + agentId + "|" + success);
        orchestrator.reportAgentResult(jobId, agentId, success, errorMessage);
        if (!success) {
            log.warn("배포 Job {} 에이전트 {} 실패 보고: {}", jobId, agentId, errorMessage);
        }
        return Map.of("jobId", jobId, "agentId", agentId, "accepted", true);
    }

    /**
     * 배포 작업 상태 조회
     * @param jobId 조회할 Job ID
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "배포 작업 상태 조회", description = "지정된 Job의 현재 상태, 진행 웨이브, 에이전트별 결과를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "Job 상태 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    @ApiResponse(responseCode = "404", description = "Job을 찾을 수 없음")
    public Map<String, Object> getJobStatus(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId) {
        return orchestrator.getJobStatus(jobId);
    }

    /**
     * 배포 작업 이벤트 목록 조회
     * @param jobId 조회할 Job ID
     * @return 이벤트 타입, 상세 JSON, 발생 시각 목록
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/jobs/{jobId}/events")
    @Operation(summary = "배포 이벤트 목록 조회", description = "Job의 이벤트 로그(웨이브 시작/완료, 에이전트 결과 등)를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "이벤트 목록 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
    public List<Map<String, Object>> getEvents(
            @Parameter(description = "배포 작업 ID") @PathVariable Long jobId) {
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

    /**
     * 최근 배포 작업 목록 조회
     * 최근 생성된 Job 목록을 반환합니다.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/jobs")
    @Operation(summary = "최근 배포 작업 목록 조회", description = "최근 생성된 배포 Job 목록을 최신 순으로 조회합니다.")
    @ApiResponse(responseCode = "200", description = "배포 작업 목록 조회 성공")
    @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음")
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

    private Double parseOptionalDouble(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a number");
        }
    }
}
