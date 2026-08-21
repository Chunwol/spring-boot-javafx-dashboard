package com.example.backend.security;

import com.example.backend.service.auth.CustomUserDetailsService;
import com.example.backend.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    static final String JWT_TOKEN_ATTRIBUTE = WebSocketAuthChannelInterceptor.class.getName() + ".token";
    static final String JWT_EXPIRES_AT_ATTRIBUTE = WebSocketAuthChannelInterceptor.class.getName() + ".expiresAt";

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final WebSocketSessionExpiryRegistry expiryRegistry;

    public WebSocketAuthChannelInterceptor(
            JwtUtil jwtUtil,
            CustomUserDetailsService userDetailsService,
            WebSocketSessionExpiryRegistry expiryRegistry) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.expiryRegistry = expiryRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                || StompCommand.SEND.equals(accessor.getCommand())) {
            revalidateSession(accessor);
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AccessDeniedException("WebSocket bearer token required");
        }
        try {
            String token = authorization.substring(7);
            Claims claims = jwtUtil.validateAndGetClaims(token);
            Instant expiresAt = requireFutureExpiration(claims);
            UserDetails user = userDetailsService.loadUserByUsername(claims.getSubject());
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes == null) {
                sessionAttributes = new ConcurrentHashMap<>();
                accessor.setSessionAttributes(sessionAttributes);
            }
            sessionAttributes.put(JWT_TOKEN_ATTRIBUTE, token);
            sessionAttributes.put(JWT_EXPIRES_AT_ATTRIBUTE, expiresAt.toEpochMilli());
            expiryRegistry.bindUser(accessor.getSessionId(), user.getUsername());
            expiryRegistry.scheduleExpiration(accessor.getSessionId(), expiresAt);
        } catch (Exception exception) {
            throw new AccessDeniedException("Invalid WebSocket bearer token", exception);
        }
    }

    private void revalidateSession(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("WebSocket authentication required");
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object tokenValue = attributes == null ? null : attributes.get(JWT_TOKEN_ATTRIBUTE);
        Object expiresAtValue = attributes == null ? null : attributes.get(JWT_EXPIRES_AT_ATTRIBUTE);
        if (!(tokenValue instanceof String token) || !(expiresAtValue instanceof Number expirationMillis)) {
            throw new AccessDeniedException("WebSocket authentication state is missing");
        }
        if (!Instant.now().isBefore(Instant.ofEpochMilli(expirationMillis.longValue()))) {
            throw new AccessDeniedException("WebSocket bearer token expired");
        }
        try {
            Claims claims = jwtUtil.validateAndGetClaims(token);
            requireFutureExpiration(claims);
            if (!accessor.getUser().getName().equals(claims.getSubject())) {
                throw new AccessDeniedException("WebSocket token subject mismatch");
            }
            UserDetails currentUser = userDetailsService.loadUserByUsername(claims.getSubject());
            if (!(accessor.getUser() instanceof Authentication authentication)
                    || !new HashSet<>(authentication.getAuthorities())
                    .equals(new HashSet<>(currentUser.getAuthorities()))) {
                throw new AccessDeniedException("WebSocket account permissions changed");
            }
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AccessDeniedException("Invalid WebSocket bearer token", exception);
        }
    }

    private Instant requireFutureExpiration(Claims claims) {
        Date expiration = claims == null ? null : claims.getExpiration();
        if (expiration == null || !Instant.now().isBefore(expiration.toInstant())) {
            throw new AccessDeniedException("WebSocket bearer token expired");
        }
        return expiration.toInstant();
    }
}
