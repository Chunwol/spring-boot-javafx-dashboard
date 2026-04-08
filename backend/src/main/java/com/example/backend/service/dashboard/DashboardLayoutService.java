package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.DashboardPanelUpsertRequest;
import com.example.backend.model.dashboard.DashboardLayout;
import com.example.backend.model.dashboard.DashboardLayoutBackup;
import com.example.backend.model.dashboard.DashboardPanel;
import com.example.backend.repository.dashboard.DashboardLayoutBackupRepository;
import com.example.backend.repository.dashboard.DashboardLayoutRepository;
import com.example.backend.repository.dashboard.DashboardPanelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DashboardLayoutService {

    private static final Logger log = LoggerFactory.getLogger(DashboardLayoutService.class);
    private static final int MAX_BACKUPS_PER_DASHBOARD = 50;
    private static final int BACKUP_RETENTION_DAYS = 90;

    private final DashboardLayoutRepository dashboardLayoutRepository;
    private final DashboardPanelRepository dashboardPanelRepository;
    private final DashboardLayoutBackupRepository backupRepository;
    private final ObjectMapper objectMapper;

    public DashboardLayoutService(DashboardLayoutRepository dashboardLayoutRepository,
                                  DashboardPanelRepository dashboardPanelRepository,
                                  DashboardLayoutBackupRepository backupRepository,
                                  ObjectMapper objectMapper) {
        this.dashboardLayoutRepository = dashboardLayoutRepository;
        this.dashboardPanelRepository = dashboardPanelRepository;
        this.backupRepository = backupRepository;
        this.objectMapper = objectMapper;
    }

    public List<DashboardLayout> getDashboards() {
        return dashboardLayoutRepository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    @Transactional
    public DashboardLayout createDashboard(DashboardLayout request) {
        DashboardLayout dashboard = new DashboardLayout();
        dashboard.setName(request.getName());
        dashboard.setDescription(request.getDescription());
        dashboard.setDisplayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
        dashboard.setCreatedAt(LocalDateTime.now().toString());
        return dashboardLayoutRepository.save(dashboard);
    }

    @Transactional
    public DashboardLayout updateDashboard(Long id, DashboardLayout request) {
        DashboardLayout dashboard = dashboardLayoutRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("대시보드를 찾을 수 없습니다: " + id));

        if (request.getName() != null && !request.getName().isBlank()) {
            dashboard.setName(request.getName());
        }
        if (request.getDescription() != null) {
            dashboard.setDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            dashboard.setDisplayOrder(request.getDisplayOrder());
        }

        return dashboardLayoutRepository.save(dashboard);
    }

    @Transactional
    public void deleteDashboard(Long id) {
        // Delete all backups for this dashboard
        backupRepository.deleteByDashboardId(id);
        dashboardLayoutRepository.deleteById(id);
    }

    public List<DashboardPanel> getPanels(Long dashboardId) {
        return dashboardPanelRepository.findPanelsByDashboardId(dashboardId);
    }

    @Transactional
    public DashboardPanel createPanel(Long dashboardId, DashboardPanelUpsertRequest request) {
        DashboardPanel panel = new DashboardPanel();
        panel.setDashboardId(dashboardId);
        applyPanelRequest(panel, request, true);
        panel.setCreatedAt(LocalDateTime.now().toString());
        panel.setUpdatedAt(LocalDateTime.now().toString());
        return dashboardPanelRepository.save(panel);
    }

    @Transactional
    public DashboardPanel updatePanel(Long dashboardId, Long panelId, DashboardPanelUpsertRequest request) {
        DashboardPanel panel = dashboardPanelRepository.findById(panelId)
                .orElseThrow(() -> new IllegalArgumentException("패널을 찾을 수 없습니다: " + panelId));

        if (!dashboardId.equals(panel.getDashboardId())) {
            throw new IllegalArgumentException("대시보드와 패널이 일치하지 않습니다.");
        }

        applyPanelRequest(panel, request, false);
        panel.setUpdatedAt(LocalDateTime.now().toString());
        return dashboardPanelRepository.save(panel);
    }

    @Transactional
    public void deletePanel(Long dashboardId, Long panelId) {
        DashboardPanel panel = dashboardPanelRepository.findById(panelId)
                .orElseThrow(() -> new IllegalArgumentException("패널을 찾을 수 없습니다: " + panelId));
        if (!dashboardId.equals(panel.getDashboardId())) {
            throw new IllegalArgumentException("대시보드와 패널이 일치하지 않습니다.");
        }
        dashboardPanelRepository.delete(panel);
    }

    private void applyPanelRequest(DashboardPanel panel, DashboardPanelUpsertRequest request, boolean createMode) {
        if (createMode) {
            if (request.getPanelType() == null || request.getPanelType().isBlank()) {
                throw new IllegalArgumentException("panelType은 필수입니다.");
            }
            panel.setPanelType(request.getPanelType());
        } else if (request.getPanelType() != null && !request.getPanelType().isBlank()) {
            panel.setPanelType(request.getPanelType());
        }

        if (request.getRefGroupId() != null) panel.setRefGroupId(request.getRefGroupId());
        if (request.getConfigJson() != null) panel.setConfigJson(request.getConfigJson());
        if (request.getX() != null) panel.setX(request.getX());
        if (request.getY() != null) panel.setY(request.getY());
        // width/height: 양수 값일 때만 적용 (prefWidth(-1) 같은 JavaFX 기본값 무시)
        if (request.getWidth() != null && request.getWidth() > 0) panel.setWidth(request.getWidth());
        if (request.getHeight() != null && request.getHeight() > 0) panel.setHeight(request.getHeight());
        if (request.getZIndex() != null) panel.setZIndex(request.getZIndex());
        if (request.getVisible() != null) panel.setVisible(request.getVisible());

        if (panel.getConfigJson() == null) panel.setConfigJson("{}");
    }

    // ======== BACKUP/RESTORE METHODS ========

    /**
     * Create a backup of current dashboard layout
     */
    @Transactional
    public DashboardLayoutBackup backupLayout(Long dashboardId, String name, String description,
                                               String layoutDataJson, String createdBy) {
        // Verify dashboard exists
        dashboardLayoutRepository.findById(dashboardId)
                .orElseThrow(() -> new IllegalArgumentException("대시보드를 찾을 수 없습니다: " + dashboardId));

        DashboardLayoutBackup backup = new DashboardLayoutBackup(
                dashboardId,
                name != null ? name : ("자동백업_" + System.currentTimeMillis()),
                description,
                layoutDataJson,
                createdBy,
                false
        );
        backup = backupRepository.save(backup);
        log.info("Layout backup created: dashboardId={}, backupId={}", dashboardId, backup.getId());

        // Clean up old backups if exceeds limit
        cleanupOldBackups(dashboardId);

        return backup;
    }

    /**
     * List all backups for a dashboard with pagination
     */
    public Page<DashboardLayoutBackup> listBackups(Long dashboardId, int page, int size) {
        dashboardLayoutRepository.findById(dashboardId)
                .orElseThrow(() -> new IllegalArgumentException("대시보드를 찾을 수 없습니다: " + dashboardId));

        Pageable pageable = PageRequest.of(page, size);
        return backupRepository.findByDashboardId(dashboardId, pageable);
    }

    /**
     * Get a specific backup by ID
     */
    public DashboardLayoutBackup getBackup(Long backupId) {
        return backupRepository.findById(backupId)
                .orElseThrow(() -> new IllegalArgumentException("백업을 찾을 수 없습니다: " + backupId));
    }

    /**
     * Restore layout from a backup (copies backup to new snapshot)
     */
    @Transactional
    public DashboardLayoutBackup restoreFromBackup(Long dashboardId, Long backupId, String restoredBy) {
        DashboardLayout dashboard = dashboardLayoutRepository.findById(dashboardId)
                .orElseThrow(() -> new IllegalArgumentException("대시보드를 찾을 수 없습니다: " + dashboardId));

        DashboardLayoutBackup sourceBackup = backupRepository.findById(backupId)
                .orElseThrow(() -> new IllegalArgumentException("백업을 찾을 수 없습니다: " + backupId));

        if (!dashboardId.equals(sourceBackup.getDashboardId())) {
            throw new IllegalArgumentException("백업이 해당 대시보드에 속하지 않습니다.");
        }

        // Create a new backup from restored state
        DashboardLayoutBackup restoredBackup = new DashboardLayoutBackup(
                dashboardId,
                "복원됨_" + sourceBackup.getName() + "_" + System.currentTimeMillis(),
                "Restored from backup: " + sourceBackup.getName(),
                sourceBackup.getLayoutData(),
                restoredBy,
                false
        );
        restoredBackup = backupRepository.save(restoredBackup);
        log.info("Layout restored: dashboardId={}, sourceBackupId={}, restoredBackupId={}",
                dashboardId, backupId, restoredBackup.getId());

        return restoredBackup;
    }

    /**
     * Delete a specific backup
     */
    @Transactional
    public void deleteBackup(Long backupId) {
        DashboardLayoutBackup backup = backupRepository.findById(backupId)
                .orElseThrow(() -> new IllegalArgumentException("백업을 찾을 수 없습니다: " + backupId));
        backupRepository.delete(backup);
        log.info("Layout backup deleted: backupId={}", backupId);
    }

    /**
     * Cleanup old backups (keep only latest MAX_BACKUPS_PER_DASHBOARD)
     */
    @Transactional
    public void cleanupOldBackups(Long dashboardId) {
        long backupCount = backupRepository.countByDashboardId(dashboardId);
        if (backupCount > MAX_BACKUPS_PER_DASHBOARD) {
            // Delete oldest backups to maintain limit
            List<DashboardLayoutBackup> allBackups =
                    backupRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId);

            if (allBackups.size() > MAX_BACKUPS_PER_DASHBOARD) {
                List<DashboardLayoutBackup> toDelete =
                        allBackups.subList(MAX_BACKUPS_PER_DASHBOARD, allBackups.size());
                backupRepository.deleteAll(toDelete);
                log.debug("Cleaned up {} old backups for dashboardId={}", toDelete.size(), dashboardId);
            }
        }

        // Also delete backups older than retention period
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(BACKUP_RETENTION_DAYS);
        int deleted = backupRepository.deleteOldBackups(dashboardId, cutoffDate);
        if (deleted > 0) {
            log.debug("Deleted {} expired backups for dashboardId={}", deleted, dashboardId);
        }
    }

    /**
     * Create automatic backup (called by scheduler)
     */
    @Transactional
    public DashboardLayoutBackup createAutomaticBackup(Long dashboardId, String layoutDataJson) {
        DashboardLayout dashboard = dashboardLayoutRepository.findById(dashboardId)
                .orElseThrow(() -> new IllegalArgumentException("대시보드를 찾을 수 없습니다: " + dashboardId));

        DashboardLayoutBackup backup = new DashboardLayoutBackup(
                dashboardId,
                "자동백업_" + LocalDateTime.now(),
                null,
                layoutDataJson,
                "SYSTEM",
                true
        );
        backup = backupRepository.save(backup);
        log.debug("Automatic layout backup created: dashboardId={}, backupId={}", dashboardId, backup.getId());

        // Cleanup old backups
        cleanupOldBackups(dashboardId);

        return backup;
    }
}
