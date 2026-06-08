package com.example.backend.service.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock UserRoleResolver userRoleResolver;
    @InjectMocks AuthService authService;

    @Test
    void loginReturnsTokenForValidCredentials() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "admin");
        ReflectionTestUtils.setField(request, "password", "secret123");
        User user = new User("admin", "encoded", "admin@example.com");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken("admin")).thenReturn("jwt-token");
        when(userRoleResolver.resolveRole("admin")).thenReturn("ADMIN");

        LoginResponse response = authService.login(request);

        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void loginUsesFriendlyMessageForInvalidCredentials() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "admin");
        ReflectionTestUtils.setField(request, "password", "wrong-password");
        User user = new User("admin", "encoded", "admin@example.com");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
    }
}
