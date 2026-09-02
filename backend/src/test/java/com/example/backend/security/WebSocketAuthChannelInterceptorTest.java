package com.example.backend.security;

import com.example.backend.service.auth.CustomUserDetailsService;
import com.example.backend.service.auth.UserLoginSessionService;
import com.example.backend.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    @Test
    void authenticatesConnectFrameFromBearerToken() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        WebSocketSessionExpiryRegistry expiryRegistry = mock(WebSocketSessionExpiryRegistry.class);
        Date expiration = Date.from(Instant.now().plusSeconds(60));
        when(jwtUtil.validateAndGetClaims("valid-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("admin");
        when(claims.getExpiration()).thenReturn(expiration);
        when(users.loadUserByUsername("admin")).thenReturn(User.withUsername("admin").password("x").roles("ADMIN").build());
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                jwtUtil, users, expiryRegistry, activeLoginSessions());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer valid-token");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class));

        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor).isNotNull();
        assertThat(resultAccessor.getUser()).isNotNull();
        assertThat(resultAccessor.getUser().getName()).isEqualTo("admin");
        assertThat(resultAccessor.getSessionAttributes())
                .containsEntry(WebSocketAuthChannelInterceptor.JWT_TOKEN_ATTRIBUTE, "valid-token")
                .containsEntry(WebSocketAuthChannelInterceptor.JWT_EXPIRES_AT_ATTRIBUTE, expiration.getTime());
        verify(expiryRegistry).scheduleExpiration("session-1", expiration.toInstant());
        verify(expiryRegistry).bindUser("session-1", "admin");
    }

    @Test
    void rejectsAnonymousConnectFrame() {
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                mock(JwtUtil.class), mock(CustomUserDetailsService.class),
                mock(WebSocketSessionExpiryRegistry.class), activeLoginSessions());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revalidatesTokenForSubscribeAndSend() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(jwtUtil.validateAndGetClaims("stored-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("admin");
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().plusSeconds(60)));
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        User user = (User) User.withUsername("admin").password("x").roles("ADMIN").build();
        when(users.loadUserByUsername("admin")).thenReturn(user);
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                jwtUtil, users, mock(WebSocketSessionExpiryRegistry.class), activeLoginSessions());

        for (StompCommand command : new StompCommand[]{StompCommand.SUBSCRIBE, StompCommand.SEND}) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            accessor.setSessionAttributes(Map.of(
                    WebSocketAuthChannelInterceptor.JWT_TOKEN_ATTRIBUTE, "stored-token",
                    WebSocketAuthChannelInterceptor.JWT_EXPIRES_AT_ATTRIBUTE,
                    Instant.now().plusSeconds(60).toEpochMilli()));
            accessor.setLeaveMutable(true);
            Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThat(interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)))
                    .isSameAs(message);
        }
    }

    @Test
    void rejectsSubscribeAfterStoredExpiration() {
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                mock(JwtUtil.class), mock(CustomUserDetailsService.class),
                mock(WebSocketSessionExpiryRegistry.class), activeLoginSessions());
        User user = (User) User.withUsername("admin").password("x").roles("ADMIN").build();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        accessor.setSessionAttributes(Map.of(
                WebSocketAuthChannelInterceptor.JWT_TOKEN_ATTRIBUTE, "expired-token",
                WebSocketAuthChannelInterceptor.JWT_EXPIRES_AT_ATTRIBUTE,
                Instant.now().minusSeconds(1).toEpochMilli()));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(
                message, mock(org.springframework.messaging.MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsConnectWhenClaimsHaveNoFutureExpiration() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(jwtUtil.validateAndGetClaims("expired-token")).thenReturn(claims);
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().minusSeconds(1)));
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                jwtUtil, mock(CustomUserDetailsService.class), mock(WebSocketSessionExpiryRegistry.class),
                activeLoginSessions());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-expired");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer expired-token");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(
                message, mock(org.springframework.messaging.MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UserLoginSessionService activeLoginSessions() {
        UserLoginSessionService sessions = mock(UserLoginSessionService.class);
        when(sessions.validateAndTouch(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(true);
        return sessions;
    }
}
