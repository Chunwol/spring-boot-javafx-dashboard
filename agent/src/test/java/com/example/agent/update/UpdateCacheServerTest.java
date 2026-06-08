package com.example.agent.update;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCacheServerTest {

    @Test
    void servesRangeRequestsForCachedArtifact() throws Exception {
        Path tempDir = Files.createTempDirectory("update-cache-server-test-");
        UpdateCacheServer server = null;
        try {
            byte[] payload = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
            Path source = tempDir.resolve("agent-update.bin");
            Files.write(source, payload);
            String sha256 = sha256(payload);

            int port = findFreePort();
            server = new UpdateCacheServer(tempDir.resolve("cache"), port);
            server.start();
            server.registerArtifact(sha256, source, "peer-token");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/pcagent/update-cache/" + sha256))
                    .header("X-Agent-Update-Token", "peer-token")
                    .header("Range", "bytes=3-8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Content-Range")).contains("bytes 3-8/" + payload.length);
            assertThat(response.headers().firstValue("Accept-Ranges")).contains("bytes");
            assertThat(response.body()).isEqualTo(Arrays.copyOfRange(payload, 3, 9));
        } finally {
            if (server != null) {
                server.stop();
            }
            deleteRecursively(tempDir);
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
