package com.example.backend.remotecontrol;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

@Component
public class VncRelayHandshakeInterceptor implements HandshakeInterceptor {

    static final String GRANT_ATTRIBUTE = VncRelayHandshakeInterceptor.class.getName() + ".grant";

    private final RemoteControlSessionService sessionService;

    public VncRelayHandshakeInterceptor(@Lazy RemoteControlSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        try {
            String path = request.getURI().getPath();
            String prefix = "/vnc-relay/";
            if (path == null || !path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) {
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return false;
            }
            UUID sessionId = UUID.fromString(path.substring(prefix.length()));
            MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI())
                    .build().getQueryParams();
            String ticket = query.getFirst("ticket");
            RemoteControlRelayGrant grant = sessionService.consumeTicket(sessionId, ticket);
            attributes.put(GRANT_ATTRIBUTE, grant);
            return true;
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        if (exception == null) return;
        String path = request.getURI().getPath();
        String prefix = "/vnc-relay/";
        if (path == null || !path.startsWith(prefix)) return;
        try {
            sessionService.connectionFailed(
                    UUID.fromString(path.substring(prefix.length())),
                    "Remote-control WebSocket handshake failed");
        } catch (RuntimeException ignored) {
            // The failed handshake has no reusable relay state.
        }
    }
}
