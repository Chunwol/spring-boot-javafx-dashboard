package com.example.backend.repository.agent;

import com.example.backend.model.agent.AgentAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AgentAuthToken 엔티티 Repository.
 * 에이전트별 인증 토큰의 발급, 폐기 여부 확인, 해시 기반 조회를 제공한다.
 */
public interface AgentAuthTokenRepository extends JpaRepository<AgentAuthToken, Long> {

    /**
     * MAC 주소로 에이전트 토큰을 조회한다.
     *
     * @param macAddress 에이전트 MAC 주소
     * @return 토큰 (없으면 empty)
     */
    Optional<AgentAuthToken> findByMacAddress(String macAddress);

    /**
     * MAC 주소에 연결된 모든 토큰을 조회한다 (재발급 이력 포함).
     *
     * @param macAddress 에이전트 MAC 주소
     * @return 토큰 목록
     */
    List<AgentAuthToken> findAllByMacAddress(String macAddress);

    /**
     * 토큰 해시로 에이전트 토큰을 조회한다 (인증 필터에서 사용).
     *
     * @param tokenHash SHA-256 해시값
     * @return 토큰 (없으면 empty)
     */
    Optional<AgentAuthToken> findByTokenHash(String tokenHash);

    /**
     * 폐기 여부(revoked)로 토큰 수를 집계한다.
     *
     * @param revoked 폐기 여부
     * @return 해당 토큰 수
     */
    long countByRevoked(boolean revoked);
}
