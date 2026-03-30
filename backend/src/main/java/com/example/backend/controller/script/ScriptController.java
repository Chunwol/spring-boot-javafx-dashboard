package com.example.backend.controller.script;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.repository.script.ScriptArtifactRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 스크립트 관리 API
 * PowerShell 및 다른 스크립트 작을 업로드 및 다운로드할 수 있습니다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Scripts", description = "스크립트 관리 API")
public class ScriptController {

    private final ScriptArtifactRepository scriptArtifactRepository;

    @Value("${app.scripts.dir:storage/scripts}")
    private String scriptsDir;

    public ScriptController(ScriptArtifactRepository scriptArtifactRepository) {
        this.scriptArtifactRepository = scriptArtifactRepository;
    }

    /**
     * 스크립트 파일 업로드
     */
    @PostMapping(value = "/scripts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
}

