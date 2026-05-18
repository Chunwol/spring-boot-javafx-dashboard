package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ClassroomGroup 엔티티 Repository.
 * 강의실 그룹의 CRUD, 이름 중복 확인,
 * PC 통계(총수/온라인수)를 포함한 집계 조회를 제공한다.
 */
@Repository
public interface ClassroomGroupRepository extends JpaRepository<ClassroomGroup, Long> {

    /**
     * 그룹 이름으로 강의실 그룹을 조회한다.
     *
     * @param name 그룹 이름
     * @return 강의실 그룹
     */
    ClassroomGroup findByName(String name);

    /**
     * 이름이 대소문자 구분 없이 존재하는지 확인한다.
     *
     * @param name 확인할 이름
     * @return 존재 여부
     */
    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM ClassroomGroup g WHERE UPPER(g.name) = UPPER(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    /**
     * 특정 ID를 제외하고 이름이 대소문자 구분 없이 존재하는지 확인한다 (수정 시 중복 검사).
     *
     * @param name 확인할 이름
     * @param id   제외할 그룹 ID
     * @return 존재 여부
     */
    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM ClassroomGroup g WHERE UPPER(g.name) = UPPER(:name) AND g.id != :id")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("id") Long id);

    /**
     * 전체 강의실 목록과 각 그룹의 PC 집계(총수/온라인수)를 단일 LEFT JOIN 쿼리로 반환합니다.
     * DB 벤더 의존성이 없도록 JPQL을 사용합니다.
     *
     * @param cutoff 온라인 판정 기준 시각 (이 시각 이후 lastSeen이면 온라인)
     * @return 그룹별 PC 통계 목록
     */
    @Query("""
            SELECT g.id AS groupId,
                   g.name AS groupName,
                   COUNT(p.macAddress) AS totalCount,
                   SUM(CASE WHEN p.lastSeen IS NOT NULL AND p.lastSeen >= :cutoff THEN 1 ELSE 0 END) AS onlineCount
            FROM ClassroomGroup g
            LEFT JOIN PcMaster p ON p.groupId = g.id
            GROUP BY g.id, g.name
            ORDER BY g.id
            """)
    List<GroupWithPcStatsProjection> findAllWithPcStats(@Param("cutoff") LocalDateTime cutoff);

    /** PC 통계 프로젝션 인터페이스 */
    interface GroupWithPcStatsProjection {
        Long getGroupId();
        String getGroupName();
        Long getTotalCount();
        Long getOnlineCount();
    }
}
