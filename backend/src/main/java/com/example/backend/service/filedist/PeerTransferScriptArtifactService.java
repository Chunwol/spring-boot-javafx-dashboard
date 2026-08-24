package com.example.backend.service.filedist;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.service.script.ScriptArtifactService;
import com.example.backend.service.storage.FileStorageService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Service
public class PeerTransferScriptArtifactService {

    private static final String ARTIFACT_KIND = "ADMIN_UPLOAD";

    private final FileStorageService storageService;
    private final ScriptArtifactService artifactService;

    public PeerTransferScriptArtifactService(FileStorageService storageService,
                                             ScriptArtifactService artifactService) {
        this.storageService = storageService;
        this.artifactService = artifactService;
    }

    public ScriptArtifact create(String templateName,
                                 String generatedFilename,
                                 Map<String, String> replacements) {
        try {
            ClassPathResource resource = new ClassPathResource("peer-transfer/" + templateName);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                content = content.replace("__" + entry.getKey() + "__", entry.getValue());
            }
            if (content.matches("(?s).*__[A-Z0-9_]+__.*")) {
                throw new IllegalStateException("Unresolved peer-transfer script placeholder: " + templateName);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            ScriptArtifact existing = artifactService.findBySha256AndKind(sha256, ARTIFACT_KIND);
            if (existing != null) {
                return existing;
            }

            ScriptArtifact artifact = new ScriptArtifact();
            artifact.setOriginalFilename(generatedFilename);
            artifact.setStoredFilename(storageService.storeBytes(generatedFilename, bytes));
            artifact.setSha256(sha256);
            artifact.setSizeBytes((long) bytes.length);
            artifact.setContentType("text/plain; charset=utf-8");
            artifact.setArtifactKind(ARTIFACT_KIND);
            artifact.setCreatedAt(LocalDateTime.now());
            return artifactService.save(artifact);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create peer-transfer script: " + templateName, ex);
        }
    }
}
