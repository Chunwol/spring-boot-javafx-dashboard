package com.example.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.service.audit.AuditService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeIntegrationTest {

    private static final String ADMIN_USERNAME = "smoke-admin";
    private static final String ADMIN_PASSWORD = "smoke-password-123!";
    private static final String VIEWER_USERNAME = "smoke-viewer";
    private static final String VIEWER_PASSWORD = "smoke-viewer-password-123!";
    private static final String JWT_SECRET = "api-smoke-integration-test-signing-key-2026";
    private static final String INSTALL_KEY = "SMOKE-INSTALL-KEY-2026";
    private static final String AGENT_MAC = "AA:BB:CC:DD:EE:77";
    private static final String AGENT_HOST = "SMOKE-LAB-77";
    private static final String AGENT_IP = "192.168.88.77";
    private static final String AGENT2_MAC = "AA:BB:CC:DD:EE:78";
    private static final String AGENT2_HOST = "SMOKE-LAB-78";
    private static final String AGENT2_IP = "192.168.88.78";
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + normalizePathForJdbc(SMOKE_DB_PATH));
        registry.add("app.scripts.dir", () -> SMOKE_UPLOAD_DIR.toString());
        registry.add("agent.install-key", () -> INSTALL_KEY);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("app.audit.flush-interval-ms", () -> "600000");
        registry.add("DEFAULT_ADMIN_USERNAME", () -> ADMIN_USERNAME);
        registry.add("DEFAULT_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("DEFAULT_ADMIN_EMAIL", () -> "smoke-admin@example.com");
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "true");
        registry.add("DEFAULT_VIEWER_USERNAME", () -> VIEWER_USERNAME);
        registry.add("DEFAULT_VIEWER_PASSWORD", () -> VIEWER_PASSWORD);
        registry.add("DEFAULT_VIEWER_EMAIL", () -> "smoke-viewer@example.com");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "true");
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
        deleteCommandHistory(bearerToken, queuedTaskId);

        uploadAndDownloadScript(bearerToken, agentToken);
    }

    @Test
    void smoke_openApiDocumentIsAvailable() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/v3/api-docs", String.class);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertTrue(response.getBody() != null && response.getBody().contains("\"openapi\""),
                "OpenAPI 문서 응답에 openapi 필드가 없습니다.");
    }

    @Test
    void smoke_autoLoginCookieRefreshesAndLogoutClearsIt() throws Exception {
        Map<String, Object> payload = Map.of(
                "username", ADMIN_USERNAME,
                "password", ADMIN_PASSWORD,
                "autoLogin", true
        );
        ResponseEntity<String> login = restTemplate.postForEntity(
                baseUrl() + "/auth/login", jsonEntity(payload, null), String.class);
        String loginCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(loginCookie, "자동 로그인 쿠키가 발급되지 않았습니다.");
        Assertions.assertTrue(loginCookie.contains("classroom_refresh="));
        Assertions.assertTrue(loginCookie.contains("HttpOnly"));
        Assertions.assertTrue(loginCookie.contains("SameSite=Strict"));
        Assertions.assertTrue(loginCookie.contains("Path=/api/v1/auth"));

        HttpHeaders cookieHeaders = new HttpHeaders();
        cookieHeaders.add(HttpHeaders.COOKIE, loginCookie.split(";", 2)[0]);
        ResponseEntity<String> refresh = restTemplate.exchange(
                baseUrl() + "/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(cookieHeaders),
                String.class);
        Assertions.assertEquals(200, refresh.getStatusCode().value());
        Assertions.assertFalse(stringValue(parseMap(refresh.getBody()).get("token")).isBlank());
        Assertions.assertTrue(refresh.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("classroom_refresh="));

        ResponseEntity<String> logout = restTemplate.exchange(
                baseUrl() + "/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(cookieHeaders),
                String.class);
        Assertions.assertEquals(204, logout.getStatusCode().value());
        Assertions.assertTrue(logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("Max-Age=0"));
        auditService.flushQueue();
        Assertions.assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND actor = ?",
                Integer.class, "LOGOUT", ADMIN_USERNAME) >= 1);
    }

    @Test
    void smoke_loginFailuresPersistLockAndDisableAccount() throws Exception {
        String username = "smoke-lockout-" + System.nanoTime();
        String password = "smoke-lockout-password-123!";
        User user = userRepository.save(new User(
                username, passwordEncoder.encode(password), username + "@example.com", "USER"));
        Map<String, Object> wrongCredentials = Map.of(
                "username", username,
                "password", "definitely-wrong",
                "autoLogin", true);

        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                expectLoginRejected(wrongCredentials);
            }
            expectLoginRejected(wrongCredentials);

            User temporarilyLocked = userRepository.findById(user.getId()).orElseThrow();
            Assertions.assertEquals(5, temporarilyLocked.getFailedLoginAttempts());
            Assertions.assertTrue(temporarilyLocked.getLoginLockedUntil().isAfter(LocalDateTime.now()));
            temporarilyLocked.setLoginLockedUntil(LocalDateTime.now().minusSeconds(1));
            userRepository.save(temporarilyLocked);

            for (int attempt = 6; attempt <= 9; attempt++) {
                expectLoginRejected(wrongCredentials);
            }
            expectLoginRejected(wrongCredentials);

            User disabled = userRepository.findById(user.getId()).orElseThrow();
            Assertions.assertEquals(10, disabled.getFailedLoginAttempts());
            Assertions.assertFalse(disabled.isActive());
            auditService.flushQueue();
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                    Integer.class, "LOGIN_TEMPORARILY_LOCKED", username));
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                    Integer.class, "LOGIN_ACCOUNT_DISABLED", username));
            Assertions.assertEquals(10, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                    Integer.class, "LOGIN_FAILED", username));

            String adminToken = loginAndGetBearerToken();
            HttpHeaders historyHeaders = new HttpHeaders();
            historyHeaders.setBearerAuth(adminToken);
            ResponseEntity<String> history = restTemplate.exchange(
                    baseUrl() + "/users/access-history",
                    HttpMethod.GET,
                    new HttpEntity<>(historyHeaders),
                    String.class);
            Assertions.assertEquals(200, history.getStatusCode().value());
            Assertions.assertTrue(history.getBody() != null
                    && history.getBody().contains(username)
                    && history.getBody().contains("LOGIN_FAILED"));
        } finally {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void smoke_chunk_upload_resume_and_assemble() throws Exception {
        String bearerToken = loginAndGetBearerToken();
        String agentToken = registerAgentAndGetToken();

        byte[] payload = createSmokeChunkPayload();
        int chunkSize = 32 * 1024;
        List<byte[]> chunks = splitChunks(payload, chunkSize);
        Assertions.assertTrue(chunks.size() >= 3, "청크 시나리오를 위해 최소 3개 이상의 청크가 필요합니다.");
        List<String> hashes = chunks.stream().map(this::sha256).toList();

        String uploadId = "smoke_chunk_" + System.currentTimeMillis();
        int totalChunks = chunks.size();

        uploadChunk(bearerToken, uploadId, 0, totalChunks, hashes.get(0), chunks.get(0));
        uploadChunk(bearerToken, uploadId, 1, totalChunks, hashes.get(1), chunks.get(1));

        Map<String, Object> resumed = uploadChunk(
                bearerToken, uploadId, 1, totalChunks, hashes.get(1), chunks.get(1));
        Assertions.assertEquals(Boolean.TRUE, resumed.get("resumed"), "동일 청크 재업로드 resume 처리 실패");

        Map<String, Object> missing = assembleChunkExpectConflict(
                bearerToken, uploadId, "smoke-chunk.bin", totalChunks);
        Assertions.assertEquals("MISSING_CHUNKS", stringValue(missing.get("code")), "누락 청크 에러 코드 불일치");
        Assertions.assertEquals(Boolean.TRUE, missing.get("retryable"), "누락 청크 retryable=true 기대");

        for (int i = 2; i < totalChunks; i++) {
            uploadChunk(bearerToken, uploadId, i, totalChunks, hashes.get(i), chunks.get(i));
        }

        Map<String, Object> assembled = assembleChunkSuccess(
                bearerToken, uploadId, "smoke-chunk.bin", totalChunks);
        long artifactId = Long.parseLong(String.valueOf(assembled.get("id")));
        Assertions.assertEquals(sha256(payload), stringValue(assembled.get("sha256")), "조립 파일 SHA-256 불일치");

        byte[] downloaded = downloadScriptWithAgentToken(agentToken, artifactId);
        Assertions.assertArrayEquals(payload, downloaded, "청크 조립 파일 다운로드 검증 실패");
    }

    @Test
    void smoke_ack_result_rejects_non_owner_and_ignores_duplicate_final_result() throws Exception {
        String bearerToken = loginAndGetBearerToken();
        String ownerToken = registerAgentAndGetToken(AGENT_MAC, AGENT_HOST, AGENT_IP, "smoke-1.0");
        String otherToken = registerAgentAndGetToken(AGENT2_MAC, AGENT2_HOST, AGENT2_IP, "smoke-1.0");

        sendHeartbeat(ownerToken, AGENT_MAC, AGENT_HOST, AGENT_IP, "smoke-1.0");
        long taskId = enqueueRunCmdCommand(bearerToken);
        long deliveredTaskId = pollHeartbeatCommand(ownerToken);
        Assertions.assertEquals(taskId, deliveredTaskId, "소유 에이전트에 전달된 taskId 불일치");

        sendAckExpectStatus(otherToken, taskId, 403);
        sendResultExpectStatus(otherToken, taskId, 0, "forbidden", "", 403);

        sendAck(ownerToken, taskId);
        sendResult(ownerToken, taskId, 0, "first-result", "");
        sendResult(ownerToken, taskId, 1, "late-overwrite", "should-ignore");

        Map<String, Object> command = fetchCommand(bearerToken, taskId);
        Assertions.assertEquals("COMPLETED", stringValue(command.get("status")), "최종 상태는 COMPLETED를 유지해야 합니다.");
        Assertions.assertTrue(stringValue(command.get("stdout")).contains("first-result"),
                "중복 결과 보고로 기존 stdout이 덮어써지면 안 됩니다.");
        Assertions.assertEquals("0", stringValue(command.get("exitCode")), "중복 결과 보고로 exitCode가 변경되면 안 됩니다.");
    }

    @Test
    void viewer_can_save_personal_settings_but_admin_command_api_returns_forbidden() throws Exception {
        String viewerToken = loginAndGetBearerToken(VIEWER_USERNAME, VIEWER_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(viewerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> settingResponse = restTemplate.exchange(
                baseUrl() + "/settings/alert-type-filter",
                HttpMethod.PUT,
                new HttpEntity<>("{\"value\":\"[]\"}", headers),
                String.class);
        Assertions.assertEquals(204, settingResponse.getStatusCode().value());

        try {
            restTemplate.exchange(
                    baseUrl() + "/commands/status",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"taskIds\":[]}", headers),
                    String.class);
            Assertions.fail("VIEWER의 관리자 명령 API 호출은 거부되어야 합니다.");
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(403, ex.getStatusCode().value());
        }

        try {
            restTemplate.exchange(
                    baseUrl() + "/definitely-missing-smoke-route",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            Assertions.fail("없는 API 경로는 404를 반환해야 합니다.");
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(404, ex.getStatusCode().value());
        }
    }

    private String loginAndGetBearerToken() throws Exception {
        return loginAndGetBearerToken(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    private String loginAndGetBearerToken(String username, String password) throws Exception {
        String url = baseUrl() + "/auth/login";
        Map<String, Object> payload = Map.of(
                "username", username,
                "password", password
        );

        ResponseEntity<String> response = restTemplate.postForEntity(url, jsonEntity(payload, null), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "로그인 실패: " + response.getStatusCode());

        Map<String, Object> body = parseMap(response.getBody());
        String token = stringValue(body.get("token"));
        Assertions.assertFalse(token.isBlank(), "로그인 응답 token이 비어 있습니다.");
        return token;
    }

    private String registerAgentAndGetToken() throws Exception {
        return registerAgentAndGetToken(AGENT_MAC, AGENT_HOST, AGENT_IP, "smoke-1.0");
    }

    private String registerAgentAndGetToken(String macAddress,
                                            String hostname,
                                            String ipAddress,
                                            String agentVersion) throws Exception {
        String url = baseUrl() + "/agent/register";
        Map<String, Object> payload = Map.of(
                "macAddress", macAddress,
                "hostname", hostname,
                "ipAddress", ipAddress,
                "osInfo", "Windows 11",
                "agentVersion", agentVersion
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
        sendHeartbeat(agentToken, AGENT_MAC, AGENT_HOST, AGENT_IP, "smoke-1.0");
    }

    private void sendHeartbeat(String agentToken,
                               String macAddress,
                               String hostname,
                               String ipAddress,
                               String agentVersion) throws Exception {
        String url = baseUrl() + "/heartbeat";
        Map<String, Object> payload = Map.of(
                "macAddress", macAddress,
                "hostname", hostname,
                "ipAddress", ipAddress,
                "agentVersion", agentVersion,
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

    private void sendAckExpectStatus(String agentToken, long taskId, int expectedStatus) {
        String url = baseUrl() + "/agent/commands/" + taskId + "/ack";
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, jsonEntity(Map.of(), agentToken), String.class);
            Assertions.assertEquals(expectedStatus, response.getStatusCode().value(),
                    "ACK 응답 상태 코드가 기대와 다릅니다.");
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(expectedStatus, ex.getStatusCode().value(),
                    "ACK 응답 상태 코드가 기대와 다릅니다.");
        }
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

    private void sendResultExpectStatus(String agentToken,
                                        long taskId,
                                        int exitCode,
                                        String stdout,
                                        String stderr,
                                        int expectedStatus) {
        String url = baseUrl() + "/agent/commands/" + taskId + "/result";
        Map<String, Object> payload = Map.of(
                "exitCode", exitCode,
                "stdout", stdout,
                "stderr", stderr
        );
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, jsonEntity(payload, agentToken), String.class);
            Assertions.assertEquals(expectedStatus, response.getStatusCode().value(),
                    "결과 보고 응답 상태 코드가 기대와 다릅니다.");
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(expectedStatus, ex.getStatusCode().value(),
                    "결과 보고 응답 상태 코드가 기대와 다릅니다.");
        }
    }

    private Map<String, Object> fetchCommand(String bearerToken, long taskId) throws Exception {
        String url = baseUrl() + "/commands?taskId=" + taskId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(), "명령 상태 조회 실패");
        return parseMap(response.getBody());
    }

    private void deleteCommandHistory(String bearerToken, long taskId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/commands/" + taskId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(), "명령 이력 삭제 실패");
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_queue WHERE task_id = ?",
                Integer.class,
                taskId));
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

    private byte[] downloadScriptWithAgentToken(String agentToken, long scriptId) {
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.set("X-Agent-Token", agentToken);
        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                baseUrl() + "/scripts/" + scriptId + "/download",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class
        );
        Assertions.assertEquals(200, downloadResponse.getStatusCode().value(), "스크립트 다운로드 실패");
        return downloadResponse.getBody() == null ? new byte[0] : downloadResponse.getBody();
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
                return "smoke-" + chunkIndex + ".part";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(parts, headers), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "청크 업로드 실패 index=" + chunkIndex);
        return parseMap(response.getBody());
    }

    private Map<String, Object> assembleChunkExpectConflict(String bearerToken,
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
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
            Assertions.assertEquals(409, response.getStatusCode().value(), "누락 청크 조립은 409가 기대됩니다.");
            return parseMap(response.getBody());
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(409, ex.getStatusCode().value(), "누락 청크 조립은 409가 기대됩니다.");
            return parseMap(ex.getResponseBodyAsString());
        }
    }

    private Map<String, Object> assembleChunkSuccess(String bearerToken,
                                                     String uploadId,
                                                     String originalFilename,
                                                     int totalChunks) throws Exception {
        String url = baseUrl() + "/file-chunks/assemble"
                + "?uploadId=" + uploadId
                + "&originalFilename=" + originalFilename
                + "&totalChunks=" + totalChunks;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "청크 조립 실패");
        return parseMap(response.getBody());
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

    private void expectLoginRejected(Map<String, Object> payload) {
        try {
            restTemplate.postForEntity(baseUrl() + "/auth/login", jsonEntity(payload, null), String.class);
            throw new AssertionError("잘못된 비밀번호 로그인이 성공했습니다.");
        } catch (HttpStatusCodeException ex) {
            Assertions.assertEquals(401, ex.getStatusCode().value());
        }
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

    private byte[] createSmokeChunkPayload() {
        byte[] seed = "API-SMOKE-CHUNK-2026".getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[seed.length * 5000];
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
