package com.example.backend.security;

import com.example.backend.model.user.FeaturePermission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/** 화면 숨김과 별개로 API에서도 운영자의 기능별 접근을 차단한다. */
@Component
public class FeaturePermissionAuthorizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<FeaturePermission> required = requiredPermissions(request.getRequestURI());
        if (authentication != null && authentication.isAuthenticated() && required != null
                && required.stream().noneMatch(permission -> hasAuthority(authentication, permission))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Feature permission required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasAuthority(Authentication authentication, FeaturePermission permission) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.authority().equals(authority.getAuthority()));
    }

    private Set<FeaturePermission> requiredPermissions(String path) {
        if (path == null || !path.startsWith("/api/v1/")) return null;

        if (path.startsWith("/api/v1/remote-control/")) return any(FeaturePermission.CLASSROOMS, FeaturePermission.PCS, FeaturePermission.COMMANDS);
        if (path.startsWith("/api/v1/file-distribution/") || path.startsWith("/api/v1/file-chunks")
                || path.startsWith("/api/v1/agent/uploads/")) return any(FeaturePermission.FILES);
        if (path.startsWith("/api/v1/script-library/")) return any(FeaturePermission.SCRIPT_LIBRARY);
        if (path.startsWith("/api/v1/reports/")) return any(FeaturePermission.REPORTS);
        if (path.equals("/api/v1/status/performance")) return any(FeaturePermission.REPORTS);
        if (path.startsWith("/api/v1/scmate/")) return any(FeaturePermission.PCS, FeaturePermission.COMMANDS);
        if (path.startsWith("/api/v1/commands")) return any(FeaturePermission.CLASSROOMS, FeaturePermission.PCS, FeaturePermission.COMMANDS, FeaturePermission.CLASSROOM_SETTINGS);
        if (path.startsWith("/api/v1/groups") || path.startsWith("/api/v1/ip-ranges")) return any(FeaturePermission.CLASSROOM_SETTINGS);
        if (path.startsWith("/api/v1/settings/")) return any(FeaturePermission.SYSTEM_SETTINGS);

        if (path.startsWith("/api/v1/dashboard/pcs/broken")) return any(FeaturePermission.BROKEN_PCS);
        if (path.startsWith("/api/v1/dashboard/traffic")
                || path.startsWith("/api/v1/dashboard/config/traffic-thresholds")
                || path.matches("/api/v1/dashboard/classrooms/[^/]+/traffic/pcs.*")
                || path.startsWith("/api/v1/dashboard/classrooms/traffic/summary")) return any(FeaturePermission.NETWORK);
        if (path.matches("/api/v1/dashboard/classrooms/[^/]+/(ip-ranges|teacher-devices).*")) return any(FeaturePermission.CLASSROOM_SETTINGS);
        if (path.startsWith("/api/v1/dashboard/classrooms/ip-ranges/")
                || path.startsWith("/api/v1/dashboard/classrooms/teacher-devices/")) return any(FeaturePermission.CLASSROOM_SETTINGS);
        if (path.startsWith("/api/v1/dashboard/classrooms")) return any(
                FeaturePermission.CLASSROOMS, FeaturePermission.NETWORK, FeaturePermission.REPORTS,
                FeaturePermission.FILES, FeaturePermission.CLASSROOM_SETTINGS);
        if (path.startsWith("/api/v1/dashboard/pcs")) return any(
                FeaturePermission.CLASSROOMS, FeaturePermission.PCS, FeaturePermission.FILES,
                FeaturePermission.SCRIPT_LIBRARY, FeaturePermission.BROKEN_PCS);
        if (path.startsWith("/api/v1/dashboard/") || path.startsWith("/api/v1/dashboards")) return any(FeaturePermission.CLASSROOMS);
        if (path.startsWith("/api/v1/status/")) return any(FeaturePermission.CLASSROOMS, FeaturePermission.PCS);
        if (path.startsWith("/api/v1/classrooms")) return any(FeaturePermission.CLASSROOMS);

        if (path.startsWith("/api/v1/agent/registration-status") || path.startsWith("/api/v1/agent/config/download")
                || path.startsWith("/api/v1/admin/install-key") || path.startsWith("/api/v1/admin/agents")) return any(FeaturePermission.TOKENS);
        if (path.startsWith("/api/v1/admin/enqueue") || path.startsWith("/api/v1/admin/group/wol")) return any(
                FeaturePermission.CLASSROOMS, FeaturePermission.PCS, FeaturePermission.COMMANDS);
        if (path.equals("/api/v1/scripts") || path.startsWith("/api/v1/scripts/")
                || path.startsWith("/api/v1/file-chunks/")) return any(FeaturePermission.FILES);
        return null;
    }

    private Set<FeaturePermission> any(FeaturePermission first, FeaturePermission... remaining) {
        Set<FeaturePermission> values = EnumSet.of(first);
        for (FeaturePermission permission : remaining) values.add(permission);
        return values;
    }
}
