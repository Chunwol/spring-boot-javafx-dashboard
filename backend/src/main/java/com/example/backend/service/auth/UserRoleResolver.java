package com.example.backend.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class UserRoleResolver {

    private final Set<String> adminUsers;
    private final Set<String> viewerUsers;

    public UserRoleResolver(@Value("${app.security.admin-users:admin}") String adminUsersRaw,
                            @Value("${app.security.viewer-users:}") String viewerUsersRaw,
                            @Value("${DEFAULT_ADMIN_USERNAME:admin}") String defaultAdminUsername,
                            @Value("${DEFAULT_VIEWER_USERNAME:}") String defaultViewerUsername) {
        this.adminUsers = parseUsers(adminUsersRaw);
        this.viewerUsers = parseUsers(viewerUsersRaw);

        if (defaultAdminUsername != null && !defaultAdminUsername.isBlank()) {
            this.adminUsers.add(defaultAdminUsername.trim());
        }
        if (defaultViewerUsername != null && !defaultViewerUsername.isBlank()) {
            this.viewerUsers.add(defaultViewerUsername.trim());
        }

        // ADMIN 우선. 동일 사용자명이 두 목록에 있으면 VIEWER에서 제거.
        this.viewerUsers.removeAll(this.adminUsers);
    }

    public String resolveRole(String username) {
        if (username == null || username.isBlank()) {
            return "USER";
        }
        if (adminUsers.contains(username)) {
            return "ADMIN";
        }
        if (viewerUsers.contains(username)) {
            return "VIEWER";
        }
        return "USER";
    }

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
