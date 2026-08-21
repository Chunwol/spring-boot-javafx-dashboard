package com.example.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionExpiryRegistryTest {

    private WebSocketSessionExpiryRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.shutdown();
    }

    @Test
    void closesIdleTransportWhenJwtExpires() throws Exception {
        registry = new WebSocketSessionExpiryRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        registry.register(session);

        registry.scheduleExpiration("session-1", Instant.now().plusMillis(25));

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session, timeout(1500)).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(status.getValue().getReason()).isEqualTo("JWT expired");
    }

    @Test
    void closesAllTransportsForChangedAccount() throws Exception {
        registry = new WebSocketSessionExpiryRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-account");
        when(session.isOpen()).thenReturn(true);
        registry.register(session);
        registry.bindUser("session-account", "viewer");

        registry.closeUserSessions("VIEWER");

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getReason()).isEqualTo("Account changed");
    }
}
