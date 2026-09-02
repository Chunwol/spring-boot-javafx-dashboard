package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    @Test
    void operatorReceivesOnlyItsSelectedFeatureAuthorities() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver roleResolver = mock(UserRoleResolver.class);
        User user = new User("operator", "encoded", "operator@example.com", "USER");
        user.changeFeaturePermissions("COMMANDS,FILES");
        when(userRepository.findByUsername("operator")).thenReturn(Optional.of(user));
        when(roleResolver.resolveRole(user)).thenReturn("USER");
        CustomUserDetailsService service = new CustomUserDetailsService(
                userRepository, roleResolver, new UserFeaturePermissionService());

        var authorities = service.loadUserByUsername("operator").getAuthorities();

        assertThat(authorities).extracting(authority -> authority.getAuthority())
                .contains("ROLE_USER", "PERM_COMMAND_VIEW", "PERM_FILE_DISTRIBUTION_VIEW")
                .doesNotContain("PERM_TOKEN_VIEW");
    }

    @Test
    void disabledAccountCannotAuthenticateWithAnExistingJwt() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver roleResolver = mock(UserRoleResolver.class);
        User user = new User("disabled-user", "encoded", "disabled@example.com", "USER");
        user.deactivate();
        when(userRepository.findByUsername("disabled-user")).thenReturn(Optional.of(user));
        CustomUserDetailsService service = new CustomUserDetailsService(
                userRepository, roleResolver, new UserFeaturePermissionService());

        assertThatThrownBy(() -> service.loadUserByUsername("disabled-user"))
                .isInstanceOf(DisabledException.class);
    }
}
