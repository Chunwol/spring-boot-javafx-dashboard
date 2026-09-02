package com.example.backend.controller.script;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.service.script.ScriptArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 스크립트 관리 API
 * PowerShell 및 다른 스크립트 파일을 업로드 및 다운로드합니다.
 *
 * <h2>대용량 파일 다운로드 최적화 (X-Accel-Redirect)</h2>
 * <p>{@code app.nginx.accel-redirect-enabled=true} 설정 시 Spring Boot가 파일을 직접 스트리밍하는 대신
 * Nginx에게 {@code X-Accel-Redirect} 헤더로 서빙을 위임한다.</p>
 * <ul>
 *   <li>Spring Boot: 헤더만 반환, Tomcat 스레드 즉시 반환</li>
 *   <li>Nginx: sendfile(2) 시스템 콜로 커널 복사 — JVM 메모리 무관</li>
 *   <li>850대 동시 다운로드 시 JVM 힙 압박 제거</li>
 * </ul>
 * <p><b>Nginx 설정 필요 (Docker 및 NAS Nginx 공통):</b></p>
 * <pre>
 * location /internal-uploads/ {
 *     internal;
 *     alias /app/uploads/;   # NAS의 경우 실제 파일 경로로 변경
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Scripts", description = "스크립트 관리 API")
public class ScriptController {

    private static final Logger log = LoggerFactory.getLogger(ScriptController.class);
    private static final Pattern SAFE_UPLOAD_ID = Pattern.compile("^[A-Za-z0-9_-]{8,120}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final int MAX_TOTAL_CHUNKS = 200_000;
    private static final int MAX_MISSING_PREVIEW = 100;
    private static final int LOCK_STRIPE_COUNT = 1024;
    /** 에이전트 수집 파일 총량 기본 제한: 30GB */
    private static final long DEFAULT_COLLECT_MAX_BYTES = 30L * 1024 * 1024 * 1024;
    private static final String ADMIN_UPLOAD = "ADMIN_UPLOAD";
    private static final String AGENT_COLLECTION = "AGENT_COLLECTION";
    private static final long DEFAULT_CHUNK_MAX_BYTES = 16L * 1024 * 1024;
    private static final long DEFAULT_CHUNK_STAGING_MAX_BYTES = 60L * 1024 * 1024 * 1024;
    private static final long DEFAULT_CHUNK_TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final ScriptArtifactService scriptArtifactService;
    private final ObjectMapper objectMapper;
    private final Object[] uploadLockStripes = IntStream.range(0, LOCK_STRIPE_COUNT)
            .mapToObj(i -> new Object())
            .toArray();
    private final AtomicLong stagedChunkBytes = new AtomicLong();

    // 청크 메타데이터/매니페스트는 파일 기반(meta.json, _chunk-manifests/*.json)으로 유지한다.

    @Value("${app.scripts.dir:storage/scripts}")
    private String scriptsDir;

    @Value("${app.collect.max-total-bytes:" + DEFAULT_COLLECT_MAX_BYTES + "}")
    private long collectMaxTotalBytes;

    @Value("${app.scripts.chunk-max-bytes:" + DEFAULT_CHUNK_MAX_BYTES + "}")
    private long chunkMaxBytes;

    @Value("${app.scripts.chunk-staging-max-bytes:" + DEFAULT_CHUNK_STAGING_MAX_BYTES + "}")
    private long chunkStagingMaxBytes;

    @Value("${app.scripts.chunk-ttl-ms:" + DEFAULT_CHUNK_TTL_MILLIS + "}")
    private long chunkTtlMillis;

    /**
     * true이면 Nginx X-Accel-Redirect로 파일 서빙을 Nginx에 위임한다.
     * Nginx에 internal location이 설정된 경우에만 활성화할 것.
     */
    @Value("${app.nginx.accel-redirect-enabled:false}")
    private boolean nginxAccelRedirectEnabled;

    /**
     * X-Accel-Redirect 헤더에 사용할 Nginx internal location 경로.
     * Nginx 설정의 alias 경로와 일치해야 한다.
     */
    @Value("${app.nginx.internal-location:/internal-uploads/}")
    private String nginxInternalLocation;

    public ScriptController(ScriptArtifactService scriptArtifactService, ObjectMapper objectMapper) {
        this.scriptArtifactService = scriptArtifactService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            Path base = Path.of(scriptsDir).toAbsolutePath().normalize();
            Files.createDirectories(base);
            stagedChunkBytes.set(stagedPayloadSize(base.resolve("_chunks")));
            log.info("ScriptController initialized, app.scripts.dir={}", base);
        } catch (Exception ex) {
            log.warn("Failed to resolve app.scripts.dir='{}' : {}", scriptsDir, ex.toString());
        }
    }

    /**
     * 스크립트 파일 업로드
     */
    @PostMapping(value = "/scripts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "스크립트 업로드",
        description = "PowerShell 및 다른 스크립트 파일을 업로드합니다. SHA256 체크섬도 동시에 진행됩니다."
    )
    @ApiResponse(responseCode = "200", description = "{\"scriptId\":\"...\", \"sha256\":\"...\"}")
    public ResponseEntity<?> upload(
            @Parameter(description = "업로드할 스크립트 파일") @RequestPart("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file required"));
        }

        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "script.ps1";
        String stored = UUID.randomUUID() + "-" + original.replaceAll("[^a-zA-Z0-9._-]", "_");

        Path dir = Path.of(scriptsDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(stored);

        // compute sha256 while writing
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[8192];
            int r;
            try (var out = Files.newOutputStream(target)) {
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                }
            }
        }
        String sha256 = HexFormat.of().formatHex(md.digest());

        // de-dup by sha256 (optional)
        ScriptArtifact existing = scriptArtifactService.findBySha256AndKind(sha256, ADMIN_UPLOAD);
        if (existing != null) {
            // keep existing artifact, remove newly written file
            Files.deleteIfExists(target);
            return ResponseEntity.ok(Map.of(
                    "id", existing.getId(),
                    "sha256", existing.getSha256(),
                    "downloadUrl", "/api/v1/scripts/" + existing.getId() + "/download"
            ));
        }

        ScriptArtifact a = new ScriptArtifact();
        a.setOriginalFilename(original);
        a.setStoredFilename(stored);
        a.setSha256(sha256);
        a.setSizeBytes(file.getSize());
        a.setContentType(file.getContentType());
        a.setArtifactKind(ADMIN_UPLOAD);
        a.setCreatedAt(LocalDateTime.now());
        ScriptArtifact saved;
        try {
            saved = scriptArtifactService.save(a);
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw ex;
        }

        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "sha256", saved.getSha256(),
                "downloadUrl", "/api/v1/scripts/" + saved.getId() + "/download"
        ));
    }

    /**
     * 에이전트가 수집한 파일(주로 zip)을 업로드한다.
     * - AgentTokenInterceptor로 인증
     * - 저장/중복해시 처리 로직은 일반 스크립트 업로드와 동일
     */
    @PostMapping(value = "/agent/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "에이전트 수집 파일 업로드",
        description = "에이전트가 원격 PC에서 수집한 파일(압축본)을 업로드합니다."
    )
    public ResponseEntity<?> uploadFromAgent(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "originalFilename", required = false) String originalFilename,
            @RequestParam(value = "taskId", required = false) Long taskId,
            HttpServletRequest request) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file required"));
        }
        // 30GB 총량 제한 확인
        ResponseEntity<?> capacityError = checkCollectCapacity(file.getSize());
        if (capacityError != null) return capacityError;

        String original = StringUtils.hasText(originalFilename)
                ? originalFilename
                : (StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "collected.zip");
        String stored = UUID.randomUUID() + "-" + original.replaceAll("[^a-zA-Z0-9._-]", "_");

        Path dir = Path.of(scriptsDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(stored);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[8192];
            int r;
            try (var out = Files.newOutputStream(target)) {
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                }
            }
        }
        String sha256 = HexFormat.of().formatHex(md.digest());

        ScriptArtifact existing = scriptArtifactService.findBySha256AndKind(sha256, AGENT_COLLECTION);
        if (existing != null) {
            Files.deleteIfExists(target);
            return ResponseEntity.ok(Map.of(
                    "id", existing.getId(),
                    "sha256", existing.getSha256(),
                    "taskId", taskId,
                    "downloadUrl", "/api/v1/agent/uploads/" + existing.getId() + "/download"
            ));
        }

        ScriptArtifact a = new ScriptArtifact();
        a.setOriginalFilename(original);
        a.setStoredFilename(stored);
        a.setSha256(sha256);
        a.setSizeBytes(file.getSize());
        a.setContentType(file.getContentType());
        a.setArtifactKind(AGENT_COLLECTION);
        a.setOwnerMac(agentMac(request));
        a.setCreatedAt(LocalDateTime.now());
        ScriptArtifact saved;
        try {
            saved = scriptArtifactService.save(a);
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw ex;
        }

        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "sha256", saved.getSha256(),
                "taskId", taskId,
                "downloadUrl", "/api/v1/agent/uploads/" + saved.getId() + "/download"
        ));
    }


    /**
     * 대용량 파일 청크 업로드 (1단계)
     * - 각 청크는 별도 요청으로 업로드
     * - 모든 청크 업로드 후 assemble 호출 시 파일 조립 및 해시/매니페스트 생성
     * - 재전송(resume)을 위해 uploadId 단위 메타데이터를 유지한다.
     */
    @PostMapping(value = "/file-chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "대용량 파일 청크 업로드",
        description = "파일을 여러 청크로 나누어 업로드합니다. 각 청크는 순번(chunkIndex)과 전체 청크 수(totalChunks), 파일 식별자(uploadId)와 함께 전송되어야 합니다."
    )
    public ResponseEntity<?> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("chunkHash") String chunkHash,
            @RequestPart("file") MultipartFile chunkFile) throws Exception {
        if (!isValidUploadId(uploadId)) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_ID", "uploadId 형식이 올바르지 않습니다.", false);
        }
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_TOTAL_CHUNKS", "totalChunks 범위가 올바르지 않습니다.", false);
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_INDEX", "chunkIndex는 0 이상 totalChunks 미만이어야 합니다.", false);
        }
        String normalizedChunkHash = normalizeChunkHash(chunkHash);
        if (normalizedChunkHash == null) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_HASH", "chunkHash는 SHA-256 64자리 hex여야 합니다.", false);
        }
        if (chunkFile == null || chunkFile.isEmpty()) {
            return chunkError(HttpStatus.BAD_REQUEST, "EMPTY_CHUNK", "file required", false);
        }
        if (chunkFile.getSize() > Math.max(1, chunkMaxBytes)) {
            return chunkError(HttpStatus.PAYLOAD_TOO_LARGE, "CHUNK_TOO_LARGE",
                    "청크 크기가 서버 한도를 초과합니다.", false,
                    Map.of("maxBytes", chunkMaxBytes, "receivedBytes", chunkFile.getSize()));
        }

        synchronized (lockForUploadId(uploadId)) {
                Path uploadDir = resolveChunkUploadDir(uploadId, true);
                Path metadataPath = uploadDir.resolve("meta.json");

                ChunkUploadMetadata metadata = readMetadata(metadataPath);
                if (metadata == null) {
                    metadata = new ChunkUploadMetadata();
                    metadata.uploadId = uploadId;
                    metadata.totalChunks = totalChunks;
                    metadata.createdAtEpochMs = System.currentTimeMillis();
                } else if (metadata.totalChunks != totalChunks) {
                    return chunkError(HttpStatus.CONFLICT, "TOTAL_CHUNKS_MISMATCH", "기존 업로드의 totalChunks와 일치하지 않습니다.", false, Map.of(
                            "expectedTotalChunks", metadata.totalChunks,
                            "requestedTotalChunks", totalChunks
                    ));
                }

                Path chunkPath = resolveChunkPath(uploadDir, chunkIndex);
                boolean resumed = false;
                String knownHash = metadata.chunkHashes.get(chunkIndex);
                Long knownSize = metadata.chunkSizes.get(chunkIndex);
                if (Files.exists(chunkPath)
                        && normalizedChunkHash.equals(knownHash)
                        && knownSize != null
                        && knownSize == chunkFile.getSize()) {
                    resumed = true;
                } else {
                    long previousSize = Files.exists(chunkPath) ? Files.size(chunkPath) : 0L;
                    if (!reserveStagingBytes(chunkFile.getSize())) {
                        return chunkError(HttpStatus.INSUFFICIENT_STORAGE, "CHUNK_STAGING_LIMIT_EXCEEDED",
                                "임시 청크 저장 공간이 가득 찼습니다. 잠시 후 다시 시도하세요.", true,
                                Map.of("maxBytes", chunkStagingMaxBytes));
                    }
                    boolean committed = false;
                    Path tempChunk = Files.createTempFile(uploadDir, "chunk-" + chunkIndex + "-", ".tmp");
                    try {
                        String actualHash = saveChunkWithSha256(chunkFile, tempChunk);
                        if (!normalizedChunkHash.equals(actualHash)) {
                            Files.deleteIfExists(tempChunk);
                            return chunkError(HttpStatus.BAD_REQUEST, "CHUNK_HASH_MISMATCH", "청크 해시가 일치하지 않습니다.", true, Map.of(
                                    "expectedHash", normalizedChunkHash,
                                    "actualHash", actualHash,
                                    "chunkIndex", chunkIndex
                            ));
                        }
                        Files.move(tempChunk, chunkPath, StandardCopyOption.REPLACE_EXISTING);
                        stagedChunkBytes.addAndGet(-previousSize);
                        committed = true;
                    } finally {
                        Files.deleteIfExists(tempChunk);
                        if (!committed) stagedChunkBytes.addAndGet(-chunkFile.getSize());
                    }
                }

                metadata.updatedAtEpochMs = System.currentTimeMillis();
                metadata.chunkHashes.put(chunkIndex, normalizedChunkHash);
                metadata.chunkSizes.put(chunkIndex, chunkFile.getSize());
                writeMetadata(metadataPath, metadata);

                Set<Integer> uploadedIndexes = collectUploadedIndexes(uploadDir);
                List<Integer> missingPreview = buildMissingPreview(totalChunks, uploadedIndexes);

                return ResponseEntity.ok(Map.of(
                        "uploadId", uploadId,
                        "chunkIndex", chunkIndex,
                        "totalChunks", totalChunks,
                        "uploadedChunks", uploadedIndexes.size(),
                        "missingChunks", Math.max(0, totalChunks - uploadedIndexes.size()),
                        "missingChunkIndexesPreview", missingPreview,
                        "resumed", resumed,
                        "complete", uploadedIndexes.size() == totalChunks
                ));
        }
    }

    /**
     * 에이전트 전용 대용량 파일 청크 업로드.
     * AgentTokenInterceptor가 X-Agent-Token을 검증한다.
     */
    @PostMapping(value = "/agent/file-chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "에이전트 대용량 파일 청크 업로드",
            description = "에이전트가 수집한 대용량 파일을 청크 단위로 업로드합니다."
    )
    public ResponseEntity<?> uploadChunkFromAgent(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("chunkHash") String chunkHash,
            @RequestPart("file") MultipartFile chunkFile) throws Exception {
        return uploadChunk(uploadId, chunkIndex, totalChunks, chunkHash, chunkFile);
    }

    /**
     * 업로드 완료 후 파일 조립 및 매니페스트/최종 해시 생성 (1단계)
     * - 모든 청크 업로드 후 호출
     * - 청크 순서대로 병합, 전체 파일 해시 계산, 매니페스트 생성
     * - DB/스토리지에 최종 파일 및 매니페스트 기록
     */
    @PostMapping("/file-chunks/assemble")
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "청크 조립 및 매니페스트 생성",
        description = "모든 청크 업로드 후 호출. 서버가 청크를 병합하여 최종 파일을 생성하고, 전체 해시 및 매니페스트를 기록합니다."
    )
    public ResponseEntity<?> assembleChunks(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("originalFilename") String originalFilename,
            @RequestParam("totalChunks") int totalChunks) throws Exception {
        return assembleChunksInternal(uploadId, originalFilename, totalChunks, ADMIN_UPLOAD, null);
    }

    private ResponseEntity<?> assembleChunksInternal(
            String uploadId,
            String originalFilename,
            int totalChunks,
            String artifactKind,
            String ownerMac) throws Exception {
        if (!isValidUploadId(uploadId)) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_ID", "uploadId 형식이 올바르지 않습니다.", false);
        }
        if (!StringUtils.hasText(originalFilename)) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "originalFilename required", false);
        }
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_TOTAL_CHUNKS", "totalChunks 범위가 올바르지 않습니다.", false);
        }

        synchronized (lockForUploadId(uploadId)) {
                Path uploadDir = resolveChunkUploadDir(uploadId, false);
                if (!Files.exists(uploadDir) || !Files.isDirectory(uploadDir)) {
                    return chunkError(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "uploadId에 해당하는 청크 업로드가 없습니다.", false);
                }

                Path metadataPath = uploadDir.resolve("meta.json");
                ChunkUploadMetadata metadata = readMetadata(metadataPath);
                int effectiveTotalChunks = metadata != null ? metadata.totalChunks : totalChunks;
                if (metadata != null && metadata.totalChunks != totalChunks) {
                    return chunkError(HttpStatus.CONFLICT, "TOTAL_CHUNKS_MISMATCH", "metadata totalChunks와 assemble 요청값이 일치하지 않습니다.", false, Map.of(
                            "metadataTotalChunks", metadata.totalChunks,
                            "requestedTotalChunks", totalChunks
                    ));
                }

                Set<Integer> uploadedIndexes = collectUploadedIndexes(uploadDir);
                List<Integer> missingPreview = buildMissingPreview(effectiveTotalChunks, uploadedIndexes);
                if (!missingPreview.isEmpty() || uploadedIndexes.size() != effectiveTotalChunks) {
                    return chunkError(HttpStatus.CONFLICT, "MISSING_CHUNKS", "누락된 청크가 있어 조립할 수 없습니다.", true, Map.of(
                            "totalChunks", effectiveTotalChunks,
                            "uploadedChunks", uploadedIndexes.size(),
                            "missingChunks", Math.max(0, effectiveTotalChunks - uploadedIndexes.size()),
                            "missingChunkIndexesPreview", missingPreview
                    ));
                }

                Path baseDir = resolveScriptsBaseDir();
                Path assembledTemp = Files.createTempFile(baseDir, "assembled-" + uploadId + "-", ".tmp");
                long assembledSizeBytes = 0L;
                String finalSha256;

                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1024 * 1024];
                try (OutputStream out = Files.newOutputStream(assembledTemp)) {
                    for (int i = 0; i < effectiveTotalChunks; i++) {
                        Path chunkPath = resolveChunkPath(uploadDir, i);
                        if (!Files.exists(chunkPath)) {
                            Files.deleteIfExists(assembledTemp);
                            return chunkError(HttpStatus.CONFLICT, "MISSING_CHUNK_DURING_ASSEMBLE", "조립 중 누락 청크가 발견되었습니다.", true, Map.of(
                                    "missingChunkIndex", i
                            ));
                        }
                        try (InputStream in = Files.newInputStream(chunkPath)) {
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                                md.update(buffer, 0, read);
                                assembledSizeBytes += read;
                            }
                        }
                    }
                }
                finalSha256 = HexFormat.of().formatHex(md.digest());

                ScriptArtifact existing = scriptArtifactService.findBySha256AndKind(finalSha256, artifactKind);
                if (existing != null) {
                    Files.deleteIfExists(assembledTemp);
                    writeManifest(uploadId, metadata, effectiveTotalChunks, finalSha256, assembledSizeBytes, existing.getId(), existing.getStoredFilename(), true);
                    cleanupUploadDir(uploadDir);
                    return ResponseEntity.ok(Map.of(
                            "id", existing.getId(),
                            "sha256", existing.getSha256(),
                            "sizeBytes", existing.getSizeBytes(),
                            "downloadUrl", "/api/v1/scripts/" + existing.getId() + "/download",
                            "deduplicated", true,
                            "manifestKey", "_chunk-manifests/" + uploadId + ".json"
                    ));
                }

                String safeOriginal = sanitizeFilename(originalFilename);
                String stored = UUID.randomUUID() + "-" + safeOriginal;
                Path target = baseDir.resolve(stored).normalize();
                if (!target.startsWith(baseDir)) {
                    Files.deleteIfExists(assembledTemp);
                    return chunkError(HttpStatus.BAD_REQUEST, "INVALID_TARGET_PATH", "저장 경로가 유효하지 않습니다.", false);
                }
                Files.move(assembledTemp, target, StandardCopyOption.REPLACE_EXISTING);

                ScriptArtifact a = new ScriptArtifact();
                a.setOriginalFilename(originalFilename);
                a.setStoredFilename(stored);
                a.setSha256(finalSha256);
                a.setSizeBytes(assembledSizeBytes);
                a.setContentType("application/octet-stream");
                a.setArtifactKind(artifactKind);
                a.setOwnerMac(ownerMac);
                a.setCreatedAt(LocalDateTime.now());
                ScriptArtifact saved;
                try {
                    saved = scriptArtifactService.save(a);
                } catch (Exception ex) {
                    Files.deleteIfExists(target);
                    throw ex;
                }

                writeManifest(uploadId, metadata, effectiveTotalChunks, finalSha256, assembledSizeBytes, saved.getId(), stored, false);
                cleanupUploadDir(uploadDir);

                return ResponseEntity.ok(Map.of(
                        "id", saved.getId(),
                        "sha256", saved.getSha256(),
                        "sizeBytes", saved.getSizeBytes(),
                        "downloadUrl", "/api/v1/scripts/" + saved.getId() + "/download",
                        "deduplicated", false,
                        "manifestKey", "_chunk-manifests/" + uploadId + ".json"
                ));
        }
    }

    /**
     * 에이전트 전용 청크 조립.
     * 결과 다운로드 URL을 관리자 다운로드 엔드포인트(/agent/uploads/{id}/download)로 표준화한다.
     */
    @PostMapping("/agent/file-chunks/assemble")
    @Operation(
            summary = "에이전트 청크 조립",
            description = "에이전트 청크 업로드 완료 후 최종 파일을 조립하고 관리자 다운로드 URL을 반환합니다."
    )
    public ResponseEntity<?> assembleChunksFromAgent(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("originalFilename") String originalFilename,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(value = "taskId", required = false) Long taskId,
            HttpServletRequest request) throws Exception {
        // 청크 총합 크기로 용량 사전 확인
        long estimatedSize = estimateChunksDirSize(uploadId);
        ResponseEntity<?> capacityError = checkCollectCapacity(estimatedSize);
        if (capacityError != null) return capacityError;

        ResponseEntity<?> baseResponse = assembleChunksInternal(
                uploadId, originalFilename, totalChunks, AGENT_COLLECTION, agentMac(request));
        return remapAsAgentDownload(baseResponse, taskId);
    }

    @GetMapping("/scripts/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id,
                                      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Optional<ScriptArtifact> opt = scriptArtifactService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ScriptArtifact a = opt.get();

        if (a.isAgentCollection()) {
            return ResponseEntity.notFound().build();
        }

        // Path traversal 방지: resolve 후 반드시 scripts 디렉터리 내부인지 검증
        Path baseDir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Path file = baseDir.resolve(a.getStoredFilename()).normalize();
        if (!file.startsWith(baseDir)) return ResponseEntity.status(400).build();
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        String filename = a.getOriginalFilename() == null ? ("script-" + id + ".ps1") : a.getOriginalFilename();
        return buildDownloadResponse(file, filename, a.getStoredFilename(), a.getSha256(), rangeHeader);
    }

    /**
     * 관리자용: 에이전트가 업로드한 수집 파일 다운로드.
     * X-Accel-Redirect 활성화 시 Nginx가 직접 서빙한다.
     */
    @GetMapping("/agent/uploads/{id}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "수집 파일 다운로드", description = "에이전트가 업로드한 수집 파일을 관리자가 다운로드합니다.")
    public ResponseEntity<?> downloadCollected(@PathVariable Long id,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Optional<ScriptArtifact> opt = scriptArtifactService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ScriptArtifact a = opt.get();
        if (!a.isAgentCollection()) return ResponseEntity.notFound().build();

        Path baseDir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Path file = baseDir.resolve(a.getStoredFilename()).normalize();
        if (!file.startsWith(baseDir)) return ResponseEntity.status(400).build();
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        String filename = a.getOriginalFilename() == null ? ("collected-" + id + ".zip") : a.getOriginalFilename();
        return buildDownloadResponse(file, filename, a.getStoredFilename(), a.getSha256(), rangeHeader);
    }

    private ResponseEntity<?> buildDownloadResponse(Path file,
                                                    String filename,
                                                    String storedFilename,
                                                    String sha256Value,
                                                    String rangeHeader) {
        String safeFilename = filename.replaceAll("[^\\w.\\-]", "_");
        String sha256 = sha256Value != null ? sha256Value : "";

        if (nginxAccelRedirectEnabled) {
            return ResponseEntity.ok()
                    .header("X-Accel-Redirect", nginxInternalLocation + storedFilename)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("X-Script-SHA256", sha256)
                    .build();
        }

        try {
            long fileSize = Files.size(file);
            RangeSpec range = parseRangeHeader(rangeHeader, fileSize);
            if (range != null) {
                InputStream in = Files.newInputStream(file);
                in.skipNBytes(range.start);
                Resource rangeResource = new InputStreamResource(new BoundedInputStream(in, range.length())) {
                    @Override
                    public long contentLength() {
                        return range.length();
                    }
                };
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE,
                                "bytes " + range.start + "-" + range.end + "/" + fileSize)
                        .header("X-Script-SHA256", sha256)
                        .contentLength(range.length())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(rangeResource);
            }

            Resource res = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("X-Script-SHA256", sha256)
                    .contentLength(fileSize)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(res);
        } catch (InvalidRangeException ex) {
            long size = ex.fileSize;
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(0L, size))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isValidUploadId(String uploadId) {
        return uploadId != null && SAFE_UPLOAD_ID.matcher(uploadId).matches();
    }

    private RangeSpec parseRangeHeader(String rangeHeader, long fileSize) {
        if (!StringUtils.hasText(rangeHeader)) {
            return null;
        }
        if (fileSize <= 0) {
            throw new InvalidRangeException(fileSize);
        }
        String value = rangeHeader.trim();
        if (!value.startsWith("bytes=") || value.contains(",")) {
            throw new InvalidRangeException(fileSize);
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new InvalidRangeException(fileSize);
        }

        try {
            long start;
            long end;
            String startText = spec.substring(0, dash).trim();
            String endText = spec.substring(dash + 1).trim();
            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    throw new InvalidRangeException(fileSize);
                }
                start = Math.max(0L, fileSize - suffixLength);
                end = fileSize - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? fileSize - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= fileSize || end < start) {
                throw new InvalidRangeException(fileSize);
            }
            return new RangeSpec(start, Math.min(end, fileSize - 1));
        } catch (NumberFormatException ex) {
            throw new InvalidRangeException(fileSize);
        }
    }

    private static final class RangeSpec {
        private final long start;
        private final long end;

        private RangeSpec(long start, long end) {
            this.start = start;
            this.end = end;
        }

        private long length() {
            return end - start + 1;
        }
    }

    private static final class InvalidRangeException extends RuntimeException {
        private final long fileSize;

        private InvalidRangeException(long fileSize) {
            this.fileSize = fileSize;
        }
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = Math.max(0L, limit);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = delegate.read(b, off, (int) Math.min(len, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private String normalizeChunkHash(String chunkHash) {
        if (!StringUtils.hasText(chunkHash)) {
            return null;
        }
        String normalized = chunkHash.trim().toLowerCase(Locale.ROOT);
        return SHA256_HEX.matcher(normalized).matches() ? normalized : null;
    }

    private String sanitizeFilename(String filename) {
        String normalized = StringUtils.hasText(filename) ? filename.trim() : "uploaded.bin";
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private Object lockForUploadId(String uploadId) {
        int idx = Math.floorMod(uploadId.hashCode(), LOCK_STRIPE_COUNT);
        return uploadLockStripes[idx];
    }

    private Path resolveScriptsBaseDir() throws IOException {
        Path baseDir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        return baseDir;
    }

    private Path resolveChunkRootDir() throws IOException {
        Path baseDir = resolveScriptsBaseDir();
        Path chunkRoot = baseDir.resolve("_chunks").normalize();
        if (!chunkRoot.startsWith(baseDir)) {
            throw new IllegalArgumentException("chunk root path is invalid");
        }
        Files.createDirectories(chunkRoot);
        return chunkRoot;
    }

    private Path resolveChunkUploadDir(String uploadId, boolean createIfMissing) throws IOException {
        Path chunkRoot = resolveChunkRootDir();
        Path uploadDir = chunkRoot.resolve(uploadId).normalize();
        if (!uploadDir.startsWith(chunkRoot)) {
            throw new IllegalArgumentException("upload path is invalid");
        }
        if (createIfMissing) {
            Files.createDirectories(uploadDir);
        }
        return uploadDir;
    }

    private Path resolveChunkPath(Path uploadDir, int chunkIndex) {
        String fileName = String.format("%08d.part", chunkIndex);
        return uploadDir.resolve(fileName).normalize();
    }

    private String saveChunkWithSha256(MultipartFile chunkFile, Path target) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = chunkFile.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                md.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest()).toLowerCase(Locale.ROOT);
    }

    private ChunkUploadMetadata readMetadata(Path metadataPath) throws IOException {
        if (!Files.exists(metadataPath)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(metadataPath)) {
            ChunkUploadMetadata metadata = objectMapper.readValue(in, ChunkUploadMetadata.class);
            if (metadata.chunkHashes == null) {
                metadata.chunkHashes = new HashMap<>();
            }
            if (metadata.chunkSizes == null) {
                metadata.chunkSizes = new HashMap<>();
            }
            return metadata;
        }
    }

    private void writeMetadata(Path metadataPath, ChunkUploadMetadata metadata) throws IOException {
        Path tempPath = Files.createTempFile(metadataPath.getParent(), "meta-", ".tmp");
        try (OutputStream out = Files.newOutputStream(tempPath)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, metadata);
        }
        Files.move(tempPath, metadataPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private Set<Integer> collectUploadedIndexes(Path uploadDir) throws IOException {
        Set<Integer> indexes = new java.util.TreeSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDir, "*.part")) {
            for (Path chunkPath : stream) {
                String name = chunkPath.getFileName().toString();
                if (!name.endsWith(".part")) {
                    continue;
                }
                String indexText = name.substring(0, name.length() - ".part".length());
                try {
                    indexes.add(Integer.parseInt(indexText));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return indexes;
    }

    private List<Integer> buildMissingPreview(int totalChunks, Set<Integer> uploadedIndexes) {
        List<Integer> preview = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (uploadedIndexes.contains(i)) {
                continue;
            }
            preview.add(i);
            if (preview.size() >= MAX_MISSING_PREVIEW) {
                break;
            }
        }
        return preview;
    }

    private void writeManifest(String uploadId,
                               ChunkUploadMetadata metadata,
                               int totalChunks,
                               String finalSha256,
                               long sizeBytes,
                               Long artifactId,
                               String storedFilename,
                               boolean deduplicated) throws IOException {
        Path baseDir = resolveScriptsBaseDir();
        Path manifestRoot = baseDir.resolve("_chunk-manifests").normalize();
        if (!manifestRoot.startsWith(baseDir)) {
            throw new IllegalArgumentException("manifest root path is invalid");
        }
        Files.createDirectories(manifestRoot);

        Path manifestPath = manifestRoot.resolve(uploadId + ".json").normalize();
        if (!manifestPath.startsWith(manifestRoot)) {
            throw new IllegalArgumentException("manifest path is invalid");
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("uploadId", uploadId);
        manifest.put("totalChunks", totalChunks);
        manifest.put("sha256", finalSha256);
        manifest.put("sizeBytes", sizeBytes);
        manifest.put("artifactId", artifactId);
        manifest.put("storedFilename", storedFilename);
        manifest.put("deduplicated", deduplicated);
        manifest.put("assembledAt", LocalDateTime.now().toString());
        if (metadata != null) {
            manifest.put("createdAtEpochMs", metadata.createdAtEpochMs);
            manifest.put("updatedAtEpochMs", metadata.updatedAtEpochMs);
            manifest.put("chunkHashes", metadata.chunkHashes);
            manifest.put("chunkSizes", metadata.chunkSizes);
        }

        Path tempPath = Files.createTempFile(manifestRoot, uploadId + "-", ".manifest.tmp");
        try (OutputStream out = Files.newOutputStream(tempPath)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, manifest);
        }
        Files.move(tempPath, manifestPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupUploadDir(Path uploadDir) {
        if (uploadDir == null || !Files.exists(uploadDir)) {
            return;
        }
        long releasedBytes = stagedPayloadSize(uploadDir);
        try (var walk = Files.walk(uploadDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("chunk temp cleanup skip: {}", path, e);
                }
            });
            stagedChunkBytes.updateAndGet(current -> Math.max(0L, current - releasedBytes));
        } catch (IOException e) {
            log.debug("chunk upload dir cleanup failed: {}", uploadDir, e);
        }
    }

    @Scheduled(fixedDelayString = "${app.scripts.chunk-cleanup-interval-ms:3600000}")
    public void cleanupStaleChunkUploads() {
        Path root = Path.of(scriptsDir, "_chunks").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        long cutoff = System.currentTimeMillis() - Math.max(60_000L, chunkTtlMillis);
        try (DirectoryStream<Path> uploads = Files.newDirectoryStream(root)) {
            for (Path uploadDir : uploads) {
                if (!Files.isDirectory(uploadDir)) continue;
                ChunkUploadMetadata metadata = readMetadata(uploadDir.resolve("meta.json"));
                long updatedAt = metadata == null ? Files.getLastModifiedTime(uploadDir).toMillis()
                        : Math.max(metadata.createdAtEpochMs, metadata.updatedAtEpochMs);
                if (updatedAt >= cutoff) continue;
                synchronized (lockForUploadId(uploadDir.getFileName().toString())) {
                    cleanupUploadDir(uploadDir);
                }
            }
        } catch (Exception exception) {
            log.warn("stale chunk cleanup failed: {}", exception.getMessage());
        }
    }

    private boolean reserveStagingBytes(long bytes) {
        long requested = Math.max(0L, bytes);
        while (true) {
            long current = stagedChunkBytes.get();
            long limit = Math.max(DEFAULT_CHUNK_MAX_BYTES, chunkStagingMaxBytes);
            if (requested > limit - current) return false;
            if (stagedChunkBytes.compareAndSet(current, current + requested)) return true;
        }
    }

    private long directorySize(Path directory) {
        if (directory == null || !Files.exists(directory)) return 0L;
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (IOException ignored) { return 0L; }
                    })
                    .sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private long stagedPayloadSize(Path directory) {
        if (directory == null || !Files.exists(directory)) return 0L;
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".part") || name.endsWith(".tmp");
                    })
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (IOException ignored) { return 0L; }
                    })
                    .sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private ResponseEntity<Map<String, Object>> chunkError(HttpStatus status,
                                                           String code,
                                                           String message,
                                                           boolean retryable) {
        return chunkError(status, code, message, retryable, Map.of());
    }

    private ResponseEntity<Map<String, Object>> chunkError(HttpStatus status,
                                                           String code,
                                                           String message,
                                                           boolean retryable,
                                                           Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", retryable);
        body.put("timestamp", LocalDateTime.now().toString());
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<?> remapAsAgentDownload(ResponseEntity<?> response, Long taskId) {
        if (response == null || response.getBody() == null) {
            return response;
        }
        if (!(response.getBody() instanceof Map<?, ?> source)) {
            return response;
        }
        Object id = source.get("id");
        if (id == null) {
            return response;
        }
        String artifactId = String.valueOf(id).trim();
        if (artifactId.isBlank()) {
            return response;
        }

        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            mapped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        mapped.put("downloadUrl", "/api/v1/agent/uploads/" + artifactId + "/download");
        if (taskId != null) {
            mapped.put("taskId", taskId);
        }
        return ResponseEntity.status(response.getStatusCode()).body(mapped);
    }

    private String agentMac(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("agentMac");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 에이전트 수집 파일 총량이 한도를 초과하면 507 응답, 아니면 null 반환.
     */
    private ResponseEntity<?> checkCollectCapacity(long additionalBytes) {
        long used = scriptArtifactService.getTotalSizeBytes();
        if (used + additionalBytes > collectMaxTotalBytes) {
            long usedMb = used / (1024 * 1024);
            long maxMb = collectMaxTotalBytes / (1024 * 1024);
            return ResponseEntity.status(507).body(Map.of(
                    "error", "STORAGE_LIMIT_EXCEEDED",
                    "message", "저장 공간 초과: 사용=" + usedMb + "MB / 한도=" + maxMb
                            + "MB. 관리자에서 오래된 파일을 삭제하세요.",
                    "retryable", false
            ));
        }
        return null;
    }

    /**
     * 청크 임시 디렉터리의 합산 크기를 반환한다 (조립 전 용량 추정용).
     * 디렉터리가 없거나 접근 불가이면 0 반환.
     */
    private long estimateChunksDirSize(String uploadId) {
        try {
            Path chunkDir = Path.of(scriptsDir, "_chunks", uploadId);
            if (!Files.exists(chunkDir)) return 0L;
            try (var stream = Files.walk(chunkDir)) {
                return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0L; } })
                        .sum();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private static class ChunkUploadMetadata {
        public String uploadId;
        public int totalChunks;
        public long createdAtEpochMs;
        public long updatedAtEpochMs;
        public Map<Integer, String> chunkHashes = new HashMap<>();
        public Map<Integer, Long> chunkSizes = new HashMap<>();
    }
}
