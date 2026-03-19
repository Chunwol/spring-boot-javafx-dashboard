package com.example.backend.repository.pc;

import com.example.backend.model.pc.PcMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PcMasterRepository extends JpaRepository<PcMaster, String> {
    Optional<PcMaster> findByIpAddress(String ipAddress);
    java.util.List<PcMaster> findByGroupIdAndStatus(Long groupId, String status);
    java.util.List<PcMaster> findByGroupId(Long groupId);
    java.util.List<PcMaster> findByGroupIdAndRole(Long groupId, String role);

    List<PcMaster> findByGroupIdIn(List<Long> groupIds);

    @Query("""
            SELECT p.groupId AS groupId,
                   COUNT(p) AS totalCount,
                   SUM(CASE WHEN p.lastSeen IS NOT NULL AND p.lastSeen >= :cutoff THEN 1 ELSE 0 END) AS onlineCount
            FROM PcMaster p
            WHERE p.groupId IS NOT NULL
            GROUP BY p.groupId
            """)
    List<GroupPcStatsProjection> findGroupPcStats(@Param("cutoff") LocalDateTime cutoff);

    interface GroupPcStatsProjection {
        Long getGroupId();

        Long getTotalCount();

        Long getOnlineCount();
    }
}
