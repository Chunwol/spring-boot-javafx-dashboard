package com.example.backend.dto.scriptlibrary;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ScriptLibraryRerunRequest(
        @Size(max = 2000) List<@Pattern(
                regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$") String> targetMacs,
        @Pattern(regexp = "^(FOREGROUND|BACKGROUND)$") String runMode,
        @Pattern(regexp = "^(HIDDEN|VISIBLE)$") String windowMode,
        @Size(max = 1000) String arguments,
        Boolean acknowledged
) {
}
