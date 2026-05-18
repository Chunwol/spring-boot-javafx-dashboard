package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * DashboardLayout 엔티티 Repository.
 * 대시보드 레이아웃의 CRUD 및 표시 순서 기반 정렬 조회를 제공한다.
 */
public interface DashboardLayoutRepository extends JpaRepository<DashboardLayout, Long> {

    /**
     * 모든 대시보드를 표시 순서(displayOrder) 오름차순, ID 오름차순으로 조회한다.
     *
     * @return 정렬된 대시보드 목록
     */
    List<DashboardLayout> findAllByOrderByDisplayOrderAscIdAsc();
}
