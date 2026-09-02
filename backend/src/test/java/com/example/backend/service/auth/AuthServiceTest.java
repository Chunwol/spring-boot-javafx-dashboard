package com.example.backend.service.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.model.user.User;
import com.example.backend.remotecontrol.RemoteControlSessionService;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.security.WebSocketSessionExpiryRegistry;
import com.example.backend.service.audit.AuditService;
import com.example.backend.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock UserRoleResolver userRoleResolver;
    @Mock UserFeaturePermissionService featurePermissionService;
    @Mock AuditService auditService;
    @Mock WebSocketSessionExpiryRegistry webSocketSessions;
    @Mock UserLoginSessionService loginSessions;
    @Mock RemoteControlSessionService remoteControlSessions;
    @InjectMocks AuthService authService;

    @Test
    void loginReturnsTokenForValidCredentials() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "admin");
        ReflectionTestUtils.setField(request, "password", "secret123");
        User user = new User("admin", "encoded", "admin@example.com");

        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("jwt-token");
        when(userRoleResolver.resolveRole(user)).thenReturn("ADMIN");

        LoginResponse response = authService.login(request, null);

        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(user.getActiveSessionId()).isNotBlank();
    }

    @Test
    void secondLoginReplacesTheOnlyActiveSession() {
        LoginRequest request = loginRequest("admin", "secret123");
        User user = new User("admin", "encoded", "admin@example.com");
        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "jwt-" + invocation.getArgument(1));
        when(userRoleResolver.resolveRole(user)).thenReturn("ADMIN");

        authService.login(request, "192.0.2.20", "first-browser");
        String firstSession = user.getActiveSessionId();
        authService.login(request, "192.0.2.21", "second-browser");

        assertThat(user.getActiveSessionId()).isNotEqualTo(firstSession);
        assertThat(user.getLoginIpAddress()).isEqualTo("192.0.2.21");
        assertThat(user.getLoginUserAgent()).isEqualTo("second-browser");
        verify(webSocketSessions, org.mockito.Mockito.times(2)).closeUserSessions("admin");
        verify(remoteControlSessions, org.mockito.Mockito.times(2)).closeOwnerSessions("admin");
    }

    @Test
    void loginUsesFriendlyMessageForInvalidCredentials() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "admin");
        ReflectionTestUtils.setField(request, "password", "wrong-password");
        User user = new User("admin", "encoded", "admin@example.com");

        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    void fifthPasswordFailureLocksForFiveMinutesAndWritesAuditLog() {
        LoginRequest request = loginRequest("admin", "wrong-password");
        User user = new User("admin", "encoded", "admin@example.com");
        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> authService.login(request, "192.0.2.10"))
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        LocalDateTime beforeFifthAttempt = LocalDateTime.now();

        assertThatThrownBy(() -> authService.login(request, "192.0.2.10"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("비밀번호 오류 5회로 5분간 로그인이 제한됩니다.");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLoginLockedUntil()).isAfterOrEqualTo(beforeFifthAttempt.plusMinutes(5));
        verify(auditService).log(
                org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq("LOGIN_TEMPORARILY_LOCKED"),
                org.mockito.ArgumentMatchers.eq("USER"),
                org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.contains("\"failedAttempts\":5"),
                org.mockito.ArgumentMatchers.eq("192.0.2.10"));
    }

    @Test
    void activeTemporaryLockRejectsEvenCorrectPasswordWithoutAddingFailure() {
        LoginRequest request = loginRequest("admin", "correct-password");
        User user = new User("admin", "encoded", "admin@example.com");
        user.setFailedLoginAttempts(5);
        user.setLoginLockedUntil(LocalDateTime.now().plusMinutes(4));
        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request, "192.0.2.11"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 실패 횟수 초과로 로그인이 잠시 제한되어 있습니다.");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        verify(passwordEncoder, never()).matches("correct-password", "encoded");
    }

    @Test
    void tenthPasswordFailureDisablesAccountAndClosesExistingSessions() {
        LoginRequest request = loginRequest("admin", "wrong-password");
        User user = new User("admin", "encoded", "admin@example.com");
        user.setFailedLoginAttempts(9);
        user.setLoginLockedUntil(LocalDateTime.now().minusSeconds(1));
        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, "192.0.2.12"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("비밀번호 오류 10회로 계정이 비활성화되었습니다. 관리자에게 문의해 주세요.");

        assertThat(user.isActive()).isFalse();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(10);
        verify(auditService).log("admin", "LOGIN_ACCOUNT_DISABLED", "USER", "admin",
                "{\"failedAttempts\":10,\"reason\":\"PASSWORD_FAILURE_LIMIT\"}", "192.0.2.12");
        verify(webSocketSessions).closeUserSessions("admin");
    }

    @Test
    void successfulLoginAfterRestrictionExpiresResetsFailureCount() {
        LoginRequest request = loginRequest("admin", "secret123");
        User user = new User("admin", "encoded", "admin@example.com");
        user.setFailedLoginAttempts(5);
        user.setLoginLockedUntil(LocalDateTime.now().minusSeconds(1));
        when(userRepository.findByUsernameForLogin("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("jwt-token");
        when(userRoleResolver.resolveRole(user)).thenReturn("ADMIN");

        authService.login(request, "192.0.2.13");

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLoginLockedUntil()).isNull();
    }

    @Test
    void refreshRotatesAccessTokenForExistingUser() {
        User user = new User("admin", "encoded", "admin@example.com");
        user.startLoginSession("session-1", "192.0.2.10", "browser", LocalDateTime.now());
        when(jwtUtil.validateRefreshAndGetClaims("refresh-token"))
                .thenReturn(Jwts.claims().setSubject("admin"));
        when(jwtUtil.getSessionId(org.mockito.ArgumentMatchers.any())).thenReturn("session-1");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("admin", "session-1")).thenReturn("new-access-token");
        when(userRoleResolver.resolveRole(user)).thenReturn("ADMIN");

        LoginResponse response = authService.refresh("refresh-token");

        assertThat(response.getToken()).isEqualTo("new-access-token");
        assertThat(response.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void refreshRejectsInvalidTokenWithFriendlyMessage() {
        when(jwtUtil.validateRefreshAndGetClaims("invalid-token"))
                .thenThrow(new io.jsonwebtoken.MalformedJwtException("invalid"));

        assertThatThrownBy(() -> authService.refresh("invalid-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("자동 로그인 정보가 만료되었거나 유효하지 않습니다.");
    }

    @Test
    void refreshRejectsTokenFromAReplacedLoginSession() {
        User user = new User("admin", "encoded", "admin@example.com");
        user.startLoginSession("new-session", "192.0.2.21", "browser", LocalDateTime.now());
        when(jwtUtil.validateRefreshAndGetClaims("old-refresh"))
                .thenReturn(Jwts.claims().setSubject("admin"));
        when(jwtUtil.getSessionId(org.mockito.ArgumentMatchers.any())).thenReturn("old-session");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("다른 장소에서 새로 로그인되어 현재 로그인이 종료되었습니다.");
    }

    @Test
    void refreshRejectsDisabledAccount() {
        User user = new User("admin", "encoded", "admin@example.com");
        user.deactivate();
        when(jwtUtil.validateRefreshAndGetClaims("refresh-token"))
                .thenReturn(Jwts.claims().setSubject("admin"));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("비활성화된 계정입니다. 관리자에게 문의해 주세요.");
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", username);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }
}
