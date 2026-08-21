package com.example.backend.controller.script;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.service.script.ScriptArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptControllerSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsAdminUploadsAndAgentCollectionsOnSeparateDownloadEndpoints() throws Exception {
        ScriptArtifactService artifacts = mock(ScriptArtifactService.class);
        ScriptController controller = controller(artifacts);
        ScriptArtifact collection = artifact(1L, "collection.zip", "AGENT_COLLECTION");
        ScriptArtifact adminUpload = artifact(2L, "tool.ps1", "ADMIN_UPLOAD");
        Files.writeString(tempDir.resolve(collection.getStoredFilename()), "collected");
        Files.writeString(tempDir.resolve(adminUpload.getStoredFilename()), "script");
        when(artifacts.findById(1L)).thenReturn(Optional.of(collection));
        when(artifacts.findById(2L)).thenReturn(Optional.of(adminUpload));

        assertThat(controller.download(1L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.downloadCollected(2L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.downloadCollected(1L, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.download(2L, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsOversizedChunksBeforeWritingThem() throws Exception {
        ScriptController controller = controller(mock(ScriptArtifactService.class));
        ReflectionTestUtils.setField(controller, "chunkMaxBytes", 4L);
        byte[] bytes = "too-large".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));

        var response = controller.uploadChunk("upload-12345678", 0, 1, hash,
                new MockMultipartFile("file", "chunk", "application/octet-stream", bytes));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(Files.exists(tempDir.resolve("_chunks"))).isFalse();
    }

    @Test
    void removesAbandonedChunkDirectoriesAfterTtl() throws Exception {
        ScriptController controller = controller(mock(ScriptArtifactService.class));
        ReflectionTestUtils.setField(controller, "chunkTtlMillis", 60_000L);
        Path abandoned = Files.createDirectories(tempDir.resolve("_chunks").resolve("upload-abandoned"));
        Files.writeString(abandoned.resolve("00000000.part"), "stale");
        Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minusSeconds(120)));
        controller.init();

        controller.cleanupStaleChunkUploads();

        assertThat(Files.exists(abandoned)).isFalse();
    }

    private ScriptController controller(ScriptArtifactService artifacts) {
        ScriptController controller = new ScriptController(artifacts, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "scriptsDir", tempDir.toString());
        ReflectionTestUtils.setField(controller, "chunkMaxBytes", 16L * 1024 * 1024);
        ReflectionTestUtils.setField(controller, "chunkStagingMaxBytes", 1024L * 1024 * 1024);
        return controller;
    }

    private ScriptArtifact artifact(long id, String filename, String kind) {
        ScriptArtifact artifact = new ScriptArtifact();
        artifact.setId(id);
        artifact.setOriginalFilename(filename);
        artifact.setStoredFilename(filename);
        artifact.setArtifactKind(kind);
        artifact.setSha256("a".repeat(64));
        artifact.setSizeBytes(8L);
        return artifact;
    }
}
