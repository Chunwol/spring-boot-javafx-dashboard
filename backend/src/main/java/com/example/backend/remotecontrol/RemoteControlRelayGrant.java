package com.example.backend.remotecontrol;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

public record RemoteControlRelayGrant(
        UUID sessionId,
        String macAddress,
        InetAddress targetAddress,
        int targetPort,
        Instant sessionExpiresAt) {
}
