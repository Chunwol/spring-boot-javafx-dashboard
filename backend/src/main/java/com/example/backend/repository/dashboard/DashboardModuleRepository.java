package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardModule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DashboardModuleRepository extends JpaRepository<DashboardModule, Long> {
    List<DashboardModule> findByGroupId(Long groupId);
}
