package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserLoginSessionServiceTest {

    @Test
    void acceptsOnlyTheLatestSessionIdAndTouchesItsActivity() {
        UserRepository users = mock(UserRepository.class);
        User user = new User("admin", "encoded", "admin@example.com");
        LocalDateTime oldActivity = LocalDateTime.now().minusMinutes(2);
        user.startLoginSession("latest", "192.0.2.20", "browser", oldActivity);
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));
        UserLoginSessionService sessions = new UserLoginSessionService(users);

        assertThat(sessions.validateAndTouch("admin", "old")).isFalse();
        assertThat(sessions.validateAndTouch("admin", "latest")).isTrue();
        assertThat(user.getLastActivityAt()).isAfter(oldActivity);
    }

    @Test
    void staleLogoutCannotRevokeTheNewSession() {
        UserRepository users = mock(UserRepository.class);
        User user = new User("admin", "encoded", "admin@example.com");
        user.startLoginSession("latest", "192.0.2.20", "browser", LocalDateTime.now());
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));
        UserLoginSessionService sessions = new UserLoginSessionService(users);

        assertThat(sessions.revoke("admin", "old")).isFalse();
        assertThat(user.getActiveSessionId()).isEqualTo("latest");
        assertThat(sessions.revoke("admin", "latest")).isTrue();
        assertThat(user.getActiveSessionId()).isNull();
    }
}
