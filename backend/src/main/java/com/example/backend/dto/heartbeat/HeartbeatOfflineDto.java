package com.example.backend.dto.heartbeat;

import lombok.Data;

@Data
public class HeartbeatOfflineDto {
    private String macAddress;
    private String hostname;
    private String ipAddress;
    private String agentSessionId;
    private String agentVersion;
    private String reason;
}
