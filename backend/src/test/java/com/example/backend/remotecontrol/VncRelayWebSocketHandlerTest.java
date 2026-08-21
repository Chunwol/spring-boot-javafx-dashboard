package com.example.backend.remotecontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VncRelayWebSocketHandlerTest {

    private VncRelayWebSocketHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdown();
    }

    @Test
    void relaysBinaryFramesInBothDirections() throws Exception {
        RemoteControlSessionService sessions = mock(RemoteControlSessionService.class);
        RemoteControlRelayProperties properties = new RemoteControlRelayProperties();
        properties.setTcpIdleTimeout(java.time.Duration.ofSeconds(5));
        handler = new VncRelayWebSocketHandler(sessions, properties, new RemoteControlRelayRegistry());
        UUID sessionId = UUID.randomUUID();

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<byte[]> receivedByVnc = new CompletableFuture<>();
            Thread.ofPlatform().daemon().start(() -> {
                try (Socket socket = server.accept()) {
                    byte[] input = socket.getInputStream().readNBytes(3);
                    receivedByVnc.complete(input);
                    socket.getOutputStream().write(new byte[]{4, 5, 6});
                    socket.getOutputStream().flush();
                    Thread.sleep(100);
                } catch (Exception exception) {
                    receivedByVnc.completeExceptionally(exception);
                }
            });

            RemoteControlRelayGrant grant = new RemoteControlRelayGrant(
                    sessionId,
                    "AA:BB:CC:DD:EE:FF",
                    InetAddress.getLoopbackAddress(),
                    server.getLocalPort(),
                    Instant.now().plusSeconds(10));
            WebSocketSession webSocket = mockWebSocket(grant);

            handler.afterConnectionEstablished(webSocket);
            handler.handleMessage(webSocket, new BinaryMessage(new byte[]{1, 2, 3}));

            assertThat(receivedByVnc.get(2, TimeUnit.SECONDS)).containsExactly(1, 2, 3);
            ArgumentCaptor<WebSocketMessage<?>> outbound = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(webSocket, timeout(2000)).sendMessage(outbound.capture());
            assertThat(((BinaryMessage) outbound.getValue()).getPayload().array()).containsExactly(4, 5, 6);
            verify(sessions).markConnected(sessionId);
            verify(sessions, timeout(2000)).relayClosed(sessionId);
        }
    }

    @Test
    void rejectsConnectionWithoutHandshakeGrant() throws Exception {
        RemoteControlSessionService sessions = mock(RemoteControlSessionService.class);
        handler = new VncRelayWebSocketHandler(sessions, new RemoteControlRelayProperties(), new RemoteControlRelayRegistry());
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getAttributes()).thenReturn(Map.of());

        handler.afterConnectionEstablished(webSocket);

        verify(webSocket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void marksSessionFailedWhenTargetPortRefusesConnection() throws Exception {
        RemoteControlSessionService sessions = mock(RemoteControlSessionService.class);
        RemoteControlRelayProperties properties = new RemoteControlRelayProperties();
        properties.setTcpConnectTimeout(java.time.Duration.ofMillis(200));
        handler = new VncRelayWebSocketHandler(sessions, properties, new RemoteControlRelayRegistry());
        UUID sessionId = UUID.randomUUID();

        int closedPort;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = server.getLocalPort();
        }
        RemoteControlRelayGrant grant = new RemoteControlRelayGrant(
                sessionId,
                "AA:BB:CC:DD:EE:FF",
                InetAddress.getLoopbackAddress(),
                closedPort,
                Instant.now().plusSeconds(10));
        WebSocketSession webSocket = mockWebSocket(grant);

        handler.afterConnectionEstablished(webSocket);
        verify(sessions).connectionFailed(sessionId, "Target VNC server refused the relay connection");
        verify(webSocket).close(any(CloseStatus.class));
    }

    private WebSocketSession mockWebSocket(RemoteControlRelayGrant grant) throws Exception {
        WebSocketSession webSocket = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(webSocket.getId()).thenReturn("ws-1");
        when(webSocket.getAttributes()).thenReturn(Map.of(
                VncRelayHandshakeInterceptor.GRANT_ATTRIBUTE, grant));
        when(webSocket.isOpen()).thenAnswer(ignored -> open.get());
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(webSocket).close(any(CloseStatus.class));
        return webSocket;
    }
}
