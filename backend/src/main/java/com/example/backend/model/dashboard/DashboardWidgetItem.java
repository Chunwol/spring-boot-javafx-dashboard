package com.example.backend.model.dashboard;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 대시보드 위젯 아이템 엔티티.
 * 대시보드 내 자유 배치 가능한 위젯 항목으로,
 * 유형(type), 위치(x, y), 크기(width, height), 표시 여부(visible)를 관리한다.
 */
@Schema(description = "대시보드 위젯 아이템 (자유 배치 위젯)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dashboard_widget_items")
public class DashboardWidgetItem {

    /** 위젯 ID (자동 생성) */
    @Schema(description = "위젯 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 강의실 그룹 ID */
    @Schema(description = "소속 강의실 그룹 ID")
    @Column(name = "group_id")
    private Long groupId;

    /** 소속 대시보드 ID */
    @Schema(description = "소속 대시보드 ID")
    @Column(name = "dashboard_id")
    private Long dashboardId;

    /** 위젯 유형 */
    @Schema(description = "위젯 유형")
    @Column(nullable = false)
    private String type;

    /** 위젯 설정 JSON */
    @Schema(description = "위젯 설정 JSON")
    @Column(columnDefinition = "TEXT")
    private String config;

    /** X 좌표 */
    @Schema(description = "X 좌표")
    private Double x;

    /** Y 좌표 */
    @Schema(description = "Y 좌표")
    private Double y;

    /** 너비 (px) */
    @Schema(description = "너비 (px)")
    private Double width;

    /** 높이 (px) */
    @Schema(description = "높이 (px)")
    private Double height;

    /** 표시 여부 */
    @Schema(description = "표시 여부")
    private boolean visible = true;

    /** 생성 일시 */
    @Schema(description = "생성 일시")
    @Column(name = "created_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt = LocalDateTime.now();
}
