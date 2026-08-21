package com.example.backend.remotecontrol;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record RemoteControlBatchCloseRequest(
        @NotEmpty
        @Size(max = 64)
        List<@NotNull UUID> sessionIds) {
}
