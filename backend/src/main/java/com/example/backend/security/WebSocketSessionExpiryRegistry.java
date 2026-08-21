package com.example.backend.security;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class WebSocketSessionExpiryRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionExpiryRegistry.class);
    private static final CloseStatus JWT_EXPIRED =
            CloseStatus.POLICY_VIOLATION.withReason("JWT expired");
    private static final CloseStatus ACCOUNT_CHANGED =
            CloseStatus.POLICY_VIOLATION.withReason("Account changed");

    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;

    public WebSocketSessionExpiryRegistry() {
        scheduler = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("stomp-jwt-expiry-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void register(WebSocketSession session) {
        TrackedSession replacement = new TrackedSession(session);
        TrackedSession previous = sessions.put(session.getId(), replacement);
        if (previous != null) {
            previous.cancelExpiration();
        }
    }

    public void scheduleExpiration(String sessionId, Instant expiresAt) {
        if (sessionId == null || expiresAt == null) {
            throw new IllegalArgumentException("WebSocket session and JWT expiration are required");
        }
        TrackedSession tracked = sessions.get(sessionId);
        if (tracked == null) {
            throw new IllegalStateException("WebSocket transport session is not registered");
        }
        long delayMillis = Math.max(0L, Duration.between(Instant.now(), expiresAt).toMillis());
        synchronized (tracked) {
            tracked.cancelExpiration();
            tracked.expiration = scheduler.schedule(
                    () -> expire(sessionId, tracked), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void bindUser(String sessionId, String username) {
        if (sessionId == null || username == null || username.isBlank()) {
            throw new IllegalArgumentException("WebSocket session and username are required");
        }
        TrackedSession tracked = sessions.get(sessionId);
        if (tracked == null) {
            throw new IllegalStateException("WebSocket transport session is not registered");
        }
        tracked.username = username;
    }

    public void closeUserSessions(String username) {
        if (username == null || username.isBlank()) return;
        for (Map.Entry<String, TrackedSession> item : sessions.entrySet()) {
            TrackedSession tracked = item.getValue();
            if (tracked.username != null && tracked.username.equalsIgnoreCase(username)
                    && sessions.remove(item.getKey(), tracked)) {
                close(tracked, ACCOUNT_CHANGED);
            }
        }
    }

    public void unregister(String sessionId) {
        if (sessionId == null) return;
        TrackedSession tracked = sessions.remove(sessionId);
        if (tracked != null) {
            tracked.cancelExpiration();
        }
    }

    private void expire(String sessionId, TrackedSession expected) {
        if (!sessions.remove(sessionId, expected)) return;
        close(expected, JWT_EXPIRED);
    }

    private void close(TrackedSession expected, CloseStatus status) {
        expected.cancelExpiration();
        try {
            if (expected.session.isOpen()) {
                expected.session.close(status);
            }
        } catch (IOException exception) {
            log.debug("Failed to close STOMP session {}: {}", expected.session.getId(), exception.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        for (TrackedSession tracked : sessions.values()) {
            tracked.cancelExpiration();
        }
        sessions.clear();
        scheduler.shutdownNow();
    }

    private static final class TrackedSession {
        private final WebSocketSession session;
        private volatile String username;
        private ScheduledFuture<?> expiration;

        private TrackedSession(WebSocketSession session) {
            this.session = session;
        }

        private synchronized void cancelExpiration() {
            if (expiration != null) {
                expiration.cancel(false);
                expiration = null;
            }
        }
    }
}
