package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.DashboardPanelUpsertRequest;
import com.example.backend.model.dashboard.DashboardLayout;
import com.example.backend.model.dashboard.DashboardPanel;
import com.example.backend.repository.dashboard.DashboardLayoutRepository;
import com.example.backend.repository.dashboard.DashboardPanelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardLayoutService {

    private final DashboardLayoutRepository dashboardLayoutRepository;
    private final DashboardPanelRepository dashboardPanelRepository;

    public DashboardLayoutService(DashboardLayoutRepository dashboardLayoutRepository,
                                  DashboardPanelRepository dashboardPanelRepository) {
        this.dashboardLayoutRepository = dashboardLayoutRepository;
        this.dashboardPanelRepository = dashboardPanelRepository;
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
        if (request.getWidth() != null) panel.setWidth(request.getWidth());
        if (request.getHeight() != null) panel.setHeight(request.getHeight());
        if (request.getZIndex() != null) panel.setZIndex(request.getZIndex());
        if (request.getVisible() != null) panel.setVisible(request.getVisible());

        if (panel.getConfigJson() == null) panel.setConfigJson("{}");
    }
}
