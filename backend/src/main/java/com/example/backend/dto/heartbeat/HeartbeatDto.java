package com.example.backend.dto.heartbeat;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class HeartbeatDto {
    private String macAddress;
    private String hostname;
    private String ipAddress;
    private String agentSessionId;
    private String agentVersion;

    /**
     * 하위 호환용 트래픽 필드.
     * - 신규/에이전트: inboundKbps/outboundKbps
     * - 구버전/일부 클라이언트: inboundMbps/outboundMbps
     */
    @JsonAlias({"inboundKbps"})
    private Double inboundKbps;

    @JsonAlias({"outboundKbps"})
    private Double outboundKbps;

    private Double inboundMbps;
    private Double outboundMbps;
}