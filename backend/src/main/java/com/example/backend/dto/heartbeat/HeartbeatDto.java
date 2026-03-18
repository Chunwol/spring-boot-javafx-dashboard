package com.example.backend.dto.heartbeat;

import com.example.backend.model.pc.ClientPosition;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import lombok.Data;
import java.util.List;

@Data
public class HeartbeatDto {
    private String macAddress;
    private String hostname;
    private String ipAddress;
    // 에이전트는 마지막 하트비트 이후의 트래픽 '증가량'을 보낸다고 가정합니다.
    private double inboundMbps;
    private double outboundMbps;
    private List<ClientPosition> positions;
    private List<NetworkTrafficDto> traffic;
}