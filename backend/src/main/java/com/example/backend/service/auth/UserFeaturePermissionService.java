package com.example.backend.service.auth;

import com.example.backend.model.user.FeaturePermission;
import com.example.backend.model.user.User;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class UserFeaturePermissionService {

    private static final Set<FeaturePermission> VIEWER_PERMISSIONS = EnumSet.of(
            FeaturePermission.CLASSROOM_VIEW,
            FeaturePermission.PC_VIEW,
            FeaturePermission.REMOTE_SCREEN_VIEW,
            FeaturePermission.BROKEN_PC_VIEW,
            FeaturePermission.NETWORK_VIEW,
            FeaturePermission.REPORT_VIEW,
            FeaturePermission.CLASSROOM_SETTINGS_VIEW,
            FeaturePermission.SYSTEM_SETTINGS_VIEW,
            FeaturePermission.ALERT_SETTINGS_EDIT);

    private static final Set<FeaturePermission> LEGACY_OPERATOR_PERMISSIONS = FeaturePermission.parse(
            "CLASSROOMS,PCS,BROKEN_PCS,NETWORK,REPORTS,CLASSROOM_SETTINGS,SYSTEM_SETTINGS");

    public Set<FeaturePermission> resolve(User user, String role) {
        if ("ADMIN".equalsIgnoreCase(role)) return EnumSet.allOf(FeaturePermission.class);
        if (!"USER".equalsIgnoreCase(role)) return EnumSet.copyOf(VIEWER_PERMISSIONS);
        if (user.getFeaturePermissions() == null) return EnumSet.copyOf(LEGACY_OPERATOR_PERMISSIONS);
        return FeaturePermission.parse(user.getFeaturePermissions());
    }

    public List<String> resolveCodes(User user, String role) {
        return resolve(user, role).stream().sorted().map(Enum::name).toList();
    }
}
