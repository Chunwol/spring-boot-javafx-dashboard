package com.example.backend.service.dashboard;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.dashboard.DashboardPanelUpsertRequest;
import com.example.backend.model.dashboard.DashboardLayout;
import com.example.backend.model.dashboard.DashboardLayoutBackup;
import com.example.backend.model.dashboard.DashboardPanel;
import com.example.backend.repository.dashboard.DashboardLayoutBackupRepository;
import com.example.backend.repository.dashboard.DashboardLayoutRepository;
import com.example.backend.repository.dashboard.DashboardPanelRepository;
import tools.jackson.databind.ObjectMapper;
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

/**
 * 대시보드 레이아웃 및 패널 CRUD, 백업/복원 관리 서비스.
 * <p>
 * 대시보드당 최대 {@code MAX_BACKUPS_PER_DASHBOARD}(50)개의 백업을 유지하며,
 * {@code BACKUP_RETENTION_DAYS}(90일) 초과 백업은 자동 삭제한다.
 * </p>
 */
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
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", id));

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
                .orElseThrow(() -> new ResourceNotFoundException("Panel", panelId));

        if (!dashboardId.equals(panel.getDashboardId())) {
            throw new ConflictException("Panel", "Dashboard ID mismatch");
        }

        applyPanelRequest(panel, request, false);
        panel.setUpdatedAt(LocalDateTime.now().toString());
        return dashboardPanelRepository.save(panel);
    }

    /** 여러 패널의 위치/크기/Z-순서를 1개 트랜잭션에서 일괄 업데이트. */
    @Transactional
    public java.util.List<DashboardPanel> batchUpdatePositions(
            Long dashboardId,
            com.example.backend.dto.dashboard.DashboardPanelBatchPositionRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<DashboardPanel> updated = new java.util.ArrayList<>(request.getItems().size());
        String now = LocalDateTime.now().toString();
        for (var item : request.getItems()) {
            if (item == null || item.getPanelId() == null) continue;
            DashboardPanel panel = dashboardPanelRepository.findById(item.getPanelId())
                    .orElseThrow(() -> new ResourceNotFoundException("Panel", item.getPanelId()));
            if (!dashboardId.equals(panel.getDashboardId())) {
                throw new ConflictException("Panel", "Dashboard ID mismatch");
            }
            applyPositionItem(panel, item);
            panel.setUpdatedAt(now);
            updated.add(panel);
        }
        return dashboardPanelRepository.saveAll(updated);
    }

    private void applyPositionItem(DashboardPanel panel,
                                    com.example.backend.dto.dashboard.DashboardPanelBatchPositionRequest.Item item) {
        if (item.getX() != null) panel.setX(item.getX());
        if (item.getY() != null) panel.setY(item.getY());
        if (item.getWidth() != null && item.getWidth() > 0) panel.setWidth(item.getWidth());
        if (item.getHeight() != null && item.getHeight() > 0) panel.setHeight(item.getHeight());
        if (item.getZIndex() != null) panel.setZIndex(item.getZIndex());
        if (item.getVisible() != null) panel.setVisible(item.getVisible());
    }

    @Transactional
    public void deletePanel(Long dashboardId, Long panelId) {
        DashboardPanel panel = dashboardPanelRepository.findById(panelId)
                .orElseThrow(() -> new ResourceNotFoundException("Panel", panelId));
        if (!dashboardId.equals(panel.getDashboardId())) {
            throw new ConflictException("Panel", "Dashboard ID mismatch");
        }
        dashboardPanelRepository.delete(panel);
    }

    private void applyPanelRequest(DashboardPanel panel, DashboardPanelUpsertRequest request, boolean createMode) {
        if (createMode) {
            if (request.getPanelType() == null || request.getPanelType().isBlank()) {
                throw new InvalidRequestException("panelType", "required field");
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
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", dashboardId));

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
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", dashboardId));

        Pageable pageable = PageRequest.of(page, size);
        return backupRepository.findByDashboardId(dashboardId, pageable);
    }

    /**
     * Get a specific backup by ID
     */
    public DashboardLayoutBackup getBackup(Long backupId) {
        return backupRepository.findById(backupId)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardLayoutBackup", backupId));
    }

    /**
     * Restore layout from a backup (copies backup to new snapshot)
     */
    @Transactional
    public DashboardLayoutBackup restoreFromBackup(Long dashboardId, Long backupId, String restoredBy) {
        DashboardLayout dashboard = dashboardLayoutRepository.findById(dashboardId)
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", dashboardId));

        DashboardLayoutBackup sourceBackup = backupRepository.findById(backupId)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardLayoutBackup", backupId));

        if (!dashboardId.equals(sourceBackup.getDashboardId())) {
            throw new ConflictException("DashboardLayoutBackup", "Backup does not belong to this dashboard");
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
                .orElseThrow(() -> new ResourceNotFoundException("DashboardLayoutBackup", backupId));
        backupRepository.delete(backup);
        log.info("Layout backup deleted: backupId={}", backupId);
    }

    /**
     * 오래된 백업을 정리하여 대시보드당 최대 {@value #MAX_BACKUPS_PER_DASHBOARD}개를 유지한다.
     * <p>
     * <b>WHY 단일 쿼리:</b> 기존 {@code countByDashboardId} → {@code findByDashboardIdOrderByCreatedAtDesc}
     * 두 쿼리 패턴은 count와 list 사이에 다른 트랜잭션이 백업을 추가/삭제하면
     * count 결과와 실제 리스트 크기가 달라지는 TOCTOU(Time-of-Check-Time-of-Use) 문제가 있었다.
     * {@code findByDashboardIdOrderByCreatedAtDesc} 한 번 호출로 size 체크와 삭제를 모두 처리한다.
     * </p>
     *
     * @param dashboardId 백업 정리 대상 대시보드 ID
     */
    @Transactional
    public void cleanupOldBackups(Long dashboardId) {
        // 단일 쿼리로 정렬된 전체 리스트를 가져와 TOCTOU 제거
        List<DashboardLayoutBackup> allBackups =
                backupRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId);

        if (allBackups.size() > MAX_BACKUPS_PER_DASHBOARD) {
            // 최신 MAX_BACKUPS_PER_DASHBOARD건을 남기고 나머지 삭제
            List<DashboardLayoutBackup> toDelete =
                    allBackups.subList(MAX_BACKUPS_PER_DASHBOARD, allBackups.size());
            backupRepository.deleteAll(toDelete);
            log.debug("Cleaned up {} old backups for dashboardId={}", toDelete.size(), dashboardId);
        }

        // 보존 기간(BACKUP_RETENTION_DAYS일)을 초과한 백업도 추가 삭제
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
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", dashboardId));

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
