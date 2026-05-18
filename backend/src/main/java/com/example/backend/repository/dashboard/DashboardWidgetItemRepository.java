package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * DashboardWidgetItem 엔티티 Repository.
 * 대시보드 위젯 아이템의 CRUD 및 유형별/대시보드별 조회를 제공한다.
 */
@Repository
public interface DashboardWidgetItemRepository extends JpaRepository<DashboardWidgetItem, Long> {

    /**
     * 유형으로 위젯 아이템을 조회한다 (대소문자 무시).
     *
     * @param type 위젯 유형
     * @return 위젯 아이템 목록
     */
    List<DashboardWidgetItem> findByTypeIgnoreCase(String type);

    /**
     * 유형과 대시보드 ID로 위젯 아이템을 조회한다 (대소문자 무시).
     *
     * @param type        위젯 유형
     * @param dashboardId 대시보드 ID
     * @return 위젯 아이템 목록
     */
    List<DashboardWidgetItem> findByTypeIgnoreCaseAndDashboardId(String type, Long dashboardId);
}
