package com.example.agent.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandExecutorBundleUpdateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesAndExtractsAllowlistedFullBundle() throws Exception {
        Path work = Files.createTempDirectory("pcagent-bundle-valid-");
        Path zip = createBundle(work.resolve("bundle.zip"), false, false);
        Path extracted = work.resolve("extracted");

        CommandExecutor.BundleManifest manifest = CommandExecutor.validateAndExtractBundle(
                zip, extracted, mapper, "1.1.20");

        assertThat(manifest.version()).isEqualTo("1.1.20");
        assertThat(manifest.fileCount()).isEqualTo(8);
        assertThat(extracted.resolve("jre/bin/java.exe")).exists();
        assertThat(extracted.resolve("vnc/winvnc.exe")).exists();
    }

    @Test
    void rejectsBundleWhosePayloadDoesNotMatchManifest() throws Exception {
        Path work = Files.createTempDirectory("pcagent-bundle-tampered-");
        Path zip = createBundle(work.resolve("bundle.zip"), true, false);

        assertThatThrownBy(() -> CommandExecutor.validateAndExtractBundle(
                zip, work.resolve("extracted"), mapper, "1.1.20"))
                .hasMessageContaining("verification failed");
    }

    @Test
    void rejectsZipSlipBeforeWritingOutsideStageDirectory() throws Exception {
        Path work = Files.createTempDirectory("pcagent-bundle-slip-");
        Path zip = createBundle(work.resolve("bundle.zip"), false, true);
        Path extracted = work.resolve("extracted");

        assertThatThrownBy(() -> CommandExecutor.validateAndExtractBundle(
                zip, extracted, mapper, "1.1.20"))
                .hasMessageContaining("Unsafe bundle path");
        assertThat(work.resolve("escape.txt")).doesNotExist();
    }

    private Path createBundle(Path zipPath, boolean wrongHash, boolean zipSlip) throws Exception {
        Map<String, byte[]> payload = new LinkedHashMap<>();
        payload.put("agent.jar", "agent-1.1.20".getBytes());
        payload.put("agent-watchdog.ps1", "watchdog".getBytes());
        payload.put("install-helper.ps1", "helper".getBytes());
        payload.put("agent-bundle-updater.ps1", "updater".getBytes());
        payload.put("jre/bin/java.exe", "java".getBytes());
        payload.put("jre/lib/modules", "modules".getBytes());
        payload.put("vnc/winvnc.exe", "winvnc".getBytes());
        payload.put("vnc/createpassword.exe", "password".getBytes());

        List<Map<String, Object>> files = payload.entrySet().stream().map(entry -> {
            String hash = sha256(entry.getValue());
            if (wrongHash && "agent.jar".equals(entry.getKey())) hash = "0".repeat(64);
            return Map.<String, Object>of(
                    "path", entry.getKey(),
                    "size", entry.getValue().length,
                    "sha256", hash
            );
        }).toList();
        byte[] manifest = mapper.writeValueAsBytes(Map.of(
                "schemaVersion", 1,
                "bundleVersion", "1.1.20",
                "minimumBootstrapVersion", "1.1.20",
                "files", files
        ));

        try (OutputStream file = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : payload.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("bundle-manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            if (zipSlip) {
                zip.putNextEntry(new ZipEntry("../escape.txt"));
                zip.write("escape".getBytes());
                zip.closeEntry();
            }
        }
        return zipPath;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
