package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardPanel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * DashboardPanel 엔티티 Repository.
 * 대시보드 패널(위젯)의 CRUD 및 대시보드 ID 기반 조회를 제공한다.
 */
public interface DashboardPanelRepository extends JpaRepository<DashboardPanel, Long> {

    /**
     * 특정 대시보드의 패널 목록을 z-인덱스 오름차순, ID 오름차순으로 조회한다.
     *
     * @param dashboardId 대시보드 ID
     * @return 패널 목록
     */
    @Query("SELECT p FROM DashboardPanel p WHERE p.dashboardId = :dashboardId ORDER BY p.zIndex ASC, p.id ASC")
    List<DashboardPanel> findPanelsByDashboardId(@Param("dashboardId") Long dashboardId);
}
