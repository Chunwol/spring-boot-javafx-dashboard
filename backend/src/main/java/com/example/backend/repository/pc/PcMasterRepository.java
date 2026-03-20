package com.example.backend.repository.pc;

import com.example.backend.model.pc.PcMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PcMasterRepository extends JpaRepository<PcMaster, String> {
    Optional<PcMaster> findByIpAddress(String ipAddress);
    List<PcMaster> findByGroupIdAndStatus(Long groupId, String status);
    List<PcMaster> findByGroupId(Long groupId);
    List<PcMaster> findByGroupIdAndRole(Long groupId, String role);
    List<PcMaster> findByGroupIdIn(List<Long> groupIds);
    long countByStatus(String status);

    /** ONLINE 상태이면서 lastSeen이 threshold 이전인 PC (오프라인 판정용) */
    @Query("SELECT p FROM PcMaster p WHERE p.status = 'ONLINE' AND p.lastSeen < :threshold")
    List<PcMaster> findOnlinePcsLastSeenBefore(@Param("threshold") LocalDateTime threshold);

    /** 오프라인 일괄 전환 (JPQL UPDATE) */
    @Modifying
    @Query("UPDATE PcMaster p SET p.status = 'OFFLINE' WHERE p.status = 'ONLINE' AND p.lastSeen < :threshold")
    int markOfflineByLastSeenBefore(@Param("threshold") LocalDateTime threshold);
}
