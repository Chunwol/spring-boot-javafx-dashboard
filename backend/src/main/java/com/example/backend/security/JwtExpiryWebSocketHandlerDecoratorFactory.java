package com.example.backend.security;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

@Component
public class JwtExpiryWebSocketHandlerDecoratorFactory implements WebSocketHandlerDecoratorFactory {

    private final WebSocketSessionExpiryRegistry expiryRegistry;

    public JwtExpiryWebSocketHandlerDecoratorFactory(WebSocketSessionExpiryRegistry expiryRegistry) {
        this.expiryRegistry = expiryRegistry;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                expiryRegistry.register(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    expiryRegistry.unregister(session.getId());
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    expiryRegistry.unregister(session.getId());
                }
            }
        };
    }
}
