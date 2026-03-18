package com.example.backend.controller.dashboard;

import com.example.backend.dto.dashboard.DashboardPanelUpsertRequest;
import com.example.backend.model.dashboard.DashboardLayout;
import com.example.backend.model.dashboard.DashboardPanel;
import com.example.backend.service.dashboard.DashboardLayoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardLayoutController {

    private final DashboardLayoutService dashboardLayoutService;

    public DashboardLayoutController(DashboardLayoutService dashboardLayoutService) {
        this.dashboardLayoutService = dashboardLayoutService;
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
}
