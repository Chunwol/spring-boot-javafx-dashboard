package com.example.backend.remotecontrol;

import java.time.Instant;
import java.util.UUID;

public record RemoteControlSessionView(
        UUID sessionId,
        Long taskId,
        String macAddress,
        String status,
        Instant expiresAt,
        String failureReason) {
}
