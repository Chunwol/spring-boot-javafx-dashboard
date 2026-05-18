package com.example.backend.repository.audit;

import com.example.backend.model.audit.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AuditLog 엔티티 Repository.
 * 시스템 주요 이벤트(에이전트 등록, 명령 실행, 토큰 폐기 등)의
 * 감사 로그를 행위자(actor) 및 이벤트 유형(action)별로 조회한다.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 최근 감사 로그 100건을 최신순으로 조회한다.
     *
     * @return 감사 로그 목록 (최대 100건)
     */
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    /**
     * 특정 행위자의 감사 로그를 최신순으로 조회한다.
     *
     * @param actor 행위자 (MAC 주소 또는 "system")
     * @return 감사 로그 목록
     */
    List<AuditLog> findByActorOrderByCreatedAtDesc(String actor);

    /**
     * 특정 이벤트 유형의 감사 로그를 최신순으로 조회한다.
     *
     * @param action 이벤트 유형 (AGENT_REGISTER, HEARTBEAT 등)
     * @return 감사 로그 목록
     */
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
