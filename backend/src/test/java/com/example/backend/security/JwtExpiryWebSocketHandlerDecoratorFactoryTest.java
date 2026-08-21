package com.example.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtExpiryWebSocketHandlerDecoratorFactoryTest {

    @Test
    void tracksOnlyDecoratedStompTransportLifetime() throws Exception {
        WebSocketSessionExpiryRegistry registry = mock(WebSocketSessionExpiryRegistry.class);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        WebSocketHandler decorated =
                new JwtExpiryWebSocketHandlerDecoratorFactory(registry).decorate(delegate);

        decorated.afterConnectionEstablished(session);
        decorated.afterConnectionClosed(session, CloseStatus.NORMAL);

        var order = inOrder(registry, delegate);
        order.verify(registry).register(session);
        order.verify(delegate).afterConnectionEstablished(session);
        order.verify(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);
        order.verify(registry).unregister("session-1");
    }
}
