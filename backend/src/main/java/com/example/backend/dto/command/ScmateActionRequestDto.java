package com.example.backend.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ScmateActionRequestDto(
        @NotBlank(message = "macAddress는 필수입니다.")
        @Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$", message = "macAddress 형식이 올바르지 않습니다.")
        String macAddress,
        @NotBlank(message = "action은 필수입니다.")
        @Pattern(regexp = "^(REQUEST_OPERATOR|REBOOT_FOR_BASELINE|REBOOT_TO_OPERATOR|UPDATE_BASELINE|REQUEST_PROTECTED|REBOOT_TO_PROTECTED|RESTORE_BASELINE|INSTALL_RECOVERY_MODULE|ENABLE_PROTECTION|REFRESH_STATUS_DETAIL)$",
                message = "SCMATE action 값이 올바르지 않습니다.")
        String action,
        @Pattern(regexp = "^(BASELINE|SNAP1|SNAP2)$", message = "recoveryPoint 값이 올바르지 않습니다.")
        String recoveryPoint) {

    public ScmateActionRequestDto {
        if (recoveryPoint == null || recoveryPoint.isBlank()) {
            recoveryPoint = "BASELINE";
        }
    }

    public ScmateActionRequestDto(String macAddress, String action) {
        this(macAddress, action, "BASELINE");
    }
}
