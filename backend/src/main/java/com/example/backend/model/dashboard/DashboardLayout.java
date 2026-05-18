package com.example.backend.model.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 대시보드 레이아웃 엔티티.
 * 각 대시보드(화면)는 여러 패널(DashboardPanel)로 구성되며,
 * 이름, 설명, 표시 순서를 통해 관리된다.
 */
@Schema(description = "대시보드 레이아웃 (복수 패널의 컨테이너)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dashboards")
public class DashboardLayout {

    /** 대시보드 ID (자동 생성) */
    @Schema(description = "대시보드 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대시보드 이름 (유일) */
    @Schema(description = "대시보드 이름 (유일)")
    @Column(nullable = false, unique = true)
    private String name;

    /** 대시보드 설명 */
    @Schema(description = "대시보드 설명")
    @Column
    private String description;

    /** 표시 순서 (낮을수록 먼저 표시) */
    @Schema(description = "표시 순서")
    @Column(name = "display_order")
    private Integer displayOrder = 0;

    /** 생성 일시 (문자열 ISO 형식) */
    @Schema(description = "생성 일시")
    @Column(name = "created_at")
    private String createdAt;
}
