package com.example.backend.remotecontrol;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VncRelayHandshakeInterceptorTest {

    @Test
    void consumesTicketAndStoresTrustedGrant() {
        RemoteControlSessionService sessions = mock(RemoteControlSessionService.class);
        VncRelayHandshakeInterceptor interceptor = new VncRelayHandshakeInterceptor(sessions);
        UUID sessionId = UUID.randomUUID();
        RemoteControlRelayGrant grant = mock(RemoteControlRelayGrant.class);
        when(sessions.consumeTicket(sessionId, "once")).thenReturn(grant);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://dashboard/vnc-relay/" + sessionId + "?ticket=once"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(VncRelayHandshakeInterceptor.GRANT_ATTRIBUTE)).isSameAs(grant);
        verify(sessions).consumeTicket(sessionId, "once");
    }

    @Test
    void rejectsMalformedSessionPathBeforeRelay() {
        VncRelayHandshakeInterceptor interceptor =
                new VncRelayHandshakeInterceptor(mock(RemoteControlSessionService.class));
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://dashboard/vnc-relay/not-a-uuid?ticket=once"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        assertThat(interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void closesConsumedSessionWhenWebSocketHandshakeFails() {
        RemoteControlSessionService sessions = mock(RemoteControlSessionService.class);
        VncRelayHandshakeInterceptor interceptor = new VncRelayHandshakeInterceptor(sessions);
        UUID sessionId = UUID.randomUUID();
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://dashboard/vnc-relay/" + sessionId));

        interceptor.afterHandshake(
                request,
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                new IllegalStateException("upgrade failed"));

        verify(sessions).connectionFailed(sessionId, "Remote-control WebSocket handshake failed");
    }
}
