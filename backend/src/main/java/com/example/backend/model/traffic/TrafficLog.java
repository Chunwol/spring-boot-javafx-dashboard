package com.example.backend.model.traffic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 트래픽 로그 엔티티.
 * 에이전트가 하트비트 시 전송한 인바운드/아웃바운드 네트워크 트래픽(kbps)을
 * 시계열로 기록한다. 트래픽 초과 감지 및 사용량 통계에 활용된다.
 */
@Schema(description = "네트워크 트래픽 로그 (에이전트별 시계열 기록)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "traffic_logs")
public class TrafficLog {

    /** 로그 ID (자동 생성) */
    @Schema(description = "로그 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    /** 에이전트 MAC 주소 */
    @Schema(description = "에이전트 MAC 주소")
    @Column(nullable = false)
    private String macAddress;

    /** 인바운드 트래픽 (kbps) */
    @Schema(description = "인바운드 트래픽 (kbps)")
    @Column(name = "inbound_kbps")
    private Long inboundKbps;

    /** 아웃바운드 트래픽 (kbps) */
    @Schema(description = "아웃바운드 트래픽 (kbps)")
    @Column(name = "outbound_kbps")
    private Long outboundKbps;

    /** 측정 일시 */
    @Schema(description = "측정 일시")
    @Column
    private LocalDateTime timestamp;
}
