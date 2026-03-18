package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DashboardWidgetItemRepository extends JpaRepository<DashboardWidgetItem, Long> {
}