package com.example.agent.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

/**
 * 에이전트 간 업데이트 파일 공유용 경량 HTTP 캐시 서버.
 * 경로: GET /pcagent/update-cache/{sha256}
 * 인증: X-Agent-Update-Token 헤더 일치
 */
public class UpdateCacheServer {
    private static final Logger log = LoggerFactory.getLogger(UpdateCacheServer.class);
    private static final String CONTEXT_PATH = "/pcagent/update-cache";
    private static final String UPDATE_TOKEN_HEADER = "X-Agent-Update-Token";
    private static final long DEFAULT_TTL_SECONDS = 6 * 60 * 60L;

    private final Path cacheDir;
    private final int port;
    private final ConcurrentMap<String, CacheEntry> cacheEntries = new ConcurrentHashMap<>();

    private volatile HttpServer server;
    private volatile boolean started;

    public UpdateCacheServer(Path cacheDir, int port) {
        this.cacheDir = cacheDir;
        this.port = port;
    }

    /**
     * OTA 업데이트 명령을 받았을 때 on-demand로 시작한다.
     * 평소엔 서버를 띄우지 않아 jdk.httpserver Dispatcher 스레드의
     * 1초 주기 wakeup으로 인한 CPU 스파이크가 발생하지 않는다.
     */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        Files.createDirectories(cacheDir);
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext(CONTEXT_PATH, new CacheHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-update-cache-server");
            t.setDaemon(true);
            return t;
        }));
        httpServer.start();
        server = httpServer;
        started = true;
        log.info("Update cache server started on port={} dir={}", port, cacheDir);
    }

    public synchronized void stop() {
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop(0);
        }
        server = null;
        started = false;
    }

    public void registerArtifact(String sha256, Path sourceFile, String token) throws IOException {
        registerArtifact(sha256, sourceFile, token, DEFAULT_TTL_SECONDS);
    }

    public void registerArtifact(String sha256, Path sourceFile, String token, long ttlSeconds) throws IOException {
        String normalizedSha = normalizeSha256(sha256);
        if (normalizedSha == null) {
            throw new IOException("invalid sha256");
        }
        if (sourceFile == null || !Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new IOException("update artifact file not found");
        }

        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(normalizedSha + ".bin");
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);

        long expiresAt = System.currentTimeMillis() + Math.max(60L, ttlSeconds) * 1000L;
        CacheEntry entry = new CacheEntry(target, token == null ? "" : token, expiresAt, normalizedSha);
        cacheEntries.put(normalizedSha, entry);
        purgeExpiredEntries();
    }

    private void purgeExpiredEntries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> mapEntry : cacheEntries.entrySet()) {
            CacheEntry entry = mapEntry.getValue();
            if (entry == null || now <= entry.expiresAtEpochMs) {
                continue;
            }
            cacheEntries.remove(mapEntry.getKey(), entry);
            try {
                Files.deleteIfExists(entry.filePath);
            } catch (IOException ignored) {
            }
        }
    }

    private String normalizeSha256(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            return null;
        }
        return normalized;
    }

    private class CacheHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writePlain(exchange, 405, "method not allowed");
                    return;
                }

                purgeExpiredEntries();

                String path = exchange.getRequestURI() == null ? null : exchange.getRequestURI().getPath();
                if (path == null || !path.startsWith(CONTEXT_PATH + "/")) {
                    writePlain(exchange, 404, "not found");
                    return;
                }
                String sha = normalizeSha256(path.substring((CONTEXT_PATH + "/").length()));
                if (sha == null || sha.contains("/")) {
                    writePlain(exchange, 400, "invalid sha256");
                    return;
                }

                CacheEntry entry = cacheEntries.get(sha);
                if (entry == null || !Files.exists(entry.filePath)) {
                    writePlain(exchange, 404, "artifact not found");
                    return;
                }
                if (System.currentTimeMillis() > entry.expiresAtEpochMs) {
                    cacheEntries.remove(sha, entry);
                    Files.deleteIfExists(entry.filePath);
                    writePlain(exchange, 404, "artifact expired");
                    return;
                }

                String expectedToken = entry.token == null ? "" : entry.token;
                String providedToken = exchange.getRequestHeaders().getFirst(UPDATE_TOKEN_HEADER);
                if (!expectedToken.isBlank() && (providedToken == null || !expectedToken.equals(providedToken))) {
                    writePlain(exchange, 403, "forbidden");
                    return;
                }

                long size = Files.size(entry.filePath);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().add("X-Script-SHA256", entry.sha256);

                RangeSpec range = parseRange(exchange.getRequestHeaders().getFirst("Range"), size);
                if (range == null) {
                    exchange.sendResponseHeaders(200, size);
                    try (InputStream in = Files.newInputStream(entry.filePath);
                         OutputStream out = exchange.getResponseBody()) {
                        copy(in, out, size);
                    }
                } else {
                    exchange.getResponseHeaders().add("Content-Range",
                            "bytes " + range.start + "-" + range.end + "/" + size);
                    exchange.sendResponseHeaders(206, range.length());
                    try (InputStream in = Files.newInputStream(entry.filePath);
                         OutputStream out = exchange.getResponseBody()) {
                        in.skipNBytes(range.start);
                        copy(in, out, range.length());
                    }
                }
            } catch (InvalidRangeException ex) {
                exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().add("Content-Range", "bytes */" + Math.max(0L, ex.fileSize));
                exchange.sendResponseHeaders(416, -1);
            } catch (Exception ex) {
                writePlain(exchange, 500, "internal error");
            } finally {
                exchange.close();
            }
        }

        private RangeSpec parseRange(String rangeHeader, long fileSize) {
            if (rangeHeader == null || rangeHeader.isBlank()) {
                return null;
            }
            if (fileSize <= 0) {
                throw new InvalidRangeException(fileSize);
            }
            String value = rangeHeader.trim();
            if (!value.startsWith("bytes=") || value.contains(",")) {
                throw new InvalidRangeException(fileSize);
            }
            String spec = value.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                throw new InvalidRangeException(fileSize);
            }
            try {
                long start;
                long end;
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();
                if (startText.isEmpty()) {
                    long suffixLength = Long.parseLong(endText);
                    if (suffixLength <= 0) {
                        throw new InvalidRangeException(fileSize);
                    }
                    start = Math.max(0L, fileSize - suffixLength);
                    end = fileSize - 1;
                } else {
                    start = Long.parseLong(startText);
                    end = endText.isEmpty() ? fileSize - 1 : Long.parseLong(endText);
                }
                if (start < 0 || start >= fileSize || end < start) {
                    throw new InvalidRangeException(fileSize);
                }
                return new RangeSpec(start, Math.min(end, fileSize - 1));
            } catch (NumberFormatException ex) {
                throw new InvalidRangeException(fileSize);
            }
        }

        private void copy(InputStream in, OutputStream out, long limit) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            long remaining = limit;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }

        private void writePlain(HttpExchange exchange, int status, String text) throws IOException {
            byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static final class RangeSpec {
        private final long start;
        private final long end;

        private RangeSpec(long start, long end) {
            this.start = start;
            this.end = end;
        }

        private long length() {
            return end - start + 1;
        }
    }

    private static final class InvalidRangeException extends RuntimeException {
        private final long fileSize;

        private InvalidRangeException(long fileSize) {
            this.fileSize = fileSize;
        }
    }

    private static final class CacheEntry {
        private final Path filePath;
        private final String token;
        private final long expiresAtEpochMs;
        private final String sha256;

        private CacheEntry(Path filePath, String token, long expiresAtEpochMs, String sha256) {
            this.filePath = filePath;
            this.token = token;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.sha256 = sha256;
        }
    }
}
