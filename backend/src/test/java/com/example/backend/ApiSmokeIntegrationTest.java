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
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeIntegrationTest {

    private static final String ADMIN_USERNAME = "smoke-admin";
    private static final String ADMIN_PASSWORD = "smoke-password-123!";
    private static final String INSTALL_KEY = "SMOKE-INSTALL-KEY-2026";
    private static final String AGENT_MAC = "AA:BB:CC:DD:EE:77";
    private static final String AGENT_HOST = "SMOKE-LAB-77";
    private static final String AGENT_IP = "192.168.88.77";
    private static final Path SMOKE_DB_PATH = Path.of("build", "tmp", "smoke-api-" + Instant.now().toEpochMilli() + ".db")
            .toAbsolutePath()
            .normalize();
    private static final Path SMOKE_UPLOAD_DIR = Path.of("build", "tmp", "smoke-uploads-" + Instant.now().toEpochMilli())
            .toAbsolutePath()
            .normalize();

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + normalizePathForJdbc(SMOKE_DB_PATH));
        registry.add("app.scripts.dir", () -> SMOKE_UPLOAD_DIR.toString());
        registry.add("agent.install-key", () -> INSTALL_KEY);
        registry.add("DEFAULT_ADMIN_USERNAME", () -> ADMIN_USERNAME);
        registry.add("DEFAULT_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("DEFAULT_ADMIN_EMAIL", () -> "smoke-admin@example.com");
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "true");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
    }

    @AfterAll
    static void cleanupSmokeArtifacts() {
        safeDelete(SMOKE_DB_PATH);
        safeDelete(Path.of(SMOKE_DB_PATH + "-journal"));
        safeDelete(Path.of(SMOKE_DB_PATH + "-wal"));
        safeDelete(Path.of(SMOKE_DB_PATH + "-shm"));
        deleteRecursively(SMOKE_UPLOAD_DIR);
    }

    @Test
    void smoke_login_register_heartbeat_command_and_download() throws Exception {
        String bearerToken = loginAndGetBearerToken();
        String agentToken = registerAgentAndGetToken();

        sendHeartbeat(agentToken);
        long queuedTaskId = enqueueRunCmdCommand(bearerToken);
        long deliveredTaskId = pollHeartbeatCommand(agentToken);

        Assertions.assertEquals(queuedTaskId, deliveredTaskId, "heartbeat로 전달된 taskId가 큐에 저장된 taskId와 다릅니다.");

        sendAck(agentToken, queuedTaskId);
        sendResult(agentToken, queuedTaskId, 0, "smoke-ok", "");
        assertCommandCompleted(bearerToken, queuedTaskId);

        uploadAndDownloadScript(bearerToken, agentToken);
    }

    private String loginAndGetBearerToken() throws Exception {
        String url = baseUrl() + "/auth/login";
        Map<String, Object> payload = Map.of(
                "username", ADMIN_USERNAME,
                "password", ADMIN_PASSWORD
        );

        ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(payload, null), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "로그인 실패: " + response.getStatusCode());

        Map<String, Object> body = parseMap(response.getBody());
        String token = stringValue(body.get("token"));
        Assertions.assertFalse(token.isBlank(), "로그인 응답 token이 비어 있습니다.");
        return token;
    }

    private String registerAgentAndGetToken() throws Exception {
        String url = baseUrl() + "/agent/register";
        Map<String, Object> payload = Map.of(
                "macAddress", AGENT_MAC,
                "hostname", AGENT_HOST,
                "ipAddress", AGENT_IP,
                "osInfo", "Windows 11",
                "agentVersion", "smoke-1.0"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Agent-Install-Key", INSTALL_KEY);

        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(toJson(payload), headers), String.class);
        Assertions.assertEquals(201, response.getStatusCode().value(), "에이전트 등록 실패");

        Map<String, Object> body = parseMap(response.getBody());
        String token = stringValue(body.get("agentToken"));
        Assertions.assertFalse(token.isBlank(), "에이전트 등록 응답 agentToken이 비어 있습니다.");
        return token;
    }

    private void sendHeartbeat(String agentToken) throws Exception {
        String url = baseUrl() + "/heartbeat";
        Map<String, Object> payload = Map.of(
                "macAddress", AGENT_MAC,
                "hostname", AGENT_HOST,
                "ipAddress", AGENT_IP,
                "agentVersion", "smoke-1.0",
                "inboundKbps", 1.0,
                "outboundKbps", 2.0
        );

        ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(payload, agentToken), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "heartbeat 실패: " + response.getStatusCode());
    }

    private long enqueueRunCmdCommand(String bearerToken) throws Exception {
        String url = baseUrl() + "/commands";
        Map<String, Object> commandPayload = Map.of("cmd", "echo smoke");
        Map<String, Object> payload = Map.of(
                "scope", "SINGLE",
                "macAddress", AGENT_MAC,
                "type", "RUN_CMD",
                "payloadJson", toJson(commandPayload)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(toJson(payload), headers), String.class);
        Assertions.assertEquals(202, response.getStatusCode().value(), "명령 큐 등록 실패");

        Map<String, Object> body = parseMap(response.getBody());
        List<Long> taskIds = parseLongList(body.get("taskIds"));
        Assertions.assertFalse(taskIds.isEmpty(), "명령 큐 응답 taskIds가 비어 있습니다.");
        return taskIds.get(0);
    }

    private long pollHeartbeatCommand(String agentToken) throws Exception {
        String url = baseUrl() + "/heartbeat";
        Map<String, Object> heartbeat = Map.of(
                "macAddress", AGENT_MAC,
                "hostname", AGENT_HOST,
                "ipAddress", AGENT_IP,
                "inboundKbps", 0.5,
                "outboundKbps", 0.7
        );

        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(heartbeat, agentToken), String.class);
            Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "명령 폴링 heartbeat 실패");
            Map<String, Object> body = parseMap(response.getBody());
            Object commandObj = body.get("command");
            if (commandObj instanceof Map<?, ?> cmdMap && cmdMap.get("taskId") != null) {
                long taskId = Long.parseLong(String.valueOf(cmdMap.get("taskId")));
                String type = stringValue(cmdMap.get("type"));
                Assertions.assertEquals("RUN_CMD", type, "전달된 명령 타입이 예상과 다릅니다.");
                return taskId;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("heartbeat 응답에서 command를 받지 못했습니다.");
    }

    private void sendAck(String agentToken, long taskId) {
        String url = baseUrl() + "/agent/commands/" + taskId + "/ack";
        ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(Map.of(), agentToken), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "ACK 실패: " + response.getStatusCode());
    }

    private void sendResult(String agentToken, long taskId, int exitCode, String stdout, String stderr) {
        String url = baseUrl() + "/agent/commands/" + taskId + "/result";
        Map<String, Object> payload = Map.of(
                "exitCode", exitCode,
                "stdout", stdout,
                "stderr", stderr
        );
        ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(payload, agentToken), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "결과 보고 실패: " + response.getStatusCode());
    }

    private void assertCommandCompleted(String bearerToken, long taskId) throws Exception {
        String url = baseUrl() + "/commands?taskId=" + taskId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);

        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            Assertions.assertEquals(200, response.getStatusCode().value(), "명령 상태 조회 실패");
            Map<String, Object> body = parseMap(response.getBody());
            String status = stringValue(body.get("status"));
            if ("COMPLETED".equalsIgnoreCase(status)) {
                String stdout = stringValue(body.get("stdout"));
                Assertions.assertTrue(stdout.contains("smoke-ok"), "명령 stdout 검증 실패");
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("명령 상태가 COMPLETED로 전환되지 않았습니다.");
    }

    private void uploadAndDownloadScript(String bearerToken, String agentToken) throws Exception {
        byte[] scriptBytes = "Write-Output 'smoke-script'".getBytes(StandardCharsets.UTF_8);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(scriptBytes) {
            @Override
            public String getFilename() {
                return "smoke-script.ps1";
            }
        });

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setBearerAuth(bearerToken);
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> uploadResponse = restTemplate.postForEntity(
                baseUrl() + "/scripts",
                new HttpEntity<>(parts, uploadHeaders),
                String.class
        );
        Assertions.assertTrue(uploadResponse.getStatusCode().is2xxSuccessful(), "스크립트 업로드 실패");

        Map<String, Object> uploadBody = parseMap(uploadResponse.getBody());
        long scriptId = Long.parseLong(String.valueOf(uploadBody.get("id")));

        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.set("X-Agent-Token", agentToken);
        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                baseUrl() + "/scripts/" + scriptId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class
        );
        Assertions.assertEquals(200, downloadResponse.getStatusCode().value(), "스크립트 다운로드 실패");
        Assertions.assertArrayEquals(scriptBytes, downloadResponse.getBody(), "다운로드 바이트가 업로드 원본과 다릅니다.");
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> body, String agentToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (agentToken != null && !agentToken.isBlank()) {
            headers.set("X-Agent-Token", agentToken);
        }
        return new HttpEntity<>(toJson(body), headers);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private Map<String, Object> parseMap(String json) throws Exception {
        return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<>() {
        });
    }

    private List<Long> parseLongList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .map(one -> Long.parseLong(String.valueOf(one)))
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    private static String normalizePathForJdbc(Path path) {
        return path.toString().replace('\\', '/');
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
