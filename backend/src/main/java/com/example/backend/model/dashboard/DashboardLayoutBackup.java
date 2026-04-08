package com.example.backend.model.dashboard;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Dashboard layout backup entity for persistent storage of layout snapshots.
 * Stores complete dashboard configuration for restore operations.
 */
@Entity
@Table(name = "dashboard_layout_backups", indexes = {
    @Index(name = "idx_backup_dashboard_id", columnList = "dashboard_id"),
    @Index(name = "idx_backup_created_at", columnList = "created_at DESC")
})
public class DashboardLayoutBackup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dashboard_id", nullable = false)
    private Long dashboardId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "layout_data", columnDefinition = "TEXT")
    private String layoutData; // JSON serialized layout configuration

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy; // Username who created this backup

    @Column(name = "is_automatic", nullable = false)
    private Boolean isAutomatic = false;

    // Constructors
    public DashboardLayoutBackup() {
    }

    public DashboardLayoutBackup(Long dashboardId, String name, String layoutData) {
        this.dashboardId = dashboardId;
        this.name = name;
        this.layoutData = layoutData;
        this.createdAt = LocalDateTime.now();
        this.isAutomatic = false;
    }

    public DashboardLayoutBackup(Long dashboardId, String name, String description, String layoutData, String createdBy, Boolean isAutomatic) {
        this.dashboardId = dashboardId;
        this.name = name;
        this.description = description;
        this.layoutData = layoutData;
        this.createdAt = LocalDateTime.now();
        this.createdBy = createdBy;
        this.isAutomatic = isAutomatic != null ? isAutomatic : false;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(Long dashboardId) {
        this.dashboardId = dashboardId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLayoutData() {
        return layoutData;
    }

    public void setLayoutData(String layoutData) {
        this.layoutData = layoutData;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getIsAutomatic() {
        return isAutomatic;
    }

    public void setIsAutomatic(Boolean automatic) {
        isAutomatic = automatic;
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
