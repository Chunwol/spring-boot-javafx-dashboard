package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ClassroomGroupRepository extends JpaRepository<ClassroomGroup, Long> {
    ClassroomGroup findByName(String name);

    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM ClassroomGroup g WHERE UPPER(g.name) = UPPER(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM ClassroomGroup g WHERE UPPER(g.name) = UPPER(:name) AND g.id != :id")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("id") Long id);

    /**
     * 전체 강의실 목록과 각 그룹의 PC 집계(총수/온라인수)를 단일 LEFT JOIN 쿼리로 반환합니다.
     * DB 벤더 의존성이 없도록 JPQL을 사용합니다.
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

    interface GroupWithPcStatsProjection {
        Long getGroupId();
        String getGroupName();
        Long getTotalCount();
        Long getOnlineCount();
    }
}
