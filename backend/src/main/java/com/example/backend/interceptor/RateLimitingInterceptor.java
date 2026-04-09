package com.example.backend.interceptor;

import com.example.backend.config.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiting interceptor implementing token bucket algorithm.
 * Tracks requests per client IP and limits request rate.
 */
@Component
public class RateLimitingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingInterceptor.class);
    private static final String X_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String X_RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final long WINDOW_SIZE_MILLIS = 60 * 1000; // 1 minute window

    private final RateLimitConfig rateLimitConfig;
    private final Map<String, ClientRateLimitData> clientLimiter = new ConcurrentHashMap<>();

    public RateLimitingInterceptor(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientIp = getClientIp(request);
        String endpoint = request.getRequestURI();

        // Skip rate limiting for health checks and documentation
        if (isExempted(endpoint)) {
            return true;
        }

        ClientRateLimitData data = clientLimiter.computeIfAbsent(clientIp, k -> new ClientRateLimitData());

        if (!data.tryAcquire(rateLimitConfig.getRequestsPerMinute())) {
            logger.warn("Rate limit exceeded for client: {}, endpoint: {}", clientIp, endpoint);
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
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Check if endpoint should be exempt from rate limiting
     */
    private boolean isExempted(String endpoint) {
        return endpoint.contains("/health") ||
               endpoint.contains("/swagger") ||
               endpoint.contains("/api-docs") ||
               endpoint.contains("/actuator") ||
               endpoint.contains("/ip-ranges");  // Allow frequent IP range lookups
    }

    /**
     * Data class tracking rate limit per client
     */
    private static class ClientRateLimitData {
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        public synchronized boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
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
            if (now - windowStart.get() > WINDOW_SIZE_MILLIS) {
                return limit;
            }
            return Math.max(0, limit - requestCount.get());
        }

        public long getResetTime() {
            long resetTime = windowStart.get() + WINDOW_SIZE_MILLIS;
            return Math.max(resetTime, System.currentTimeMillis());
        }
    }
}
