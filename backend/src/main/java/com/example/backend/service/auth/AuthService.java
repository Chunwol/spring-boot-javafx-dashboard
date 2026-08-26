package com.example.backend.service.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.security.WebSocketSessionExpiryRegistry;
import com.example.backend.service.audit.AuditService;
import com.example.backend.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    private final AuditService auditService;
    private final WebSocketSessionExpiryRegistry webSocketSessions;

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse login(LoginRequest request, String ipAddress) {
        var user = userRepository.findByUsernameForLogin(request.getUsername())
                .orElseThrow(() -> invalidCredentials());
        LocalDateTime now = LocalDateTime.now();

        if (!user.isActive()) {
            throw new UnauthorizedException("비활성화된 계정입니다. 관리자에게 문의해 주세요.");
        }
        if (user.isLoginLockedAt(now)) {
            throw new UnauthorizedException("로그인 실패 횟수 초과로 로그인이 잠시 제한되어 있습니다.");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            handleFailedLogin(user, now, ipAddress);
        }

        user.resetLoginFailures();
        String token = jwtUtil.generateToken(user.getUsername());
        String role = userRoleResolver.resolveRole(user);
        return LoginResponse.of(user.getUsername(), token, role);
    }

    public String issueRefreshToken(String username) {
        return jwtUtil.generateRefreshToken(username);
    }

    public LoginResponse refresh(String refreshToken) {
        try {
            String username = jwtUtil.validateRefreshAndGetClaims(refreshToken).getSubject();
            var user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UnauthorizedException("자동 로그인 정보가 유효하지 않습니다."));
            if (!user.isActive()) {
                throw new UnauthorizedException("비활성화된 계정입니다. 관리자에게 문의해 주세요.");
            }
            String token = jwtUtil.generateToken(user.getUsername());
            String role = userRoleResolver.resolveRole(user);
            return LoginResponse.of(user.getUsername(), token, role);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("자동 로그인 정보가 만료되었거나 유효하지 않습니다.");
        }
    }

    private void handleFailedLogin(User user,
                                   LocalDateTime now,
                                   String ipAddress) {
        int failures = user.recordFailedLogin();
        if (failures >= DISABLE_THRESHOLD) {
            user.deactivate();
            auditService.log(user.getUsername(), "LOGIN_ACCOUNT_DISABLED", "USER", user.getUsername(),
                    "{\"failedAttempts\":10,\"reason\":\"PASSWORD_FAILURE_LIMIT\"}", ipAddress);
            webSocketSessions.closeUserSessions(user.getUsername());
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
}
