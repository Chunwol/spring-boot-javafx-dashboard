package com.example.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class MultipartUtil {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static String uploadFile(String url, Path filePath) throws IOException, InterruptedException {
        HttpRequest request = newMultipartRequest(url, Map.of("file", filePath.toString())).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    public static HttpRequest.Builder newMultipartRequest(String url, Map<String, String> params) {
        return newMultipartRequest(url, params, Map.of());
    }

    public static HttpRequest.Builder newMultipartRequest(String url,
                                                          Map<String, String> params,
                                                          Map<String, String> headers) {
        String boundary = "----Boundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(boundary, params);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank() && !value.isBlank()) {
                    builder.header(key, value);
                }
            });
        }

        return builder;
    }

    private static byte[] buildMultipartBody(String boundary, Map<String, String> params) {
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            Map<String, String> safeParams = params == null ? Map.of() : new LinkedHashMap<>(params);

            for (Map.Entry<String, String> entry : safeParams.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || value == null) {
                    continue;
                }

                Path path = Path.of(value);
                if (Files.exists(path)) {
                    String fileName = path.getFileName().toString();
                    String contentType = Files.probeContentType(path);
                    if (contentType == null || contentType.isBlank()) {
                        contentType = "application/octet-stream";
                    }
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(Files.readAllBytes(path));
                    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    output.write(value.getBytes(StandardCharsets.UTF_8));
                    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }

            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("multipart body 생성 실패", e);
        }
    }
}