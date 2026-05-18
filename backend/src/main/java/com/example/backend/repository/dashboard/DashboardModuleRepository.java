package com.example.backend.repository.dashboard;

import com.example.backend.model.dashboard.DashboardModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * DashboardModule 엔티티 Repository.
 * 대시보드 모듈(독립 위젯)의 CRUD 및 그룹 ID 기반 조회를 제공한다.
 */
public interface DashboardModuleRepository extends JpaRepository<DashboardModule, Long> {

    /**
     * 특정 강의실 그룹에 속한 모듈 목록을 조회한다.
     *
     * @param groupId 강의실 그룹 ID
     * @return 모듈 목록
     */
    List<DashboardModule> findByGroupId(Long groupId);
}
