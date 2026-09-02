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
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int TEMPORARY_LOCK_THRESHOLD = 5;
    private static final int DISABLE_THRESHOLD = 10;
    private static final int TEMPORARY_LOCK_MINUTES = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserRoleResolver userRoleResolver;
    private final UserFeaturePermissionService featurePermissionService;
    private final AuditService auditService;
    private final WebSocketSessionExpiryRegistry webSocketSessions;
    private final UserLoginSessionService loginSessions;
    private final RemoteControlSessionService remoteControlSessions;

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse login(LoginRequest request, String ipAddress) {
        return login(request, ipAddress, null);
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String safeUsername = sanitize(request.getUsername(), 50);
        String safeIpAddress = sanitize(ipAddress, 64);
        String safeUserAgent = sanitize(userAgent, 500);
        var user = userRepository.findByUsernameForLogin(request.getUsername()).orElse(null);
        if (user == null) {
            auditService.logAccess(safeUsername, "LOGIN_FAILED", "USER", safeUsername,
                    "{\"reason\":\"UNKNOWN_USERNAME\"}", safeIpAddress, safeUserAgent);
            throw invalidCredentials();
        }
        LocalDateTime now = LocalDateTime.now();

        if (!user.isActive()) {
            auditService.logAccess(user.getUsername(), "LOGIN_BLOCKED", "USER", user.getUsername(),
                    "{\"reason\":\"ACCOUNT_DISABLED\"}", safeIpAddress, safeUserAgent);
            throw new UnauthorizedException("비활성화된 계정입니다. 관리자에게 문의해 주세요.");
        }
        if (user.isLoginLockedAt(now)) {
            auditService.logAccess(user.getUsername(), "LOGIN_BLOCKED", "USER", user.getUsername(),
                    "{\"reason\":\"TEMPORARY_LOCK\",\"failedAttempts\":" + user.getFailedLoginAttempts() + "}",
                    safeIpAddress, safeUserAgent);
            throw new UnauthorizedException("로그인 실패 횟수 초과로 로그인이 잠시 제한되어 있습니다.");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            handleFailedLogin(user, now, safeIpAddress, safeUserAgent);
        }

        user.resetLoginFailures();
        if (user.getActiveSessionId() != null) {
            auditService.logAccess(user.getUsername(), "LOGOUT", "USER", user.getUsername(),
                    "{\"reason\":\"SESSION_REPLACED\"}", user.getLoginIpAddress(), user.getLoginUserAgent());
        }
        webSocketSessions.closeUserSessions(user.getUsername());
        remoteControlSessions.closeOwnerSessions(user.getUsername());
        LocalDateTime loggedInAt = LocalDateTime.now();
        String sessionId = UUID.randomUUID().toString();
        user.startLoginSession(sessionId, safeIpAddress, safeUserAgent, loggedInAt);
        String token = jwtUtil.generateToken(user.getUsername(), sessionId);
        String role = userRoleResolver.resolveRole(user);
        auditService.logAccess(user.getUsername(), "LOGIN_SUCCESS", "USER", user.getUsername(),
                "{\"singleSession\":true}", safeIpAddress, safeUserAgent);
        return LoginResponse.of(user.getUsername(), token, role, featurePermissionService.resolveCodes(user, role));
    }

    public String issueRefreshToken(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("자동 로그인 정보가 유효하지 않습니다."));
        if (user.getActiveSessionId() == null) {
            throw new UnauthorizedException("자동 로그인 정보가 유효하지 않습니다.");
        }
        return jwtUtil.generateRefreshToken(username, user.getActiveSessionId());
    }

    public LoginResponse refresh(String refreshToken) {
        return refresh(refreshToken, null, null);
    }

    @Transactional
    public LoginResponse refresh(String refreshToken, String ipAddress, String userAgent) {
        try {
            var claims = jwtUtil.validateRefreshAndGetClaims(refreshToken);
            String username = claims.getSubject();
            String sessionId = jwtUtil.getSessionId(claims);
            var user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UnauthorizedException("자동 로그인 정보가 유효하지 않습니다."));
            if (!user.isActive()) {
                throw new UnauthorizedException("비활성화된 계정입니다. 관리자에게 문의해 주세요.");
            }
            if (!user.hasLoginSession(sessionId)) {
                throw new UnauthorizedException("다른 장소에서 새로 로그인되어 현재 로그인이 종료되었습니다.");
            }
            user.touchLoginSession(sessionId, LocalDateTime.now());
            if (ipAddress != null && !ipAddress.isBlank()) user.setLoginIpAddress(sanitize(ipAddress, 64));
            if (userAgent != null && !userAgent.isBlank()) user.setLoginUserAgent(sanitize(userAgent, 500));
            String token = jwtUtil.generateToken(user.getUsername(), sessionId);
            String role = userRoleResolver.resolveRole(user);
            return LoginResponse.of(user.getUsername(), token, role, featurePermissionService.resolveCodes(user, role));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("자동 로그인 정보가 만료되었거나 유효하지 않습니다.");
        }
    }

    public void logout(String accessToken, String refreshToken, String ipAddress, String userAgent) {
        Claims claims = null;
        try {
            if (accessToken != null && !accessToken.isBlank()) {
                claims = jwtUtil.validateAndGetClaims(accessToken);
            }
        } catch (JwtException | IllegalArgumentException ignored) {
        }
        if (claims == null) {
            try {
                if (refreshToken != null && !refreshToken.isBlank()) {
                    claims = jwtUtil.validateRefreshAndGetClaims(refreshToken);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                return;
            }
        }
        if (claims == null) return;
        String username = claims.getSubject();
        String sessionId = jwtUtil.getSessionId(claims);
        if (loginSessions.revoke(username, sessionId)) {
            auditService.logAccess(username, "LOGOUT", "USER", username,
                    "{\"reason\":\"USER_REQUEST\"}", sanitize(ipAddress, 64), sanitize(userAgent, 500));
            webSocketSessions.closeUserSessions(username);
            remoteControlSessions.closeOwnerSessions(username);
        }
    }

    private void handleFailedLogin(User user,
                                   LocalDateTime now,
                                   String ipAddress,
                                   String userAgent) {
        int failures = user.recordFailedLogin();
        auditService.logAccess(user.getUsername(), "LOGIN_FAILED", "USER", user.getUsername(),
                "{\"reason\":\"WRONG_PASSWORD\",\"failedAttempts\":" + failures + "}",
                ipAddress, userAgent);
        if (failures >= DISABLE_THRESHOLD) {
            user.deactivate();
            auditService.log(user.getUsername(), "LOGIN_ACCOUNT_DISABLED", "USER", user.getUsername(),
                    "{\"failedAttempts\":10,\"reason\":\"PASSWORD_FAILURE_LIMIT\"}", ipAddress);
            webSocketSessions.closeUserSessions(user.getUsername());
            remoteControlSessions.closeOwnerSessions(user.getUsername());
            throw new UnauthorizedException("비밀번호 오류 10회로 계정이 비활성화되었습니다. 관리자에게 문의해 주세요.");
        }
        if (failures == TEMPORARY_LOCK_THRESHOLD) {
            LocalDateTime lockedUntil = now.plusMinutes(TEMPORARY_LOCK_MINUTES);
            user.lockLoginUntil(lockedUntil);
            auditService.log(user.getUsername(), "LOGIN_TEMPORARILY_LOCKED", "USER", user.getUsername(),
                    "{\"failedAttempts\":5,\"lockedMinutes\":5,\"lockedUntil\":\"" + lockedUntil + "\"}",
                    ipAddress);
            throw new UnauthorizedException("비밀번호 오류 5회로 5분간 로그인이 제한됩니다.");
        }
        throw invalidCredentials();
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) return null;
        String sanitized = value.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (sanitized.isBlank()) return null;
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }
}
