package com.example.backend.repository.alert;

import com.example.backend.model.alert.OperationalAlert;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * OperationalAlert 엔티티 Repository.
 * 운영 알림의 페이징 조회, 미확인 개수 집계,
 * 일괄/대상별 확인(acknowledge) 처리를 제공한다.
 */
public interface OperationalAlertRepository extends JpaRepository<OperationalAlert, Long> {

    /**
     * 모든 알림을 최신순으로 페이징 조회한다.
     *
     * @param pageable 페이지 정보
     * @return 페이징된 알림 목록
     */
    Page<OperationalAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 미확인 알림을 최신순으로 페이징 조회한다.
     *
     * @param pageable 페이지 정보
     * @return 페이징된 미확인 알림 목록
     */
    Page<OperationalAlert> findByAcknowledgedFalseOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 미확인 알림 수를 조회한다.
     *
     * @return 미확인 알림 수
     */
    long countByAcknowledgedFalse();

    /**
     * 모든 미확인 알림을 일괄 확인 처리한다.
     *
     * @param acknowledgedAt  확인 일시
     * @param acknowledgedBy  확인 관리자명
     * @return 처리된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
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
    @Transactional
    @Query("""
           UPDATE OperationalAlert a
              SET a.acknowledged = true,
                  a.acknowledgedAt = :acknowledgedAt,
                  a.acknowledgedBy = :acknowledgedBy
            WHERE a.acknowledged = false
              AND a.id IN :ids
           """)
    int acknowledgeUnreadByIds(@Param("ids") Collection<Long> ids,
                               @Param("acknowledgedAt") LocalDateTime acknowledgedAt,
                               @Param("acknowledgedBy") String acknowledgedBy);

    /**
     * 특정 소스(MAC) 및 알림 유형의 미확인 알림을 확인 처리한다.
     *
     * @param sourceMac      소스 MAC 주소
     * @param alertType      알림 유형
     * @param acknowledgedAt 확인 일시
     * @param acknowledgedBy 확인 관리자명
     * @return 처리된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
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
