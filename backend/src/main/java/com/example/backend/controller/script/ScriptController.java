package com.example.backend.controller.script;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.repository.script.ScriptArtifactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.security.access.prepost.PreAuthorize;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 스크립트 관리 API
 * PowerShell 및 다른 스크립트 작을 업로드 및 다운로드할 수 있습니다.
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

    private final ScriptArtifactRepository scriptArtifactRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> uploadLocks = new ConcurrentHashMap<>();

    // TODO: 청크 메타데이터/매니페스트용 Repository 주입 (예: FileChunkRepository, FileManifestRepository)

    @Value("${app.scripts.dir:storage/scripts}")
    private String scriptsDir;

    public ScriptController(ScriptArtifactRepository scriptArtifactRepository) {
        this.scriptArtifactRepository = scriptArtifactRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 스크립트 파일 업로드
     */
    @PostMapping(value = "/scripts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
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
        ScriptArtifact existing = scriptArtifactRepository.findFirstBySha256(sha256);
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
        a.setCreatedAt(LocalDateTime.now());
        ScriptArtifact saved = scriptArtifactRepository.save(a);

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
            @RequestParam(value = "taskId", required = false) Long taskId) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file required"));
        }

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

        ScriptArtifact existing = scriptArtifactRepository.findFirstBySha256(sha256);
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
        a.setCreatedAt(LocalDateTime.now());
        ScriptArtifact saved = scriptArtifactRepository.save(a);

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
    @PreAuthorize("hasRole('ADMIN')")
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

        Object lock = uploadLocks.computeIfAbsent(uploadId, key -> new Object());
        try {
            synchronized (lock) {
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
                    } finally {
                        Files.deleteIfExists(tempChunk);
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
        } finally {
            uploadLocks.remove(uploadId, lock);
        }
    }

    /**
     * 업로드 완료 후 파일 조립 및 매니페스트/최종 해시 생성 (1단계)
     * - 모든 청크 업로드 후 호출
     * - 청크 순서대로 병합, 전체 파일 해시 계산, 매니페스트 생성
     * - DB/스토리지에 최종 파일 및 매니페스트 기록
     */
    @PostMapping("/file-chunks/assemble")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "청크 조립 및 매니페스트 생성",
        description = "모든 청크 업로드 후 호출. 서버가 청크를 병합하여 최종 파일을 생성하고, 전체 해시 및 매니페스트를 기록합니다."
    )
    public ResponseEntity<?> assembleChunks(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("originalFilename") String originalFilename,
            @RequestParam("totalChunks") int totalChunks) throws Exception {
        if (!isValidUploadId(uploadId)) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_ID", "uploadId 형식이 올바르지 않습니다.", false);
        }
        if (!StringUtils.hasText(originalFilename)) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "originalFilename required", false);
        }
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            return chunkError(HttpStatus.BAD_REQUEST, "INVALID_TOTAL_CHUNKS", "totalChunks 범위가 올바르지 않습니다.", false);
        }

        Object lock = uploadLocks.computeIfAbsent(uploadId, key -> new Object());
        try {
            synchronized (lock) {
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

                ScriptArtifact existing = scriptArtifactRepository.findFirstBySha256(finalSha256);
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
                a.setCreatedAt(LocalDateTime.now());
                ScriptArtifact saved = scriptArtifactRepository.save(a);

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
        } finally {
            uploadLocks.remove(uploadId, lock);
        }
    }

    @GetMapping("/scripts/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Optional<ScriptArtifact> opt = scriptArtifactRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ScriptArtifact a = opt.get();

        // Path traversal 방지: resolve 후 반드시 scripts 디렉터리 내부인지 검증
        Path baseDir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Path file = baseDir.resolve(a.getStoredFilename()).normalize();
        if (!file.startsWith(baseDir)) {
            return ResponseEntity.status(400).build();
        }
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        Resource res = new FileSystemResource(file);
        // 원본 파일명을 Content-Disposition에 노출하되 RFC 5987 비ASCII 문자 제거
        String filename = a.getOriginalFilename() == null ? ("script-" + id + ".ps1") : a.getOriginalFilename();
        String safeFilename = filename.replaceAll("[^\\w.\\-]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                .header("X-Script-SHA256", a.getSha256() != null ? a.getSha256() : "")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    /**
     * 관리자용: 에이전트가 업로드한 수집 파일 다운로드
     */
    @GetMapping("/agent/uploads/{id}/download")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "수집 파일 다운로드", description = "에이전트가 업로드한 수집 파일을 관리자가 다운로드합니다.")
    public ResponseEntity<Resource> downloadCollected(@PathVariable Long id) {
        Optional<ScriptArtifact> opt = scriptArtifactRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ScriptArtifact a = opt.get();

        Path baseDir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Path file = baseDir.resolve(a.getStoredFilename()).normalize();
        if (!file.startsWith(baseDir)) {
            return ResponseEntity.status(400).build();
        }
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        Resource res = new FileSystemResource(file);
        String filename = a.getOriginalFilename() == null ? ("collected-" + id + ".zip") : a.getOriginalFilename();
        String safeFilename = filename.replaceAll("[^\\w.\\-]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                .header("X-Script-SHA256", a.getSha256() != null ? a.getSha256() : "")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    private boolean isValidUploadId(String uploadId) {
        return uploadId != null && SAFE_UPLOAD_ID.matcher(uploadId).matches();
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
        try (var walk = Files.walk(uploadDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("chunk temp cleanup skip: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("chunk upload dir cleanup failed: {}", uploadDir, e);
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

    private static class ChunkUploadMetadata {
        public String uploadId;
        public int totalChunks;
        public long createdAtEpochMs;
        public long updatedAtEpochMs;
        public Map<Integer, String> chunkHashes = new HashMap<>();
        public Map<Integer, Long> chunkSizes = new HashMap<>();
    }
}

