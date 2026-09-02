package com.example.backend.model.user;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 대시보드 운영자에게 개별 부여할 수 있는 화면 단위 기능 권한. */
public enum FeaturePermission {
    CLASSROOMS,
    PCS,
    COMMANDS,
    SCRIPT_LIBRARY,
    TOKENS,
    BROKEN_PCS,
    NETWORK,
    REPORTS,
    FILES,
    CLASSROOM_SETTINGS,
    SYSTEM_SETTINGS;

    public String authority() {
        return "PERM_" + name();
    }

    public static Set<FeaturePermission> parse(String raw) {
        Set<FeaturePermission> permissions = EnumSet.noneOf(FeaturePermission.class);
        if (raw == null || raw.isBlank()) return permissions;
        for (String value : raw.split(",")) {
            try {
                permissions.add(valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown values from a newer deployment are ignored safely.
            }
        }
        return permissions;
    }

    public static String serialize(Set<FeaturePermission> permissions) {
        return permissions.stream().sorted().map(Enum::name).reduce((left, right) -> left + "," + right).orElse("");
    }

    public static Set<FeaturePermission> requireValid(List<String> values) {
        Set<FeaturePermission> permissions = EnumSet.noneOf(FeaturePermission.class);
        if (values == null) return permissions;
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("기능 권한 값이 올바르지 않습니다.");
            try {
                permissions.add(valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("알 수 없는 기능 권한입니다: " + value);
            }
        }
        return permissions;
    }
}
