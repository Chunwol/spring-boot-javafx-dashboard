package com.example.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "네트워크 트래픽 요약")
public class NetworkTrafficDto {
    @Schema(description = "인바운드 Mbps", example = "12.5")
    private double inboundMbps;

    @Schema(description = "아웃바운드 Mbps", example = "8.3")
    private double outboundMbps;
}