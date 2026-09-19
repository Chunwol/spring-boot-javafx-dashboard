package com.example.backend.model.user;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 대시보드 운영자에게 개별 부여할 수 있는 세부 기능 권한. */
public enum FeaturePermission {
    CLASSROOM_VIEW,
    PC_VIEW,
    REMOTE_SCREEN_VIEW,
    POWER_ON,
    REBOOT,
    SHUTDOWN,
    SCMATE_STATUS_VIEW,
    SCMATE_OPERATOR_MODE,
    SCMATE_BASELINE_UPDATE,
    SCMATE_PROTECTED_MODE,
    SCMATE_SYSTEM_RESTORE,
    SCMATE_RECOVERY_MODULE_INSTALL,
    SCMATE_EXCEPTION_FOLDER_MANAGE,
    AGENT_UPDATE,
    COMMAND_VIEW,
    COMMAND_ISSUE,
    COMMAND_CANCEL,
    COMMAND_RESULT_DOWNLOAD,
    SCRIPT_LIBRARY_VIEW,
    SCRIPT_CREATE_EDIT,
    SCRIPT_DELETE,
    SCRIPT_EXECUTE,
    TOKEN_VIEW,
    AGENT_REGISTRATION_TOGGLE,
    INSTALL_KEY_ROTATE,
    AGENT_TOKEN_REVOKE,
    AGENT_DELETE,
    AGENT_CONFIG_DOWNLOAD,
    BROKEN_PC_VIEW,
    BROKEN_PC_REGISTER_EDIT,
    BROKEN_PC_CLEAR,
    NETWORK_VIEW,
    REPORT_VIEW,
    REPORT_EXPORT,
    REPORT_REGENERATE,
    FILE_DISTRIBUTION_VIEW,
    FILE_UPLOAD_DISTRIBUTE,
    FILE_JOB_CONTROL,
    FILE_ROLLBACK,
    PEER_FILE_TRANSFER,
    CLASSROOM_SETTINGS_VIEW,
    CLASSROOM_EDIT,
    IP_RANGE_EDIT,
    TEACHER_PC_EDIT,
    IP_RANGE_PC_CONTROL,
    SYSTEM_SETTINGS_VIEW,
    ALERT_SETTINGS_EDIT;

    public String authority() {
        return "PERM_" + name();
    }

    public static Set<FeaturePermission> parse(String raw) {
        Set<FeaturePermission> permissions = EnumSet.noneOf(FeaturePermission.class);
        if (raw == null || raw.isBlank()) return permissions;
        for (String value : raw.split(",")) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            Set<FeaturePermission> legacy = legacyPermissions(normalized);
            if (legacy != null) {
                permissions.addAll(legacy);
                continue;
            }
            try {
                permissions.add(valueOf(normalized));
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

    /** f9fae10 이전 화면 단위 권한을 동일하거나 더 좁지 않은 세부 권한으로 변환한다. */
    private static Set<FeaturePermission> legacyPermissions(String code) {
        return switch (code) {
            case "CLASSROOMS" -> EnumSet.of(CLASSROOM_VIEW, REMOTE_SCREEN_VIEW, POWER_ON, REBOOT, SHUTDOWN);
            case "PCS" -> EnumSet.of(PC_VIEW, REMOTE_SCREEN_VIEW, POWER_ON, REBOOT, SHUTDOWN,
                    SCMATE_STATUS_VIEW, SCMATE_OPERATOR_MODE, SCMATE_BASELINE_UPDATE, SCMATE_PROTECTED_MODE,
                    SCMATE_SYSTEM_RESTORE, SCMATE_RECOVERY_MODULE_INSTALL,
                    SCMATE_EXCEPTION_FOLDER_MANAGE, AGENT_UPDATE);
            case "COMMANDS" -> EnumSet.of(COMMAND_VIEW, COMMAND_ISSUE, COMMAND_CANCEL, COMMAND_RESULT_DOWNLOAD,
                    REMOTE_SCREEN_VIEW);
            case "SCRIPT_LIBRARY" -> EnumSet.of(SCRIPT_LIBRARY_VIEW, SCRIPT_CREATE_EDIT, SCRIPT_DELETE, SCRIPT_EXECUTE);
            case "TOKENS" -> EnumSet.of(TOKEN_VIEW, AGENT_REGISTRATION_TOGGLE, INSTALL_KEY_ROTATE,
                    AGENT_TOKEN_REVOKE, AGENT_DELETE, AGENT_CONFIG_DOWNLOAD);
            case "BROKEN_PCS" -> EnumSet.of(BROKEN_PC_VIEW, BROKEN_PC_REGISTER_EDIT, BROKEN_PC_CLEAR);
            case "NETWORK" -> EnumSet.of(NETWORK_VIEW);
            case "REPORTS" -> EnumSet.of(REPORT_VIEW, REPORT_EXPORT, REPORT_REGENERATE);
            case "FILES" -> EnumSet.of(FILE_DISTRIBUTION_VIEW, FILE_UPLOAD_DISTRIBUTE, FILE_JOB_CONTROL,
                    FILE_ROLLBACK, PEER_FILE_TRANSFER);
            case "CLASSROOM_SETTINGS" -> EnumSet.of(CLASSROOM_SETTINGS_VIEW, CLASSROOM_EDIT, IP_RANGE_EDIT,
                    TEACHER_PC_EDIT, IP_RANGE_PC_CONTROL);
            case "SYSTEM_SETTINGS" -> EnumSet.of(SYSTEM_SETTINGS_VIEW, ALERT_SETTINGS_EDIT);
            default -> null;
        };
    }
}
