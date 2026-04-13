package com.example.frontend.service;

import com.example.frontend.config.FrontendEnvConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class ApiClient {
    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);

    public static final String DEFAULT_BASE_URL;
    public static final String DEFAULT_WS_URL;
    public static final String DEFAULT_LOGIN_HOST;
    public static final int DEFAULT_LOGIN_PORT;

    static {
        // FrontendEnvConfig에서 직접 읽기
        String apiUrl = buildApiUrl();
        String wsUrl = buildWebSocketUrl();
        String loginHost = FrontendEnvConfig.getProperty("API_SERVER_HOST", "localhost");
        int loginPort = FrontendEnvConfig.getIntProperty("API_SERVER_PORT", 8888);

        // 폴백: application.properties 읽기
        try (var stream = ApiClient.class.getResourceAsStream("/application.properties")) {
            if (stream != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(stream);
                // application.properties값은 env가 없을 때만 사용
                if (apiUrl.equals("http://localhost:8888/api/v1")) {
                    apiUrl = readSetting(props, "API_BASE_URL", "API_BASE_URL", apiUrl);
                }
                if (wsUrl.equals("ws://localhost:8888/ws/websocket")) {
                    wsUrl = readSetting(props, "WEBSOCKET_STOMP_URL", "WEBSOCKET_STOMP_URL", wsUrl);
                }
            }
        } catch (Exception e) {
            log.debug("application.properties 로드 실패 (정상: .env 사용)", e);
        }

        DEFAULT_BASE_URL = apiUrl;
        DEFAULT_WS_URL = wsUrl;
        DEFAULT_LOGIN_HOST = loginHost;
        DEFAULT_LOGIN_PORT = loginPort;
    }

    private static String buildApiUrl() {
        String protocol = FrontendEnvConfig.getProperty("API_SERVER_PROTOCOL", "http");
        String host = FrontendEnvConfig.getProperty("API_SERVER_HOST", "localhost");
        int port = FrontendEnvConfig.getIntProperty("API_SERVER_PORT", 8888);
        
        // API_BASE_URL이 직접 지정되어 있으면 그것을 사용
        String baseUrl = FrontendEnvConfig.getProperty("API_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        
        return String.format("%s://%s:%d/api/v1", protocol, host, port);
    }

    private static String buildWebSocketUrl() {
        String protocol = FrontendEnvConfig.getProperty("WEBSOCKET_PROTOCOL", "ws");
        String host = FrontendEnvConfig.getProperty("WEBSOCKET_HOST", "localhost");
        int port = FrontendEnvConfig.getIntProperty("WEBSOCKET_PORT", 8888);
        return String.format("%s://%s:%d/ws/websocket", protocol, host, port);
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    private static final int DEFAULT_RETRY_COUNT = 1;
    private static final ApiClient INSTANCE = new ApiClient(DEFAULT_BASE_URL);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile String baseUrl;
    private volatile String wsUrl;
    private volatile String bearerToken;
    private volatile String currentUserRole = "USER";
    private volatile Consumer<Throwable> connectionFailureHandler;
    private volatile Consumer<ApiClientException> authFailureHandler;
    private final AtomicBoolean authFailureNotified = new AtomicBoolean(false);

    public ApiClient() {
        this(DEFAULT_BASE_URL);
    }

    public static ApiClient getInstance() {
        return INSTANCE;
    }

    public ApiClient(String baseUrl) {
        this(baseUrl, DEFAULT_WS_URL);
    }

    public ApiClient(String baseUrl, String wsUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.baseUrl = baseUrl;
        this.wsUrl = wsUrl;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
        if (bearerToken != null && !bearerToken.isBlank()) {
            authFailureNotified.set(false);
        } else {
            this.currentUserRole = "USER";
        }
    }

    public void setCurrentUserRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
        this.currentUserRole = normalized.isBlank() ? "USER" : normalized;
    }

    public String getCurrentUserRole() {
        return currentUserRole;
    }

    public boolean isViewerRole() {
        return "VIEWER".equalsIgnoreCase(currentUserRole);
    }

    public void setConnectionFailureHandler(Consumer<Throwable> handler) {
        this.connectionFailureHandler = handler;
    }

    public void setAuthFailureHandler(Consumer<ApiClientException> handler) {
        this.authFailureHandler = handler;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public boolean hasBearerToken() {
        return bearerToken != null && !bearerToken.isBlank();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void configureEndpoints(String baseUrl, String wsUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.wsUrl = trimTrailingSlash(wsUrl);
    }

    public void configureServer(String host, int port) {
        configureEndpoints(buildApiBaseUrl(host, port), buildWebSocketUrl(host, port));
    }

    public String currentLoginHost() {
        return extractHost(baseUrl, DEFAULT_LOGIN_HOST);
    }

    public int currentLoginPort() {
        return extractPort(baseUrl, DEFAULT_LOGIN_PORT);
    }

    public CompletableFuture<String> get(String path) {
        return sendWithRetry(build(path).GET().build(), DEFAULT_RETRY_COUNT)
                .thenApply(this::ensureSuccess)
                .thenApply(HttpResponse::body);
    }

    public CompletableFuture<byte[]> getBytes(String path) {
        HttpRequest request = build(path).GET().build();
        return sendBytes(request)
                .thenApply(this::ensureSuccessBytes)
                .thenApply(HttpResponse::body);
    }

    public CompletableFuture<String> postJson(String path, Object body) {
        return sendJson("POST", path, body);
    }

    public CompletableFuture<String> putJson(String path, Object body) {
        return sendJson("PUT", path, body);
    }

    public CompletableFuture<String> delete(String path) {
        return sendWithRetry(build(path).DELETE().build(), 0)
                .thenApply(this::ensureSuccess)
                .thenApply(HttpResponse::body);
    }

    /**
     * 이미지 파일을 multipart/form-data 로 업로드한다.
     * POST /api/v1/dashboard/images/upload
     */
    public CompletableFuture<String> uploadImageFile(String path, File file) {
        String boundary = "----DashboardBoundary" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String mimeType = detectMimeType(file.getName());
            // Multipart body 구성
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n"
                    + "Content-Type: " + mimeType + "\r\n\r\n";
            String footer = "\r\n--" + boundary + "--\r\n";
            byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
            System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
            System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

            HttpRequest request = build(path)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return sendWithRetry(request, 0)
                    .thenApply(this::ensureSuccess)
                    .thenApply(HttpResponse::body);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String detectMimeType(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    /**
     * 범용 파일을 multipart/form-data 로 업로드한다.
     */
    public CompletableFuture<String> uploadFile(String path, File file) {
        String boundary = "----UploadBoundary" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String mimeType = detectMimeType(file.getName());
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n"
                    + "Content-Type: " + mimeType + "\r\n\r\n";
            String footer = "\r\n--" + boundary + "--\r\n";
            byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
            System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
            System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

            HttpRequest request = build(path)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return sendWithRetry(request, 0)
                    .thenApply(this::ensureSuccess)
                    .thenApply(HttpResponse::body);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public <T> CompletableFuture<T> getJson(String path, Class<T> clazz) {
        return get(path).thenApply(body -> readValue(body, clazz));
    }

    public <T> CompletableFuture<T> postJson(String path, Object body, Class<T> clazz) {
        return postJson(path, body).thenApply(resp -> readValue(resp, clazz));
    }

    public <T> CompletableFuture<T> putJson(String path, Object body, Class<T> clazz) {
        return putJson(path, body).thenApply(resp -> readValue(resp, clazz));
    }

    public <T> CompletableFuture<T> getJson(String path, TypeReference<T> typeRef) {
        return get(path).thenApply(body -> readValue(body, typeRef));
    }

    /**
     * Spring Data Page<T> 응답에서 content 리스트를 추출합니다.
     * 백엔드가 일반 List를 반환하는 경우(legacy)에도 투명하게 동작합니다.
     */
    public <T> CompletableFuture<List<T>> getPagedList(String path, TypeReference<List<T>> typeRef) {
        return get(path).thenApply(body -> {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
                com.fasterxml.jackson.databind.JsonNode content = root.has("content") ? root.get("content") : root;
                return objectMapper.convertValue(content, typeRef);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    public Map<String, Object> parseObject(String json) {
        return readValue(json, new TypeReference<>() {});
    }

    private CompletableFuture<String> sendJson(String method, String path, Object body) {
        try {
            String json = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = build(path).header("Content-Type", "application/json");
            HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(json)).build();
            return sendWithRetry(request, 0)
                    .thenApply(this::ensureSuccess)
                    .thenApply(HttpResponse::body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private HttpRequest.Builder build(String path) {
        String normalizedPath = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : (path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizedPath))
                .timeout(DEFAULT_TIMEOUT);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return builder;
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request, int retriesLeft) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    if (retriesLeft > 0) {
                        return sendWithRetry(request, retriesLeft - 1);
                    }
                    Throwable unwrapped = unwrap(error);
                    notifyConnectionFailure(unwrapped);
                    return CompletableFuture.<HttpResponse<String>>failedFuture(unwrapped);
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<HttpResponse<byte[]>> sendBytes(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    Throwable unwrapped = unwrap(error);
                    notifyConnectionFailure(unwrapped);
                    return CompletableFuture.<HttpResponse<byte[]>>failedFuture(unwrapped);
                })
                .thenCompose(future -> future);
    }

    private void notifyConnectionFailure(Throwable error) {
        Consumer<Throwable> handler = this.connectionFailureHandler;
        if (handler == null || error == null) {
            return;
        }
        Throwable root = unwrap(error);
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.UnknownHostException
                || root instanceof java.net.SocketTimeoutException
                || root instanceof java.net.http.HttpTimeoutException
                || root instanceof java.nio.channels.ClosedChannelException) {
            handler.accept(root);
        }
    }

    private void notifyAuthFailure(ApiClientException exception) {
        Consumer<ApiClientException> handler = this.authFailureHandler;
        if (handler == null || exception == null) {
            return;
        }
        if (authFailureNotified.compareAndSet(false, true)) {
            handler.accept(exception);
        }
    }

    private HttpResponse<String> ensureSuccess(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return response;
        }
        if (code == 401) {
            ApiClientException exception = new ApiClientException(code, "인증이 필요합니다. 다시 로그인해주세요.");
            notifyAuthFailure(exception);
            throw new CompletionException(exception);
        }
        if (code == 403) {
            ApiClientException exception = new ApiClientException(code, "권한이 없거나 로그인 세션이 만료되었습니다.");
            notifyAuthFailure(exception);
            throw new CompletionException(exception);
        }
        throw new CompletionException(new ApiClientException(code, extractMessage(response.body())));
    }

    private HttpResponse<byte[]> ensureSuccessBytes(HttpResponse<byte[]> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return response;
        }
        throw new CompletionException(new ApiClientException(code, "HTTP " + code));
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "서버가 빈 응답을 반환했습니다. (HTTP 오류 응답)";
        }
        try {
            Map<String, Object> map = parseObject(body);
            Object message = map.get("message");
            if (message != null) {
                return message.toString();
            }
        } catch (Exception e) {
            log.debug("응답 메시지 추출 실패", e);
        }
        return body.length() > 240 ? body.substring(0, 240) : body;
    }

    private <T> T readValue(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private <T> T readValue(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

    private static String readSetting(java.util.Properties props, String propertyKey, String envKey, String defaultValue) {
        return readEnv(envKey, props.getProperty(propertyKey, defaultValue));
    }

    private static String readEnv(String envKey, String defaultValue) {
        // FrontendEnvConfig에서 먼저 읽기
        String value = FrontendEnvConfig.getProperty(envKey);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        // 시스템 환경 변수 읽기 (폴백)
        value = System.getenv(envKey);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parsePort(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.debug("포트 번호 파싱 실패: {}", value);
            return defaultValue;
        }
    }

    private static String buildApiBaseUrl(String host, int port) {
        String normalizedHost = normalizeHost(host);
        String scheme = port == 443 ? "https" : "http";
        return String.format("%s://%s:%d/api/v1", scheme, normalizedHost, port);
    }

    private static String buildWebSocketUrl(String host, int port) {
        String normalizedHost = normalizeHost(host);
        String scheme = port == 443 ? "wss" : "ws";
        return String.format("%s://%s:%d/ws/websocket", scheme, normalizedHost, port);
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim();
        normalized = normalized.replaceFirst("^https?://", "");
        normalized = normalized.replaceFirst("^wss?://", "");
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }
        int colonIndex = normalized.indexOf(':');
        if (colonIndex >= 0) {
            normalized = normalized.substring(0, colonIndex);
        }
        return normalized.isBlank() ? DEFAULT_LOGIN_HOST : normalized;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String extractHost(String url, String fallback) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? fallback : host;
        } catch (Exception e) {
            log.debug("호스트 추출 실패: {}", url);
            return fallback;
        }
    }

    private static int extractPort(String url, int fallback) {
        try {
            URI uri = URI.create(url);
            if (uri.getPort() > 0) {
                return uri.getPort();
            }
            return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (Exception e) {
            log.debug("포트 추출 실패: {}", url);
            return fallback;
        }
    }
}
