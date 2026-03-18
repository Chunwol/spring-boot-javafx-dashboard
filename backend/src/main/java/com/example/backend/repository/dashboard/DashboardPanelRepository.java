package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardPanel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DashboardPanelRepository extends JpaRepository<DashboardPanel, Long> {
    @Query("SELECT p FROM DashboardPanel p WHERE p.dashboardId = :dashboardId ORDER BY p.zIndex ASC, p.id ASC")
    List<DashboardPanel> findPanelsByDashboardId(@Param("dashboardId") Long dashboardId);
}
