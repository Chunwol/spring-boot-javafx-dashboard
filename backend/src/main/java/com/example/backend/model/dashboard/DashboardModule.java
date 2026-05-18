package com.example.backend.model.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 대시보드 모듈 엔티티.
 * 대시보드 상에 배치되는 독립적인 모듈(pc, text, chart, report, iframe 등)을 표현하며,
 * 강의실 그룹과 연관되고 UI에서의 X/Y 위치와 JSON 설정 데이터를 포함한다.
 */
@Schema(description = "대시보드 모듈 (독립 위젯, 그룹 연관)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dashboard_modules")
public class DashboardModule {

    /** 모듈 ID (자동 생성) */
    @Schema(description = "모듈 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 강의실 그룹 ID */
    @Schema(description = "소속 강의실 그룹 ID")
    @Column(name = "group_id")
    private Long groupId;

    /** 모듈 유형 (pc, text, chart, report, iframe 등) */
    @Schema(description = "모듈 유형 (pc, text, chart, report, iframe 등)")
    @Column(nullable = false)
    private String type;

    /** 모듈별 임의 설정 JSON */
    @Schema(description = "모듈 설정 JSON")
    @Column(columnDefinition = "TEXT")
    private String config;

    /** UI X 좌표 */
    @Schema(description = "UI X 좌표")
    @Column(name = "pos_x")
    private Double x;

    /** UI Y 좌표 */
    @Schema(description = "UI Y 좌표")
    @Column(name = "pos_y")
    private Double y;

    /** 생성 일시 */
    @Schema(description = "생성 일시")
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
