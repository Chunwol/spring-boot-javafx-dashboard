package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardLayoutBackup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for dashboard layout backups.
 * Handles CRUD and querying of layout backup snapshots.
 */
@Repository
public interface DashboardLayoutBackupRepository extends JpaRepository<DashboardLayoutBackup, Long> {

    /**
     * Find all backups for a specific dashboard, ordered by creation date descending
     */
    List<DashboardLayoutBackup> findByDashboardIdOrderByCreatedAtDesc(Long dashboardId);

    /**
     * Find paginated backups for a dashboard
     */
    Page<DashboardLayoutBackup> findByDashboardId(Long dashboardId, Pageable pageable);

    /**
     * Find the most recent backup for a dashboard (for restore)
     */
    Optional<DashboardLayoutBackup> findFirstByDashboardIdOrderByCreatedAtDesc(Long dashboardId);

    /**
     * Find backups created between date range
     */
    List<DashboardLayoutBackup> findByDashboardIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long dashboardId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find automatic backups for a dashboard
     */
    List<DashboardLayoutBackup> findByDashboardIdAndIsAutomaticTrueOrderByCreatedAtDesc(Long dashboardId);

    /**
     * Count total backups for a dashboard
     */
    long countByDashboardId(Long dashboardId);

    /**
     * Delete backups older than specified date
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM DashboardLayoutBackup b WHERE b.dashboardId = :dashboardId AND b.createdAt < :cutoffDate")
    int deleteOldBackups(@Param("dashboardId") Long dashboardId, @Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Delete all backups for a dashboard when dashboard is deleted
     */
    @Modifying
    @Transactional
    void deleteByDashboardId(Long dashboardId);
}
