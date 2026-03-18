package com.example.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(description = "PC의 상태 정보")
public class PcStatusDto {
    @Schema(description = "PC ID", example = "1")
    private Long id;
    
    @Schema(description = "PC 호스트명", example = "PC-101-001")
    private String hostname;
    
    @Schema(description = "PC IP 주소", example = "192.168.10.10")
    private String ipAddress;
    
    @Schema(description = "PC MAC 주소", example = "00:1A:2B:3C:4D:5E")
    private String macAddress;
    
    @Schema(description = "PC 온라인 여부", example = "true")
    private boolean online;
    
    @Schema(description = "마지막 하트비트 수신 시간")
    private LocalDateTime lastSeen;
    
    @Schema(description = "PC 상태", example = "ONLINE", allowableValues = {"ONLINE", "OFFLINE", "IDLE"})
    private String status;
}