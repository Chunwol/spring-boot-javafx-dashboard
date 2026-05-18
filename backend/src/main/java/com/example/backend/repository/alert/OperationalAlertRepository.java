package com.example.backend.repository.alert;

import com.example.backend.model.alert.OperationalAlert;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface OperationalAlertRepository extends JpaRepository<OperationalAlert, Long> {

    Page<OperationalAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<OperationalAlert> findByAcknowledgedFalseOrderByCreatedAtDesc(Pageable pageable);

    long countByAcknowledgedFalse();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE OperationalAlert a
              SET a.acknowledged = true,
                  a.acknowledgedAt = :acknowledgedAt,
                  a.acknowledgedBy = :acknowledgedBy
            WHERE a.acknowledged = false
           """)
    int acknowledgeAllUnread(@Param("acknowledgedAt") LocalDateTime acknowledgedAt,
                             @Param("acknowledgedBy") String acknowledgedBy);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE OperationalAlert a
              SET a.acknowledged = true,
                  a.acknowledgedAt = :acknowledgedAt,
                  a.acknowledgedBy = :acknowledgedBy
            WHERE a.acknowledged = false
              AND a.sourceMac = :sourceMac
              AND a.alertType = :alertType
           """)
    int acknowledgeUnreadBySourceAndType(@Param("sourceMac") String sourceMac,
                                         @Param("alertType") String alertType,
                                         @Param("acknowledgedAt") LocalDateTime acknowledgedAt,
                                         @Param("acknowledgedBy") String acknowledgedBy);
}
