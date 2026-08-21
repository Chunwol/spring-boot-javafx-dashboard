package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserRoleResolverTest {

    @Test
    void resolveRoleUsesStoredDbRoleBeforeConfiguredUsers() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver resolver = new UserRoleResolver(userRepository, "admin", "viewer", "", "");
        User user = new User("admin", "encoded", "admin@example.com", "VIEWER");

        assertThat(resolver.resolveRole(user)).isEqualTo("VIEWER");
    }

    @Test
    void resolveRoleByUsernameUsesStoredDbRole() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver resolver = new UserRoleResolver(userRepository, "admin", "", "", "");
        User user = new User("operator", "encoded", "operator@example.com", "VIEWER");

        when(userRepository.findByUsername("operator")).thenReturn(Optional.of(user));

        assertThat(resolver.resolveRole("operator")).isEqualTo("VIEWER");
    }

    @Test
    void resolveRoleFallsBackToConfiguredUsersWhenDbRoleIsBlank() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver resolver = new UserRoleResolver(userRepository, "admin", "viewer", "", "");
        User user = new User("viewer", "encoded", "viewer@example.com", null);

        assertThat(resolver.resolveRole(user)).isEqualTo("VIEWER");
    }
}
