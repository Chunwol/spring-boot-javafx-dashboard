package com.example.backend.interceptor;

import com.example.backend.config.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP 또는 에이전트 MAC 단위의 API 요청 속도 제한 인터셉터.
 *
 * <p><b>알고리즘</b>: 고정 윈도우(Fixed Window) 방식.
 * 1분(60초) 단위 윈도우 내에서 요청 횟수를 카운트하며,
 * 윈도우가 만료되면 카운터를 리셋한다.
 * 토큰 버킷 방식과 비교했을 때 구현이 단순하고 메모리 효율이 좋지만,
 * 윈도우 경계에서 순간 버스트가 허용될 수 있다.
 *
 * <p><b>키 선택 전략</b>:
 * <ul>
 *   <li>에이전트 엔드포인트({@code /api/v1/agent/**, /api/v1/heartbeat}): request attribute
 *       {@code agentMac}이 있으면 {@code "agent:<MAC>"}을 키로 사용한다.
 *       에이전트는 NAT 뒤에 있을 수 있어 IP 기반 키가 부정확하다.</li>
 *   <li>일반 엔드포인트: {@code X-Forwarded-For} 헤더 우선, 없으면 {@code RemoteAddr}을 사용한다.</li>
 * </ul>
 *
 * <p><b>메모리 관리</b>:
 * {@code MAX_TRACKED_CLIENTS}(20,000) 초과 또는 512 요청마다 오래된 항목을 정리한다.
 * 10분({@code STALE_ENTRY_TTL_MILLIS}) 이상 비활성 항목을 우선 제거하고,
 * 그래도 초과 시 LRU(최근 미접근) 순으로 제거한다.
 *
 * @see RateLimitConfig 임계값 설정
 */
@Component
public class RateLimitingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingInterceptor.class);
    private static final String X_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String X_RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final long WINDOW_SIZE_MILLIS = 60 * 1000; // 1 minute window
    private static final long STALE_ENTRY_TTL_MILLIS = WINDOW_SIZE_MILLIS * 10;
    private static final int MAX_TRACKED_CLIENTS = 20_000;
    private static final int CLEANUP_INTERVAL_REQUESTS = 512;

    private final RateLimitConfig rateLimitConfig;
    private final Map<String, ClientRateLimitData> clientLimiter = new ConcurrentHashMap<>();
    private final AtomicLong cleanupTicker = new AtomicLong();

    public RateLimitingInterceptor(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientIp = getClientIp(request);
        String endpoint = request.getRequestURI();
        String rateLimitKey = resolveRateLimitKey(request, endpoint, clientIp);

        // Skip rate limiting for health checks and documentation
        if (isExempted(endpoint)) {
            return true;
        }

        maybeCleanupLimiters();
        ClientRateLimitData data = clientLimiter.computeIfAbsent(rateLimitKey, k -> new ClientRateLimitData());

        if (!data.tryAcquire(rateLimitConfig.getRequestsPerMinute())) {
            logger.warn("Rate limit exceeded for key: {}, ip: {}, endpoint: {}", rateLimitKey, clientIp, endpoint);
            response.setStatus(429); // HTTP 429 Too Many Requests
            response.setHeader(X_RATE_LIMIT_LIMIT, String.valueOf(rateLimitConfig.getRequestsPerMinute()));
            response.setHeader(X_RATE_LIMIT_REMAINING, "0");
            response.setHeader(X_RATE_LIMIT_RESET, String.valueOf(data.getResetTime()));
            response.getWriter().write("{\"error\": \"Rate limit exceeded. Try again later.\"}");
            return false;
        }

        // Set rate limit headers
        response.setHeader(X_RATE_LIMIT_LIMIT, String.valueOf(rateLimitConfig.getRequestsPerMinute()));
        response.setHeader(X_RATE_LIMIT_REMAINING, String.valueOf(data.getRemainingRequests(rateLimitConfig.getRequestsPerMinute())));
        response.setHeader(X_RATE_LIMIT_RESET, String.valueOf(data.getResetTime()));

        return true;
    }

    /**
     * Get client IP address from request (considers X-Forwarded-For header for proxies)
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            String forwarded = xForwardedFor.split(",")[0].trim();
            if (isLikelyIpAddress(forwarded)) {
                return forwarded;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote.trim();
    }

    private String resolveRateLimitKey(HttpServletRequest request, String endpoint, String clientIp) {
        if (isAgentEndpoint(endpoint)) {
            Object attr = request.getAttribute("agentMac");
            if (attr != null) {
                String mac = normalizeMac(String.valueOf(attr));
                if (!mac.isEmpty()) {
                    return "agent:" + mac;
                }
            }
        }
        return "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim());
    }

    private String normalizeMac(String mac) {
        if (mac == null || mac.isBlank()) {
            return "";
        }
        return mac.replace(":", "")
                .replace("-", "")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private boolean isAgentEndpoint(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        return endpoint.startsWith("/api/v1/agent/")
                || endpoint.startsWith("/api/v1/heartbeat")
                || endpoint.matches("^/api/v1/scripts/\\d+/download$");
    }

    private boolean isLikelyIpAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String candidate = value.trim();
        if (candidate.contains(":")) {
            return candidate.matches("^[0-9a-fA-F:]+$");
        }
        if (!candidate.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            return false;
        }
        String[] parts = candidate.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int valueInt = Integer.parseInt(part);
                if (valueInt < 0 || valueInt > 255) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private void maybeCleanupLimiters() {
        long tick = cleanupTicker.incrementAndGet();
        if ((tick % CLEANUP_INTERVAL_REQUESTS) != 0 && clientLimiter.size() <= MAX_TRACKED_CLIENTS) {
            return;
        }

        long idleCutoff = System.currentTimeMillis() - STALE_ENTRY_TTL_MILLIS;
        clientLimiter.entrySet().removeIf(entry -> entry.getValue().isIdleSince(idleCutoff));
        if (clientLimiter.size() <= MAX_TRACKED_CLIENTS) {
            return;
        }

        List<Map.Entry<String, ClientRateLimitData>> snapshot = new ArrayList<>(clientLimiter.entrySet());
        snapshot.sort(Comparator.comparingLong(entry -> entry.getValue().getLastAccess()));
        int toRemove = clientLimiter.size() - MAX_TRACKED_CLIENTS;
        for (int i = 0; i < toRemove && i < snapshot.size(); i++) {
            Map.Entry<String, ClientRateLimitData> entry = snapshot.get(i);
            clientLimiter.remove(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Rate limit 면제 대상 경로.
     *
     * <p>에이전트 heartbeat·명령 폴링은 JWT 에이전트 토큰으로 이미 인증되며,
     * 800대 규모에서 상시 80개 롱폴링 연결이 유지되므로 rate limit을 적용하면
     * 에이전트가 429를 받아 명령 수신 불가 상태가 될 수 있다.
     * 보안 경계는 Rate Limit이 아닌 AgentTokenInterceptor가 담당한다.</p>
     */
    private boolean isExempted(String endpoint) {
        return endpoint.contains("/health") ||
               endpoint.contains("/swagger") ||
               endpoint.contains("/api-docs") ||
               endpoint.contains("/actuator") ||
               endpoint.contains("/ip-ranges") ||           // IP 범위 조회 (빈번한 에이전트 요청)
               endpoint.startsWith("/api/v1/heartbeat") ||  // 에이전트 heartbeat (JWT 인증, 120초 주기)
               endpoint.startsWith("/api/v1/agent/commands"); // 에이전트 롱폴링 (JWT 인증, 상시 연결)
    }

    /**
     * Data class tracking rate limit per client
     */
    private static class ClientRateLimitData {
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        public synchronized boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            lastAccess.set(now);
            long windowElapsed = now - windowStart.get();

            // Reset window if expired
            if (windowElapsed > WINDOW_SIZE_MILLIS) {
                windowStart.set(now);
                requestCount.set(0);
            }

            // Atomic check-and-increment
            if (requestCount.get() >= limit) {
                return false;
            }

            requestCount.incrementAndGet();
            return true;
        }

        public int getRemainingRequests(int limit) {
            long now = System.currentTimeMillis();
            lastAccess.set(now);
            if (now - windowStart.get() > WINDOW_SIZE_MILLIS) {
                return limit;
            }
            return Math.max(0, limit - requestCount.get());
        }

        public long getResetTime() {
            long resetTime = windowStart.get() + WINDOW_SIZE_MILLIS;
            return Math.max(resetTime, System.currentTimeMillis());
        }

        public boolean isIdleSince(long cutoffMillis) {
            return lastAccess.get() < cutoffMillis;
        }

        public long getLastAccess() {
            return lastAccess.get();
        }
    }
}
