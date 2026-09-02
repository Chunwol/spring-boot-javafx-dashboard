package com.example.backend.remotecontrol;

import java.time.Instant;
import java.util.UUID;

public record ActiveRemoteControlSessionView(
        UUID sessionId,
        String username,
        String sourceIp,
        String userAgent,
        String macAddress,
        String pcName,
        String targetIp,
        String status,
        Instant startedAt) {
}
