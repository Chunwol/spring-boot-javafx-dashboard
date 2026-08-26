package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    @Test
    void disabledAccountCannotAuthenticateWithAnExistingJwt() {
        UserRepository userRepository = mock(UserRepository.class);
        UserRoleResolver roleResolver = mock(UserRoleResolver.class);
        User user = new User("disabled-user", "encoded", "disabled@example.com", "USER");
        user.deactivate();
        when(userRepository.findByUsername("disabled-user")).thenReturn(Optional.of(user));
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository, roleResolver);

        assertThatThrownBy(() -> service.loadUserByUsername("disabled-user"))
                .isInstanceOf(DisabledException.class);
    }
}
