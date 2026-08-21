package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 사용자명을 기반으로 애플리케이션 역할(ADMIN / VIEWER / USER)을 결정하는 컴포넌트.
 *
 * <p>역할 우선순위: {@code ADMIN > VIEWER > USER}.
 * 동일 사용자명이 admin 목록과 viewer 목록 양쪽에 존재하면 ADMIN으로 처리한다.
 *
 * <p>역할 소스는 DB의 {@code users.role}을 우선하고, 값이 없으면 설정 값을 사용한다:
 * <ol>
 *   <li>application.yml의 {@code app.security.admin-users}, {@code app.security.viewer-users} (쉼표 구분)</li>
 *   <li>환경 변수 {@code DEFAULT_ADMIN_USERNAME}, {@code DEFAULT_VIEWER_USERNAME} (Docker/K8s 주입용)</li>
 * </ol>
 *
 */
@Component
public class UserRoleResolver {

    private final Set<String> adminUsers;
    private final Set<String> viewerUsers;
    private final UserRepository userRepository;

    /**
     * 생성자 초기화: yml 설정 + 환경 변수에서 admin/viewer 사용자 목록을 읽어 집합을 구성한다.
     *
     * @param adminUsersRaw       yml의 {@code app.security.admin-users} (콤마 구분 문자열)
     * @param viewerUsersRaw      yml의 {@code app.security.viewer-users} (콤마 구분 문자열)
     * @param defaultAdminUsername 환경 변수 {@code DEFAULT_ADMIN_USERNAME}
     * @param defaultViewerUsername 환경 변수 {@code DEFAULT_VIEWER_USERNAME}
     */
    public UserRoleResolver(UserRepository userRepository,
                            @Value("${app.security.admin-users:admin}") String adminUsersRaw,
                            @Value("${app.security.viewer-users:}") String viewerUsersRaw,
                            @Value("${DEFAULT_ADMIN_USERNAME:admin}") String defaultAdminUsername,
                            @Value("${DEFAULT_VIEWER_USERNAME:}") String defaultViewerUsername) {
        this.userRepository = userRepository;
        this.adminUsers = parseUsers(adminUsersRaw);
        this.viewerUsers = parseUsers(viewerUsersRaw);

        if (defaultAdminUsername != null && !defaultAdminUsername.isBlank()) {
            this.adminUsers.add(defaultAdminUsername.trim());
        }
        if (defaultViewerUsername != null && !defaultViewerUsername.isBlank()) {
            this.viewerUsers.add(defaultViewerUsername.trim());
        }

        // ADMIN 우선: 동일 사용자명이 두 목록에 있으면 VIEWER에서 제거.
        // 이를 통해 yml/환경변수 설정 오류 시에도 보안 측면에서 더 높은 권한이 유지된다.
        this.viewerUsers.removeAll(this.adminUsers);
    }

    /**
     * 사용자명에 해당하는 역할 문자열을 반환한다.
     *
     * @param username 인증된 사용자명
     * @return {@code "ADMIN"}, {@code "VIEWER"}, 또는 {@code "USER"}
     */
    @Transactional(readOnly = true)
    public String resolveRole(String username) {
        if (username == null || username.isBlank()) {
            return "USER";
        }
        return userRepository.findByUsername(username)
                .map(this::resolveRole)
                .orElseGet(() -> resolveConfiguredRole(username));
    }

    public String resolveRole(User user) {
        if (user == null) {
            return "USER";
        }
        String storedRole = normalizeRole(user.getRole());
        if (storedRole != null) {
            return storedRole;
        }
        return resolveConfiguredRole(user.getUsername());
    }

    private String resolveConfiguredRole(String username) {
        if (adminUsers.contains(username)) {
            return "ADMIN";
        }
        if (viewerUsers.contains(username)) {
            return "VIEWER";
        }
        return "USER";
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ADMIN", "USER", "VIEWER" -> normalized;
            default -> null;
        };
    }

    /**
     * 콤마로 구분된 사용자명 문자열을 파싱하여 공백이 제거된 Set으로 반환한다.
     *
     * @param raw 콤마 구분 문자열 (null 또는 빈 값 허용)
     * @return 사용자명 집합 (입력 순서 보존)
     */
    private Set<String> parseUsers(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(out::add);
        return out;
    }
}
