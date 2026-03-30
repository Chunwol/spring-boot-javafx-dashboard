package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DashboardWidgetItemRepository extends JpaRepository<DashboardWidgetItem, Long> {
	List<DashboardWidgetItem> findByTypeIgnoreCase(String type);
	List<DashboardWidgetItem> findByTypeIgnoreCaseAndDashboardId(String type, Long dashboardId);
}