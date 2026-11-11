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

/** 화면 숨김과 별개로 API에서도 운영자의 세부 기능 접근을 차단한다. */
@Component
public class FeaturePermissionAuthorizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<FeaturePermission> required = requiredPermissions(request);
        if (authentication != null && authentication.isAuthenticated() && required != null
                && required.stream().noneMatch(permission -> hasAuthority(authentication, permission))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Detailed feature permission required\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hasAuthority(Authentication authentication, FeaturePermission permission) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.authority().equals(authority.getAuthority()));
    }

    private Set<FeaturePermission> requiredPermissions(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path == null || !path.startsWith("/api/v1/")) return null;

        if (path.startsWith("/api/v1/remote-control/")) return any(FeaturePermission.REMOTE_SCREEN_VIEW);
        if (path.startsWith("/api/v1/scmate/")) return any(
                FeaturePermission.SCMATE_STATUS_VIEW, FeaturePermission.SCMATE_OPERATOR_MODE,
                FeaturePermission.SCMATE_BASELINE_UPDATE, FeaturePermission.SCMATE_PROTECTED_MODE,
                FeaturePermission.SCMATE_SYSTEM_RESTORE, FeaturePermission.SCMATE_RECOVERY_MODULE_INSTALL);
        if (path.startsWith("/api/v1/commands")) {
            if (path.endsWith("/cancel")) return any(FeaturePermission.COMMAND_CANCEL);
            if ("GET".equals(method)) return commandReadPermissions();
            return commandActionPermissions();
        }

        if (path.startsWith("/api/v1/script-library/")) {
            if ("GET".equals(method)) return any(FeaturePermission.SCRIPT_LIBRARY_VIEW);
            if (path.matches(".*/templates/[^/]+$") && "DELETE".equals(method)) return any(FeaturePermission.SCRIPT_DELETE);
            if (path.contains("/execute") || path.contains("/rerun") || path.contains("/retry-failed")) {
                return any(FeaturePermission.SCRIPT_EXECUTE);
            }
            return any(FeaturePermission.SCRIPT_CREATE_EDIT);
        }

        if (path.startsWith("/api/v1/file-distribution/peer-jobs")) {
            if (path.endsWith("/cancel") || path.endsWith("/retry-failed")) return any(FeaturePermission.FILE_JOB_CONTROL);
            return any(FeaturePermission.PEER_FILE_TRANSFER);
        }
        if (path.startsWith("/api/v1/file-distribution/")) {
            if ("GET".equals(method)) return any(FeaturePermission.FILE_DISTRIBUTION_VIEW);
            if (path.endsWith("/rollback")) return any(FeaturePermission.FILE_ROLLBACK);
            if (path.contains("/waves/") || path.endsWith("/cancel") || path.endsWith("/retry-failed")) {
                return any(FeaturePermission.FILE_JOB_CONTROL);
            }
            return any(FeaturePermission.FILE_UPLOAD_DISTRIBUTE);
        }
        if (path.startsWith("/api/v1/file-chunks") || path.startsWith("/api/v1/agent/uploads/")) {
            return "GET".equals(method) ? any(FeaturePermission.FILE_DISTRIBUTION_VIEW, FeaturePermission.COMMAND_RESULT_DOWNLOAD)
                    : any(FeaturePermission.FILE_UPLOAD_DISTRIBUTE);
        }
        if (path.equals("/api/v1/scripts") || path.startsWith("/api/v1/scripts/")) {
            return "GET".equals(method) ? any(FeaturePermission.FILE_DISTRIBUTION_VIEW)
                    : any(FeaturePermission.FILE_UPLOAD_DISTRIBUTE);
        }

        if (path.startsWith("/api/v1/reports/")) {
            if (path.endsWith("/regenerate")) return any(FeaturePermission.REPORT_REGENERATE);
            if (path.equals("/api/v1/reports/pdf")) return any(FeaturePermission.REPORT_EXPORT);
            return any(FeaturePermission.REPORT_VIEW);
        }
        if (path.equals("/api/v1/status/performance")) return any(FeaturePermission.REPORT_VIEW);

        if (path.startsWith("/api/v1/dashboard/pcs/broken")) {
            if ("GET".equals(method)) return any(FeaturePermission.BROKEN_PC_VIEW);
            if ("DELETE".equals(method)) return any(FeaturePermission.BROKEN_PC_CLEAR);
            return any(FeaturePermission.BROKEN_PC_REGISTER_EDIT);
        }
        if ("PUT".equals(method) && path.matches("/api/v1/dashboard/pcs/[^/]+/note")) {
            return any(FeaturePermission.BROKEN_PC_REGISTER_EDIT);
        }
        if (path.startsWith("/api/v1/dashboard/traffic")
                || path.startsWith("/api/v1/dashboard/config/traffic-thresholds")
                || path.matches("/api/v1/dashboard/classrooms/[^/]+/traffic/pcs.*")
                || path.startsWith("/api/v1/dashboard/classrooms/traffic/summary")) return any(FeaturePermission.NETWORK_VIEW);

        if (path.startsWith("/api/v1/groups")) {
            return "GET".equals(method) ? any(FeaturePermission.CLASSROOM_SETTINGS_VIEW) : any(FeaturePermission.CLASSROOM_EDIT);
        }
        if (path.startsWith("/api/v1/ip-ranges")) {
            return "GET".equals(method) ? any(FeaturePermission.CLASSROOM_SETTINGS_VIEW) : any(FeaturePermission.IP_RANGE_EDIT);
        }
        if (path.matches("/api/v1/dashboard/classrooms/[^/]+/ip-ranges.*")
                || path.startsWith("/api/v1/dashboard/classrooms/ip-ranges/")) {
            return "GET".equals(method) ? any(FeaturePermission.CLASSROOM_SETTINGS_VIEW) : any(FeaturePermission.IP_RANGE_EDIT);
        }
        if (path.matches("/api/v1/dashboard/classrooms/[^/]+/teacher-devices.*")
                || path.startsWith("/api/v1/dashboard/classrooms/teacher-devices/")) {
            return "GET".equals(method) ? any(FeaturePermission.CLASSROOM_SETTINGS_VIEW) : any(FeaturePermission.TEACHER_PC_EDIT);
        }
        if (path.startsWith("/api/v1/settings/")) {
            return "GET".equals(method) ? any(FeaturePermission.SYSTEM_SETTINGS_VIEW) : any(FeaturePermission.ALERT_SETTINGS_EDIT);
        }

        if (path.startsWith("/api/v1/dashboard/classrooms")) return any(
                FeaturePermission.CLASSROOM_VIEW, FeaturePermission.NETWORK_VIEW, FeaturePermission.REPORT_VIEW,
                FeaturePermission.FILE_DISTRIBUTION_VIEW, FeaturePermission.CLASSROOM_SETTINGS_VIEW);
        if (path.startsWith("/api/v1/dashboard/pcs")) return any(
                FeaturePermission.CLASSROOM_VIEW, FeaturePermission.PC_VIEW, FeaturePermission.FILE_DISTRIBUTION_VIEW,
                FeaturePermission.SCRIPT_LIBRARY_VIEW, FeaturePermission.BROKEN_PC_VIEW);
        if (path.startsWith("/api/v1/dashboard/") || path.startsWith("/api/v1/dashboards")) {
            return any(FeaturePermission.CLASSROOM_VIEW);
        }
        if (path.startsWith("/api/v1/status/")) return any(FeaturePermission.CLASSROOM_VIEW, FeaturePermission.PC_VIEW);
        if (path.startsWith("/api/v1/classrooms")) return any(FeaturePermission.CLASSROOM_VIEW);

        if (path.startsWith("/api/v1/agent/registration-status")) {
            return "GET".equals(method) ? any(FeaturePermission.TOKEN_VIEW) : any(FeaturePermission.AGENT_REGISTRATION_TOGGLE);
        }
        if (path.startsWith("/api/v1/agent/config/download")) return any(FeaturePermission.AGENT_CONFIG_DOWNLOAD);
        if (path.startsWith("/api/v1/admin/install-key")) {
            return "GET".equals(method) ? any(FeaturePermission.TOKEN_VIEW) : any(FeaturePermission.INSTALL_KEY_ROTATE);
        }
        if (path.startsWith("/api/v1/admin/agents")) {
            if ("GET".equals(method)) return any(FeaturePermission.TOKEN_VIEW);
            if (path.endsWith("/revoke")) return any(FeaturePermission.AGENT_TOKEN_REVOKE);
            return any(FeaturePermission.AGENT_DELETE);
        }
        if (path.startsWith("/api/v1/admin/enqueue") || path.startsWith("/api/v1/admin/group/wol")) {
            return commandActionPermissions();
        }
        return null;
    }

    private Set<FeaturePermission> commandReadPermissions() {
        return any(FeaturePermission.COMMAND_VIEW, FeaturePermission.POWER_ON, FeaturePermission.REBOOT,
                FeaturePermission.SHUTDOWN, FeaturePermission.AGENT_UPDATE, FeaturePermission.COMMAND_ISSUE,
                FeaturePermission.SCMATE_STATUS_VIEW, FeaturePermission.SCMATE_OPERATOR_MODE,
                FeaturePermission.SCMATE_BASELINE_UPDATE, FeaturePermission.SCMATE_PROTECTED_MODE,
                FeaturePermission.SCMATE_SYSTEM_RESTORE, FeaturePermission.SCMATE_RECOVERY_MODULE_INSTALL);
    }

    private Set<FeaturePermission> commandActionPermissions() {
        return any(FeaturePermission.COMMAND_ISSUE, FeaturePermission.POWER_ON, FeaturePermission.REBOOT,
                FeaturePermission.SHUTDOWN, FeaturePermission.AGENT_UPDATE, FeaturePermission.REMOTE_SCREEN_VIEW,
                FeaturePermission.SCMATE_SYSTEM_RESTORE);
    }

    private Set<FeaturePermission> any(FeaturePermission first, FeaturePermission... remaining) {
        Set<FeaturePermission> values = EnumSet.of(first);
        for (FeaturePermission permission : remaining) values.add(permission);
        return values;
    }
}
