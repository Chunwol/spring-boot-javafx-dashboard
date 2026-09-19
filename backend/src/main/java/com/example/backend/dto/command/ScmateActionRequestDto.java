package com.example.backend.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ScmateActionRequestDto(
        @NotBlank(message = "macAddress는 필수입니다.")
        @Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$", message = "macAddress 형식이 올바르지 않습니다.")
        String macAddress,
        @NotBlank(message = "action은 필수입니다.")
        @Pattern(regexp = "^(REQUEST_OPERATOR|REBOOT_FOR_BASELINE|REBOOT_TO_OPERATOR|UPDATE_BASELINE|REQUEST_PROTECTED|REBOOT_TO_PROTECTED|RESTORE_BASELINE|INSTALL_RECOVERY_MODULE|ENABLE_PROTECTION|REFRESH_STATUS_DETAIL|LIST_EXCEPTION_FOLDERS|ADD_EXCEPTION_FOLDER|REMOVE_EXCEPTION_FOLDER)$",
                message = "SCMATE action 값이 올바르지 않습니다.")
        String action,
        @Pattern(regexp = "^(BASELINE|SNAP1|SNAP2)$", message = "recoveryPoint 값이 올바르지 않습니다.")
        String recoveryPoint,
        @Pattern(regexp = "^$|(?i)^[a-z]:\\\\[^\\r\\n\\u0000\"*?<>|]{1,237}$",
                message = "path는 절대 Windows 폴더 경로여야 합니다.")
        String path) {

    public ScmateActionRequestDto {
        if (recoveryPoint == null || recoveryPoint.isBlank()) {
            recoveryPoint = "BASELINE";
        }
    }

    public ScmateActionRequestDto(String macAddress, String action) {
        this(macAddress, action, "BASELINE", null);
    }

    public ScmateActionRequestDto(String macAddress, String action, String recoveryPoint) {
        this(macAddress, action, recoveryPoint, null);
    }
}
