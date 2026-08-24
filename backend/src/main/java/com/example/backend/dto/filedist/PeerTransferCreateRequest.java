package com.example.backend.dto.filedist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PeerTransferCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")
        String sourceMac,

        @NotEmpty
        @Size(max = 500)
        List<@Pattern(regexp = "^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$") String> targetMacs,

        @NotBlank
        @Size(max = 1000)
        String sourcePath,

        @NotBlank
        @Size(max = 1000)
        String targetPath,

        boolean overwriteExisting
) {
}
