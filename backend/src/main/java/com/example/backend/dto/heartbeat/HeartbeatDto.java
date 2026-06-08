package com.example.backend.dto.heartbeat;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class HeartbeatDto {
    private String macAddress;
    private String hostname;
    private String ipAddress;
    private String agentSessionId;
    private String agentVersion;

    /**
     * 다중 트래픽 샘플 (신규): [[inboundKbps, outboundKbps], ...]
     * 존재하면 단일값(inboundKbps/outboundKbps)보다 우선 사용한다.
     */
    private List<double[]> trafficSamples;

    /**
     * 하위 호환용 트래픽 필드 (단일값).
     * - 호환: inboundKbps/outboundKbps
     * - 구버전: inboundMbps/outboundMbps
     */
    @JsonAlias({"inboundKbps"})
    private Double inboundKbps;

    @JsonAlias({"outboundKbps"})
    private Double outboundKbps;

    private Double inboundMbps;
    private Double outboundMbps;
}
