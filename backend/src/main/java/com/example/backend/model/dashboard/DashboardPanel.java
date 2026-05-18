package com.example.backend.model.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 대시보드 패널 엔티티.
 * 대시보드 레이아웃에 포함되는 개별 패널(위젯)로,
 * 패널 유형(pc, chart, text 등), 위치, 크기, z-인덱스를 관리한다.
 */
@Schema(description = "대시보드 패널 (위젯 단위, 위치/크기/유형 포함)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dashboard_panels")
public class DashboardPanel {

    /** 패널 ID (자동 생성) */
    @Schema(description = "패널 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 대시보드 ID */
    @Schema(description = "소속 대시보드 ID")
    @Column(name = "dashboard_id", nullable = false)
    private Long dashboardId;

    /** 패널 유형 (pc, chart, text, report, iframe 등) */
    @Schema(description = "패널 유형")
    @Column(name = "panel_type", nullable = false)
    private String panelType;

    /** 참조 강의실 그룹 ID (유형이 pc인 경우) */
    @Schema(description = "참조 강의실 그룹 ID")
    @Column(name = "ref_group_id")
    private Long refGroupId;

    /** 패널별 추가 설정 JSON */
    @Schema(description = "패널 설정 JSON")
    @Column(name = "config_json")
    private String configJson;

    /** X 좌표 */
    @Schema(description = "X 좌표")
    @Column
    private Double x = 0.0;

    /** Y 좌표 */
    @Schema(description = "Y 좌표")
    @Column
    private Double y = 0.0;

    /** 너비 (px) */
    @Schema(description = "너비 (px)")
    @Column
    private Double width = 300.0;

    /** 높이 (px) */
    @Schema(description = "높이 (px)")
    @Column
    private Double height = 220.0;

    /** Z 인덱스 (겹침 순서) */
    @Schema(description = "Z 인덱스")
    @Column(name = "z_index")
    private Integer zIndex = 0;

    /** 표시 여부 */
    @Schema(description = "표시 여부")
    @Column
    private boolean visible = true;

    /** 패널 이름 */
    @Schema(description = "패널 이름")
    @Column(name = "panel_name")
    private String panelName;

    /** 생성 일시 (문자열 ISO 형식) */
    @Schema(description = "생성 일시")
    @Column(name = "created_at")
    private String createdAt;

    /** 수정 일시 (문자열 ISO 형식) */
    @Schema(description = "수정 일시")
    @Column(name = "updated_at")
    private String updatedAt;
}
