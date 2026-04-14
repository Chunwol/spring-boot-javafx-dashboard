package com.example.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChunkUploadIntegrationTest {

    private static final String ADMIN_USERNAME = "chunk-admin";
    private static final String ADMIN_PASSWORD = "chunk-password-123!";
    private static final String INSTALL_KEY = "CHUNK-INSTALL-KEY-2026";
    private static final String AGENT_MAC = "AA:BB:CC:DD:EE:88";
    private static final Path DB_PATH = Path.of("build", "tmp", "chunk-it-" + System.currentTimeMillis() + ".db")
            .toAbsolutePath()
            .normalize();
    private static final Path SCRIPTS_DIR = Path.of("build", "tmp", "chunk-it-uploads-" + System.currentTimeMillis())
            .toAbsolutePath()
            .normalize();

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("app.scripts.dir", () -> SCRIPTS_DIR.toString());
        registry.add("agent.install-key", () -> INSTALL_KEY);
        registry.add("DEFAULT_ADMIN_USERNAME", () -> ADMIN_USERNAME);
        registry.add("DEFAULT_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("DEFAULT_ADMIN_EMAIL", () -> "chunk-admin@example.com");
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "true");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
    }

    @AfterAll
    static void cleanup() {
        safeDelete(DB_PATH);
        safeDelete(Path.of(DB_PATH + "-journal"));
        safeDelete(Path.of(DB_PATH + "-wal"));
        safeDelete(Path.of(DB_PATH + "-shm"));
        deleteRecursively(SCRIPTS_DIR);
    }

    @Test
    void chunk_upload_resume_and_assemble_flow() throws Exception {
        String bearerToken = loginAndGetBearerToken();
        String agentToken = registerAgentAndGetToken();

        byte[] payload = createChunkTestPayload();
        int chunkSize = 64 * 1024;
        List<byte[]> chunks = splitChunks(payload, chunkSize);
        List<String> chunkHashes = chunks.stream().map(this::sha256).toList();

        String uploadId = "up_" + System.currentTimeMillis();
        int totalChunks = chunks.size();

        // 1) 일부 청크 업로드
        uploadChunk(bearerToken, uploadId, 0, totalChunks, chunkHashes.get(0), chunks.get(0));
        uploadChunk(bearerToken, uploadId, 1, totalChunks, chunkHashes.get(1), chunks.get(1));

        // 2) 동일 청크 재업로드 (resume=true 기대)
        Map<String, Object> resumed = uploadChunk(bearerToken, uploadId, 1, totalChunks, chunkHashes.get(1), chunks.get(1));
        Assertions.assertEquals(Boolean.TRUE, resumed.get("resumed"), "동일 청크 재업로드 resume 처리 실패");

        // 3) 조립 시도 -> 누락 청크로 실패 (retryable=true 기대)
        Map<String, Object> missingResp = assembleExpectConflict(bearerToken, uploadId, "chunk-smoke.bin", totalChunks);
        Assertions.assertEquals("MISSING_CHUNKS", String.valueOf(missingResp.get("code")), "누락 청크 에러 코드 불일치");
        Assertions.assertEquals(Boolean.TRUE, missingResp.get("retryable"), "누락 청크 retryable=true 기대");

        // 4) 나머지 업로드 후 조립 성공
        for (int i = 2; i < totalChunks; i++) {
            uploadChunk(bearerToken, uploadId, i, totalChunks, chunkHashes.get(i), chunks.get(i));
        }
        Map<String, Object> assembled = assembleSuccess(bearerToken, uploadId, "chunk-smoke.bin", totalChunks);
        long artifactId = Long.parseLong(String.valueOf(assembled.get("id")));
        String assembledSha = String.valueOf(assembled.get("sha256"));
        Assertions.assertEquals(sha256(payload), assembledSha, "조립 파일 SHA-256 불일치");

        // 5) 최종 파일 다운로드 검증
        byte[] downloaded = downloadScriptWithAgentToken(agentToken, artifactId);
        Assertions.assertArrayEquals(payload, downloaded, "다운로드 파일과 원본 바이트가 다릅니다.");
    }

    private String loginAndGetBearerToken() throws Exception {
        String url = baseUrl() + "/auth/login";
        Map<String, Object> payload = Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, jsonEntity(payload, null, null), String.class);
        Assertions.assertTrue(resp.getStatusCode().is2xxSuccessful(), "로그인 실패");
        Map<String, Object> body = parseMap(resp.getBody());
        String token = String.valueOf(body.getOrDefault("token", ""));
        Assertions.assertFalse(token.isBlank(), "로그인 토큰 비어 있음");
        return token;
    }

    private String registerAgentAndGetToken() throws Exception {
        String url = baseUrl() + "/agent/register";
        Map<String, Object> payload = Map.of(
                "macAddress", AGENT_MAC,
                "hostname", "CHUNK-LAB-88",
                "ipAddress", "192.168.88.88",
                "osInfo", "Windows 11",
                "agentVersion", "chunk-it-1.0"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Agent-Install-Key", INSTALL_KEY);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(toJson(payload), headers), String.class);
        Assertions.assertEquals(201, resp.getStatusCode().value(), "에이전트 등록 실패");
        Map<String, Object> body = parseMap(resp.getBody());
        String token = String.valueOf(body.getOrDefault("agentToken", ""));
        Assertions.assertFalse(token.isBlank(), "agentToken 비어 있음");
        return token;
    }

    private Map<String, Object> uploadChunk(String bearerToken,
                                            String uploadId,
                                            int chunkIndex,
                                            int totalChunks,
                                            String chunkHash,
                                            byte[] chunkBytes) throws Exception {
        String url = baseUrl() + "/file-chunks"
                + "?uploadId=" + uploadId
                + "&chunkIndex=" + chunkIndex
                + "&totalChunks=" + totalChunks
                + "&chunkHash=" + chunkHash;

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(chunkBytes) {
            @Override
            public String getFilename() {
                return "chunk-" + chunkIndex + ".part";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(parts, headers), String.class);
        Assertions.assertTrue(resp.getStatusCode().is2xxSuccessful(), "청크 업로드 실패 index=" + chunkIndex);
        return parseMap(resp.getBody());
    }

    private Map<String, Object> assembleExpectConflict(String bearerToken,
                                                       String uploadId,
                                                       String originalFilename,
                                                       int totalChunks) throws Exception {
        String url = baseUrl() + "/file-chunks/assemble"
                + "?uploadId=" + uploadId
                + "&originalFilename=" + originalFilename
                + "&totalChunks=" + totalChunks;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
            Assertions.assertEquals(409, resp.getStatusCode().value(), "누락 청크 조립 충돌(409) 기대");
            return parseMap(resp.getBody());
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(409, ex.getStatusCode().value(), "누락 청크 조립 충돌(409) 기대");
            return parseMap(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> assembleSuccess(String bearerToken,
                                                String uploadId,
                                                String originalFilename,
                                                int totalChunks) throws Exception {
        String url = baseUrl() + "/file-chunks/assemble"
                + "?uploadId=" + uploadId
                + "&originalFilename=" + originalFilename
                + "&totalChunks=" + totalChunks;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);

        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
        Assertions.assertTrue(resp.getStatusCode().is2xxSuccessful(), "청크 조립 실패");
        return parseMap(resp.getBody());
    }

    private byte[] downloadScriptWithAgentToken(String agentToken, long id) {
        String url = baseUrl() + "/scripts/" + id + "/download";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agent-Token", agentToken);
        ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        Assertions.assertEquals(200, resp.getStatusCode().value(), "스크립트 다운로드 실패");
        return resp.getBody() == null ? new byte[0] : resp.getBody();
    }

    private byte[] createChunkTestPayload() {
        byte[] seed = "CHUNK-SMOKE-TEST-PAYLOAD-2026".getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[seed.length * 12000];
        for (int i = 0; i < out.length; i++) {
            out[i] = seed[i % seed.length];
        }
        return out;
    }

    private List<byte[]> splitChunks(byte[] source, int chunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < source.length) {
            int len = Math.min(chunkSize, source.length - pos);
            byte[] one = new byte[len];
            System.arraycopy(source, pos, one, 0, len);
            chunks.add(one);
            pos += len;
        }
        return chunks;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 계산 실패", e);
        }
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> payload, String bearerToken, String agentToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
        if (agentToken != null && !agentToken.isBlank()) {
            headers.set("X-Agent-Token", agentToken);
        }
        return new HttpEntity<>(toJson(payload), headers);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private Map<String, Object> parseMap(String json) throws Exception {
        return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<>() {
        });
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        safeDelete(path);
                    });
        } catch (IOException ignored) {
        }
    }

    private static void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
