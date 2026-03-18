package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardLayoutRepository extends JpaRepository<DashboardLayout, Long> {
    List<DashboardLayout> findAllByOrderByDisplayOrderAscIdAsc();
}
