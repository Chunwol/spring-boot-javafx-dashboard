package com.example.backend.controller.auth;

import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "사용자 인증 및 로그인 API")
public class AuthController {

    static final String REFRESH_COOKIE_NAME = "classroom_refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;

    @Value("${app.jwt.refresh-expiration:7776000000}")
    private long refreshExpirationMs;

    @Value("${app.jwt.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

    @PostMapping("/login")
    @Operation(
        summary = "사용자 로그인",
        description = "사용자명과 비밀번호로 인증하고 JWT 토큰을 받습니다. " +
                      "반환된 토큰은 이후 모든 API 요청의 Authorization 헤더에 포함시켜야 합니다."
    )
    @ApiResponse(
        responseCode = "200",
        description = "로그인 성공",
        content = @Content(schema = @Schema(implementation = LoginResponse.class))
    )
    @ApiResponse(responseCode = "400", description = "잘못된 자격증명 (사용자명 또는 비밀번호 오류)")
    @ApiResponse(responseCode = "401", description = "인증 실패")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest,
                                                HttpServletRequest request) {
        LoginResponse response = authService.login(
                loginRequest, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        ResponseCookie cookie = loginRequest.isAutoLogin()
                ? refreshCookie(authService.issueRefreshToken(response.getUsername()))
                : clearRefreshCookie();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "자동 로그인 갱신", description = "HttpOnly 자동 로그인 쿠키를 검증하고 새 접근 토큰을 발급합니다.")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new com.example.backend.common.error.UnauthorizedException("자동 로그인 정보가 없습니다.");
        }
        LoginResponse response = authService.refresh(
                refreshToken, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(authService.issueRefreshToken(response.getUsername())).toString())
                .body(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃", description = "브라우저의 자동 로그인 쿠키를 삭제합니다.")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        String accessToken = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        authService.logout(accessToken, refreshToken,
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
