package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.DashboardPanelUpsertRequest;
import com.example.backend.model.dashboard.DashboardLayout;
import com.example.backend.model.dashboard.DashboardLayoutBackup;
import com.example.backend.model.dashboard.DashboardPanel;
import com.example.backend.service.dashboard.DashboardLayoutService;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardLayoutController {

    private final DashboardLayoutService dashboardLayoutService;
    private final ObjectMapper objectMapper;

    public DashboardLayoutController(DashboardLayoutService dashboardLayoutService,
                                     ObjectMapper objectMapper) {
        this.dashboardLayoutService = dashboardLayoutService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<DashboardLayout>> getDashboards() {
        return ResponseEntity.ok(dashboardLayoutService.getDashboards());
    }

    @PostMapping
    public ResponseEntity<DashboardLayout> createDashboard(@RequestBody DashboardLayout request) {
        return ResponseEntity.status(201).body(dashboardLayoutService.createDashboard(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DashboardLayout> updateDashboard(@PathVariable Long id, @RequestBody DashboardLayout request) {
        return ResponseEntity.ok(dashboardLayoutService.updateDashboard(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDashboard(@PathVariable Long id) {
        dashboardLayoutService.deleteDashboard(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/panels")
    public ResponseEntity<List<DashboardPanel>> getPanels(@PathVariable Long id) {
        return ResponseEntity.ok(dashboardLayoutService.getPanels(id));
    }

    @PostMapping("/{id}/panels")
    public ResponseEntity<DashboardPanel> createPanel(@PathVariable Long id,
                                                      @Valid @RequestBody DashboardPanelUpsertRequest request) {
        return ResponseEntity.status(201).body(dashboardLayoutService.createPanel(id, request));
    }

    @PutMapping("/{id}/panels/{panelId}")
    public ResponseEntity<DashboardPanel> updatePanel(@PathVariable Long id,
                                                      @PathVariable Long panelId,
                                                      @Valid @RequestBody DashboardPanelUpsertRequest request) {
        return ResponseEntity.ok(dashboardLayoutService.updatePanel(id, panelId, request));
    }

    @DeleteMapping("/{id}/panels/{panelId}")
    public ResponseEntity<Void> deletePanel(@PathVariable Long id, @PathVariable Long panelId) {
        dashboardLayoutService.deletePanel(id, panelId);
        return ResponseEntity.noContent().build();
    }

    // ======== BACKUP/RESTORE ENDPOINTS ========

    /**
     * Create a backup of dashboard layout
     * POST /api/v1/dashboards/{id}/layouts/backup
     */
    @PostMapping("/{id}/layouts/backup")
    public ResponseEntity<DashboardLayoutBackup> backupLayout(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> request) {

        String name = request != null ? (String) request.get("name") : null;
        String description = request != null ? (String) request.get("description") : null;
        Object layoutData = request != null ? request.get("layoutData") : new HashMap<>();

        // Serialize layout data to JSON
        String layoutDataJson;
        try {
            layoutDataJson = objectMapper.writeValueAsString(layoutData);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        DashboardLayoutBackup backup = dashboardLayoutService.backupLayout(
                id, name, description, layoutDataJson, "USER"
        );
        return ResponseEntity.status(201).body(backup);
    }

    /**
     * List all backups for a dashboard
     * GET /api/v1/dashboards/{id}/layouts/backups?page=0&size=10
     */
    @GetMapping("/{id}/layouts/backups")
    public ResponseEntity<Page<DashboardLayoutBackup>> listBackups(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<DashboardLayoutBackup> backups = dashboardLayoutService.listBackups(id, page, size);
        return ResponseEntity.ok(backups);
    }

    /**
     * Get a specific backup
     * GET /api/v1/dashboards/{id}/layouts/backups/{backupId}
     */
    @GetMapping("/{id}/layouts/backups/{backupId}")
    public ResponseEntity<DashboardLayoutBackup> getBackup(
            @PathVariable Long id,
            @PathVariable Long backupId) {

        DashboardLayoutBackup backup = dashboardLayoutService.getBackup(backupId);

        // Verify ownership
        if (!id.equals(backup.getDashboardId())) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(backup);
    }

    /**
     * Restore layout from a backup
     * POST /api/v1/dashboards/{id}/layouts/restore/{backupId}
     */
    @PostMapping("/{id}/layouts/restore/{backupId}")
    public ResponseEntity<DashboardLayoutBackup> restoreBackup(
            @PathVariable Long id,
            @PathVariable Long backupId) {

        DashboardLayoutBackup restored = dashboardLayoutService.restoreFromBackup(id, backupId, "USER");
        return ResponseEntity.ok(restored);
    }

    /**
     * Delete a backup
     * DELETE /api/v1/dashboards/{id}/layouts/backups/{backupId}
     */
    @DeleteMapping("/{id}/layouts/backups/{backupId}")
    public ResponseEntity<Void> deleteBackup(
            @PathVariable Long id,
            @PathVariable Long backupId) {

        DashboardLayoutBackup backup = dashboardLayoutService.getBackup(backupId);

        // Verify ownership
        if (!id.equals(backup.getDashboardId())) {
            return ResponseEntity.notFound().build();
        }

        dashboardLayoutService.deleteBackup(backupId);
        return ResponseEntity.noContent().build();
    }
}
