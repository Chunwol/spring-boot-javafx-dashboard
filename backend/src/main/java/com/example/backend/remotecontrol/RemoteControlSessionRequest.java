package com.example.backend.remotecontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record RemoteControlSessionRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")
        String macAddress,
        @Positive Long taskId) {
}
