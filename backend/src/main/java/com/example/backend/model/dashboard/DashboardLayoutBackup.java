package com.example.backend.model.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 대시보드 레이아웃 백업 엔티티.
 * 대시보드 구성의 스냅샷을 JSON 형태로 저장하며,
 * 자동/수동 백업을 구분하고 복원 작업에 사용된다.
 */
@Schema(description = "대시보드 레이아웃 백업 스냅샷")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dashboard_layout_backups", indexes = {
    @Index(name = "idx_backup_dashboard_id", columnList = "dashboard_id"),
    @Index(name = "idx_backup_created_at", columnList = "created_at DESC")
})
public class DashboardLayoutBackup {

    /** 백업 ID (자동 생성) */
    @Schema(description = "백업 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 백업 대상 대시보드 ID */
    @Schema(description = "백업 대상 대시보드 ID")
    @Column(name = "dashboard_id", nullable = false)
    private Long dashboardId;

    /** 백업 이름 */
    @Schema(description = "백업 이름")
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** 백업 설명 */
    @Schema(description = "백업 설명")
    @Column(name = "description", length = 1000)
    private String description;

    /** 레이아웃 데이터 (JSON 직렬화) */
    @Schema(description = "레이아웃 데이터 JSON")
    @Column(name = "layout_data", columnDefinition = "TEXT")
    private String layoutData;

    /** 백업 생성 일시 */
    @Schema(description = "백업 생성 일시")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 백업 생성자 사용자명 */
    @Schema(description = "백업 생성자 사용자명")
    @Column(name = "created_by", length = 255)
    private String createdBy;

    /** 자동 백업 여부 */
    @Schema(description = "자동 백업 여부")
    @Column(name = "is_automatic", nullable = false)
    private Boolean isAutomatic = false;

    /**
     * 기본 생성자 (dashboardId, name, layoutData).
     */
    public DashboardLayoutBackup(Long dashboardId, String name, String layoutData) {
        this.dashboardId = dashboardId;
        this.name = name;
        this.layoutData = layoutData;
        this.createdAt = LocalDateTime.now();
        this.isAutomatic = false;
    }

    /**
     * 전체 필드 생성자.
     */
    public DashboardLayoutBackup(Long dashboardId, String name, String description,
                                  String layoutData, String createdBy, Boolean isAutomatic) {
        this.dashboardId = dashboardId;
        this.name = name;
        this.description = description;
        this.layoutData = layoutData;
        this.createdAt = LocalDateTime.now();
        this.createdBy = createdBy;
        this.isAutomatic = isAutomatic != null ? isAutomatic : false;
    }

    @Override
    public String toString() {
        return "DashboardLayoutBackup{" +
                "id=" + id +
                ", dashboardId=" + dashboardId +
                ", name='" + name + '\'' +
                ", createdAt=" + createdAt +
                ", createdBy='" + createdBy + '\'' +
                ", isAutomatic=" + isAutomatic +
                '}';
    }
}
