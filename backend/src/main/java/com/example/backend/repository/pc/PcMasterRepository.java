package com.example.backend.repository.pc;

import com.example.backend.model.pc.PcMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    List<PcMaster> findByStatus(String status);
    List<PcMaster> findByStatusIn(List<String> statuses);
    long countByStatus(String status);

    /** ONLINE 상태이면서 lastSeen이 threshold 이전인 PC (오프라인 판정용) */
    @Query("SELECT p FROM PcMaster p WHERE p.status = 'ONLINE' AND p.lastSeen < :threshold")
    List<PcMaster> findOnlinePcsLastSeenBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * ONLINE 상태에서 lastSeen이 threshold 이전인 PC를 OFFLINE으로 일괄 전환한다.
     *
     * <p>개별 {@code save()} N회 대신 단일 UPDATE 쿼리를 실행하여
     * 800대 환경에서의 오프라인 스캔 스파이크를 제거한다.</p>
     *
     * @return 업데이트된 PC 수
     */
    @Modifying
    @Transactional
    @Query("UPDATE PcMaster p SET p.status = 'OFFLINE' WHERE p.status = 'ONLINE' AND p.lastSeen < :threshold")
    int markOfflineByLastSeenBefore(@Param("threshold") LocalDateTime threshold);
}
