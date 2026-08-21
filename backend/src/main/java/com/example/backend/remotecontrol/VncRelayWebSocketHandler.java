package com.example.backend.remotecontrol;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class VncRelayWebSocketHandler extends BinaryWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(VncRelayWebSocketHandler.class);
    private static final CloseStatus RELAY_ERROR = new CloseStatus(1011, "VNC relay unavailable");

    private final RemoteControlSessionService sessionService;
    private final RemoteControlRelayProperties properties;
    private final RemoteControlRelayRegistry relayRegistry;
    private final Map<String, RelayConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService readers;
    private final ScheduledThreadPoolExecutor timeouts;

    public VncRelayWebSocketHandler(
            @Lazy RemoteControlSessionService sessionService,
            RemoteControlRelayProperties properties,
            RemoteControlRelayRegistry relayRegistry) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.relayRegistry = relayRegistry;
        this.readers = Executors.newFixedThreadPool(
                Math.max(1, properties.getMaxSessions()),
                Thread.ofPlatform().daemon().name("vnc-relay-reader-", 0).factory());
        this.timeouts = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("vnc-relay-timeout-", 0).factory());
        this.timeouts.setRemoveOnCancelPolicy(true);
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("binary");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) throws Exception {
        Object value = webSocket.getAttributes().get(VncRelayHandshakeInterceptor.GRANT_ATTRIBUTE);
        if (!(value instanceof RemoteControlRelayGrant grant)) {
            webSocket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Socket tcp = new Socket();
        RelayConnection connection = null;
        try {
            webSocket.setBinaryMessageSizeLimit(Math.max(1024, properties.getMaxClientMessageBytes()));
            webSocket.setTextMessageSizeLimit(1024);
            tcp.connect(new InetSocketAddress(grant.targetAddress(), grant.targetPort()),
                    durationMillis(properties.getTcpConnectTimeout()));
            tcp.setSoTimeout(durationMillis(properties.getTcpIdleTimeout()));
            tcp.setTcpNoDelay(true);
            RelayConnection established = new RelayConnection(grant, webSocket, tcp);
            connection = established;
            established.closeAction = () -> closeConnection(established, CloseStatus.NORMAL);
            connections.put(webSocket.getId(), established);
            relayRegistry.register(grant.sessionId(), established.closeAction);
            sessionService.markConnected(grant.sessionId());
            readers.submit(() -> readFromVnc(established));
            long remainingMillis = Math.max(1L, Duration.between(Instant.now(), grant.sessionExpiresAt()).toMillis());
            established.setExpirationFuture(timeouts.schedule(
                    established.closeAction, remainingMillis, TimeUnit.MILLISECONDS));
        } catch (Exception exception) {
            if (connection != null && connections.containsKey(webSocket.getId())) {
                closeConnection(connection, RELAY_ERROR);
            } else {
                closeQuietly(tcp);
                String reason = "Target VNC server refused the relay connection";
                sessionService.connectionFailed(grant.sessionId(), reason);
                log.warn("VNC relay could not connect for session {} to {}:{}: {}",
                        grant.sessionId(), grant.targetAddress().getHostAddress(), grant.targetPort(),
                        exception.getMessage());
                closeQuietly(webSocket, RELAY_ERROR);
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocket, BinaryMessage message) throws Exception {
        RelayConnection connection = connections.get(webSocket.getId());
        if (connection == null) {
            webSocket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        int size = message.getPayloadLength();
        if (size > Math.max(1024, properties.getMaxClientMessageBytes())
                || connection.transferred.addAndGet(size) > Math.max(1L, properties.getMaxSessionBytes())) {
            closeConnection(connection, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        synchronized (connection.tcpWriteLock) {
            connection.tcpOutput.write(bytes);
            connection.tcpOutput.flush();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        closeQuietly(session, CloseStatus.NOT_ACCEPTABLE.withReason("Binary RFB frames required"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        RelayConnection connection = connections.get(session.getId());
        if (connection != null) {
            closeConnection(connection, RELAY_ERROR);
        } else {
            closeQuietly(session, RELAY_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RelayConnection connection = connections.get(session.getId());
        if (connection != null) {
            closeConnection(connection, status);
        }
    }

    private void readFromVnc(RelayConnection connection) {
        byte[] buffer = new byte[Math.max(1024, properties.getTcpReadBufferBytes())];
        try (InputStream input = connection.tcp.getInputStream()) {
            int read;
            while (!connection.closed.get() && (read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (connection.transferred.addAndGet(read) > Math.max(1L, properties.getMaxSessionBytes())) {
                    closeConnection(connection, CloseStatus.TOO_BIG_TO_PROCESS);
                    return;
                }
                byte[] frame = Arrays.copyOf(buffer, read);
                synchronized (connection.webSocketWriteLock) {
                    if (!connection.webSocket.isOpen()) {
                        closeConnection(connection, CloseStatus.NORMAL);
                        return;
                    }
                    connection.webSocket.sendMessage(new BinaryMessage(frame));
                }
            }
            closeConnection(connection, CloseStatus.NORMAL);
        } catch (SocketTimeoutException exception) {
            closeConnection(connection, new CloseStatus(1001, "VNC relay idle timeout"));
        } catch (IOException exception) {
            if (!connection.closed.get()) {
                log.debug("VNC relay read failed for session {}: {}",
                        connection.grant.sessionId(), exception.getMessage());
                closeConnection(connection, RELAY_ERROR);
            }
        } finally {
            closeConnection(connection, CloseStatus.NORMAL);
        }
    }

    private void closeConnection(RelayConnection connection, CloseStatus status) {
        if (!connection.closed.compareAndSet(false, true)) return;
        connections.remove(connection.webSocket.getId(), connection);
        relayRegistry.unregister(connection.grant.sessionId(), connection.closeAction);
        if (connection.expirationFuture != null) connection.expirationFuture.cancel(false);
        closeQuietly(connection.tcp);
        closeQuietly(connection.webSocket, status);
        sessionService.relayClosed(connection.grant.sessionId());
    }

    private int durationMillis(Duration duration) {
        long millis = duration == null ? 1L : duration.toMillis();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis));
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
        }
    }

    @PreDestroy
    void shutdown() {
        for (RelayConnection connection : connections.values()) {
            closeConnection(connection, CloseStatus.GOING_AWAY);
        }
        readers.shutdownNow();
        timeouts.shutdownNow();
    }

    private static final class RelayConnection {
        private final RemoteControlRelayGrant grant;
        private final WebSocketSession webSocket;
        private final Socket tcp;
        private final OutputStream tcpOutput;
        private final Object tcpWriteLock = new Object();
        private final Object webSocketWriteLock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong transferred = new AtomicLong();
        private volatile Runnable closeAction;
        private volatile ScheduledFuture<?> expirationFuture;

        private RelayConnection(RemoteControlRelayGrant grant, WebSocketSession webSocket, Socket tcp)
                throws IOException {
            this.grant = grant;
            this.webSocket = webSocket;
            this.tcp = tcp;
            this.tcpOutput = tcp.getOutputStream();
        }

        private void setExpirationFuture(ScheduledFuture<?> expirationFuture) {
            this.expirationFuture = expirationFuture;
            if (closed.get()) expirationFuture.cancel(false);
        }
    }
}
