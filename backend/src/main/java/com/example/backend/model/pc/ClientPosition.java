package com.example.backend.model.pc;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 클라이언트(PC) 위치 엔티티.
 * 대시보드 UI에서 각 PC를 시각적으로 배치하기 위한
 * 강의실 그룹별 X/Y 좌표를 저장한다.
 */
@Schema(description = "PC 위치 정보 (대시보드 UI 레이아웃용)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "client_positions")
public class ClientPosition {

    /** 위치 ID (자동 생성) */
    @Schema(description = "위치 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PC MAC 주소 */
    @Schema(description = "PC MAC 주소")
    @Column(name = "mac_address", nullable = false)
    private String macAddress;

    /** PC IP 주소 */
    @Schema(description = "PC IP 주소")
    @Column(name = "ip_address")
    private String ipAddress;

    /** 소속 강의실 그룹 ID */
    @Schema(description = "소속 강의실 그룹 ID")
    @Column(name = "group_id")
    private Long groupId;

    /** UI X 좌표 */
    @Schema(description = "UI X 좌표")
    @Column(name = "pos_x")
    private Double x;

    /** UI Y 좌표 */
    @Schema(description = "UI Y 좌표")
    @Column(name = "pos_y")
    private Double y;
}
