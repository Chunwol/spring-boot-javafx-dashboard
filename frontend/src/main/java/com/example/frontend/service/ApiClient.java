package com.example.frontend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ApiClient {

    public static final String DEFAULT_BASE_URL;
    public static final String DEFAULT_WS_URL;

    static {
        String apiUrl = "http://localhost:8080/api/v1";
        String wsUrl  = "ws://localhost:8080/ws/websocket";
        try (var stream = ApiClient.class.getResourceAsStream("/application.properties")) {
            if (stream != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(stream);
                apiUrl = props.getProperty("API_BASE_URL", apiUrl);
                wsUrl  = props.getProperty("WEBSOCKET_STOMP_URL", wsUrl);
            }
        } catch (Exception ignored) {}
        DEFAULT_BASE_URL = apiUrl;
        DEFAULT_WS_URL   = wsUrl;
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    private static final int DEFAULT_RETRY_COUNT = 1;
    private static final ApiClient INSTANCE = new ApiClient(DEFAULT_BASE_URL);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private volatile String bearerToken;

    public ApiClient() {
        this(DEFAULT_BASE_URL);
    }

    public static ApiClient getInstance() {
        return INSTANCE;
    }

    public ApiClient(String baseUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.baseUrl = baseUrl;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String getBaseUrl() {
        return baseUrl;
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
                    return CompletableFuture.<HttpResponse<String>>failedFuture(unwrap(error));
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<HttpResponse<byte[]>> sendBytes(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    return CompletableFuture.<HttpResponse<byte[]>>failedFuture(unwrap(error));
                })
                .thenCompose(future -> future);
    }

    private HttpResponse<String> ensureSuccess(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return response;
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
            return "빈 응답이 반환되었습니다.";
        }
        try {
            Map<String, Object> map = parseObject(body);
            Object message = map.get("message");
            if (message != null) {
                return message.toString();
            }
        } catch (Exception ignored) {
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
}
