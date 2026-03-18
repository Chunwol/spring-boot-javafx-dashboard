package com.example.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

public class MultipartUtil {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static String uploadFile(String url, Path filePath) throws IOException, InterruptedException {
        // 간단한 구현 - 실제로는 multipart/form-data를 구현해야 함
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofFile(filePath))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    public static HttpRequest.Builder newMultipartRequest(String url, Map<String, String> params) {
        // 간단한 구현 - 실제로는 multipart를 구현해야 함
        return HttpRequest.newBuilder()
                .uri(URI.create(url));
    }
}