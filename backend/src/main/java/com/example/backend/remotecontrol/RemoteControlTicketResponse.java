package com.example.backend.remotecontrol;

import java.time.Instant;
import java.util.UUID;

public record RemoteControlTicketResponse(
        UUID sessionId,
        Long taskId,
        String webSocketPath,
        String oneTimePassword,
        Instant ticketExpiresAt) {
}
