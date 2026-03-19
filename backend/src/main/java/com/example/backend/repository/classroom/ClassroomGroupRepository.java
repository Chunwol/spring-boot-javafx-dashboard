package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassroomGroupRepository extends JpaRepository<ClassroomGroup, Long> {
    ClassroomGroup findByName(String name);

    /**
     * 전체 강의실 목록과 각 그룹의 PC 집계(총수/온라인수)를 단일 LEFT JOIN 쿼리로 반환합니다.
     * SQLite TEXT ISO-8601 포맷의 last_seen 컬럼을 문자열 비교로 처리합니다.
     *
     * @param cutoff ISO-8601 형식 cutoff 시각 (예: 2026-03-19T17:00:00)
     */
    @Query(nativeQuery = true, value = """
            SELECT g.id   AS groupId,
                   g.name AS groupName,
                   COUNT(p.mac_address) AS totalCount,
                   SUM(CASE WHEN p.last_seen IS NOT NULL AND p.last_seen >= :cutoff
                            THEN 1 ELSE 0 END) AS onlineCount
            FROM classroom_groups g
            LEFT JOIN pc_master p ON p.group_id = g.id
            GROUP BY g.id, g.name
            ORDER BY g.id
            """)
    List<GroupWithPcStatsProjection> findAllWithPcStats(@Param("cutoff") String cutoff);

    interface GroupWithPcStatsProjection {
        Long getGroupId();
        String getGroupName();
        Long getTotalCount();
        Long getOnlineCount();
    }
}
