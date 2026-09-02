package com.example.backend.security;

import com.example.backend.model.user.FeaturePermission;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 요청 본문에 따라 달라지는 명령과 SCMATE 작업의 세부 권한을 검사한다. */
@Component
public class FeaturePermissionGuard {

    public void requireCommand(String commandType) {
        String type = normalize(commandType);
        if ("SCMATE_CONTROL".equals(type)) {
            requireAllScmatePermissions();
            return;
        }
        FeaturePermission permission = switch (type) {
            case "PCON", "WOL", "WAKEUP" -> FeaturePermission.POWER_ON;
            case "REBOOT" -> FeaturePermission.REBOOT;
            case "SHUTDOWN", "PCOFF" -> FeaturePermission.SHUTDOWN;
            case "AGENT_UPDATE", "AGENT_BUNDLE_UPDATE" -> FeaturePermission.AGENT_UPDATE;
            case "REMOTE_CONTROL_START", "REMOTE_CONTROL_STOP" -> FeaturePermission.REMOTE_SCREEN_VIEW;
            case "RESTORE" -> FeaturePermission.SCMATE_SYSTEM_RESTORE;
            default -> FeaturePermission.COMMAND_ISSUE;
        };
        require(permission);
    }

    private void requireAllScmatePermissions() {
        require(FeaturePermission.SCMATE_STATUS_VIEW);
        require(FeaturePermission.SCMATE_OPERATOR_MODE);
        require(FeaturePermission.SCMATE_BASELINE_UPDATE);
        require(FeaturePermission.SCMATE_PROTECTED_MODE);
        require(FeaturePermission.SCMATE_SYSTEM_RESTORE);
        require(FeaturePermission.SCMATE_RECOVERY_MODULE_INSTALL);
    }

    public void requireScmateAction(String action) {
        FeaturePermission permission = switch (normalize(action)) {
            case "REFRESH_STATUS_DETAIL" -> FeaturePermission.SCMATE_STATUS_VIEW;
            case "REQUEST_OPERATOR", "REBOOT_FOR_BASELINE", "REBOOT_TO_OPERATOR" -> FeaturePermission.SCMATE_OPERATOR_MODE;
            case "UPDATE_BASELINE" -> FeaturePermission.SCMATE_BASELINE_UPDATE;
            case "REQUEST_PROTECTED", "REBOOT_TO_PROTECTED", "ENABLE_PROTECTION" -> FeaturePermission.SCMATE_PROTECTED_MODE;
            case "RESTORE_BASELINE" -> FeaturePermission.SCMATE_SYSTEM_RESTORE;
            case "INSTALL_RECOVERY_MODULE" -> FeaturePermission.SCMATE_RECOVERY_MODULE_INSTALL;
            default -> throw new AccessDeniedException("지원하지 않는 세부 권한 작업입니다.");
        };
        require(permission);
    }

    public void require(FeaturePermission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(authority ->
                permission.authority().equals(authority.getAuthority()))) {
            throw new AccessDeniedException("세부 기능 권한이 없습니다: " + permission.name());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
