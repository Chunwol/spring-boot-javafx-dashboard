package com.example.backend.service.scriptlibrary;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.service.script.ScriptArtifactService;
import com.example.backend.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ScriptLibraryArtifactService {
    private static final String ARTIFACT_KIND = "ADMIN_UPLOAD";

    private final FileStorageService storageService;
    private final ScriptArtifactService artifactService;

    public ScriptArtifact store(String filename, byte[] content) {
        try {
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            ScriptArtifact existing = artifactService.findBySha256AndKind(sha256, ARTIFACT_KIND);
            if (existing != null) return existing;

            ScriptArtifact artifact = new ScriptArtifact();
            artifact.setOriginalFilename(filename);
            artifact.setStoredFilename(storageService.storeBytes(filename, content));
            artifact.setSha256(sha256);
            artifact.setSizeBytes((long) content.length);
            artifact.setContentType("text/plain; charset=utf-8");
            artifact.setArtifactKind(ARTIFACT_KIND);
            artifact.setCreatedAt(LocalDateTime.now());
            return artifactService.save(artifact);
        } catch (Exception ex) {
            throw new IllegalStateException("스크립트 파일을 저장하지 못했습니다.", ex);
        }
    }
}
