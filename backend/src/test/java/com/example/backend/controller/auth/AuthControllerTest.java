package com.example.backend.controller.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.service.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService);
        ReflectionTestUtils.setField(controller, "refreshExpirationMs", 7776000000L);
        ReflectionTestUtils.setField(controller, "refreshCookieSecure", false);
    }

    @Test
    void autoLoginSetsProtectedRefreshCookie() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "admin");
        ReflectionTestUtils.setField(request, "password", "secret");
        ReflectionTestUtils.setField(request, "autoLogin", true);
        when(authService.login(request)).thenReturn(LoginResponse.of("admin", "access", "ADMIN"));
        when(authService.issueRefreshToken("admin")).thenReturn("refresh");

        ResponseEntity<LoginResponse> response = controller.login(request);

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("classroom_refresh=refresh", "HttpOnly", "SameSite=Strict",
                "Path=/api/v1/auth", "Max-Age=7776000");
    }

    @Test
    void loginWithoutAutoLoginAndLogoutClearCookie() {
        LoginRequest request = new LoginRequest();
        when(authService.login(request)).thenReturn(LoginResponse.of("admin", "access", "ADMIN"));

        assertThat(controller.login(request).getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("classroom_refresh=", "Max-Age=0");
        assertThat(controller.logout().getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("classroom_refresh=", "Max-Age=0");
    }

    @Test
    void refreshRequiresCookieAndRotatesIt() {
        assertThatThrownBy(() -> controller.refresh(null)).isInstanceOf(UnauthorizedException.class);
        when(authService.refresh("old-refresh"))
                .thenReturn(LoginResponse.of("admin", "new-access", "ADMIN"));
        when(authService.issueRefreshToken("admin")).thenReturn("new-refresh");

        ResponseEntity<LoginResponse> response = controller.refresh("old-refresh");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getToken()).isEqualTo("new-access");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("classroom_refresh=new-refresh");
    }
}
