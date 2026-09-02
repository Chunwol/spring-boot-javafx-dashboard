package com.example.backend.service.auth;

import com.example.backend.model.user.FeaturePermission;
import com.example.backend.model.user.User;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class UserFeaturePermissionService {

    private static final Set<FeaturePermission> DEFAULT_VISIBLE_PERMISSIONS = EnumSet.of(
            FeaturePermission.CLASSROOMS,
            FeaturePermission.PCS,
            FeaturePermission.BROKEN_PCS,
            FeaturePermission.NETWORK,
            FeaturePermission.REPORTS,
            FeaturePermission.CLASSROOM_SETTINGS,
            FeaturePermission.SYSTEM_SETTINGS);

    public Set<FeaturePermission> resolve(User user, String role) {
        if ("ADMIN".equalsIgnoreCase(role)) return EnumSet.allOf(FeaturePermission.class);
        if (!"USER".equalsIgnoreCase(role)) return EnumSet.copyOf(DEFAULT_VISIBLE_PERMISSIONS);
        if (user.getFeaturePermissions() == null) return EnumSet.copyOf(DEFAULT_VISIBLE_PERMISSIONS);
        return FeaturePermission.parse(user.getFeaturePermissions());
    }

    public List<String> resolveCodes(User user, String role) {
        return resolve(user, role).stream().sorted().map(Enum::name).toList();
    }
}
