package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionAgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * FileDistributionAgentStatus 엔티티 Repository.
 * 파일 배포 작업에서 에이전트별 웨이브 배정 및 진행 상태를 조회한다.
 * 웨이브 기반 단계적 배포(ramp-up) 로직에서 핵심적으로 사용된다.
 */
@Repository
public interface FileDistributionAgentStatusRepository extends JpaRepository<FileDistributionAgentStatus, Long> {

    /**
     * 특정 작업의 에이전트 상태를 웨이브·ID 오름차순으로 조회한다.
     *
     * @param jobId 배포 작업 ID
     * @return 에이전트 상태 목록
     */
    List<FileDistributionAgentStatus> findByJobIdOrderByWaveAscIdAsc(Long jobId);

    /**
     * 특정 작업의 특정 웨이브 에이전트 상태를 ID 오름차순으로 조회한다.
     *
     * @param jobId 배포 작업 ID
     * @param wave  웨이브 번호
     * @return 에이전트 상태 목록
     */
    List<FileDistributionAgentStatus> findByJobIdAndWaveOrderByIdAsc(Long jobId, Integer wave);

    /**
     * 특정 작업에서 지정 상태인 에이전트를 웨이브·ID 오름차순으로 조회한다.
     *
     * @param jobId  배포 작업 ID
     * @param status 상태 (QUEUED, IN_PROGRESS, COMPLETED, FAILED 등)
     * @return 에이전트 상태 목록
     */
    List<FileDistributionAgentStatus> findByJobIdAndStatusOrderByWaveAscIdAsc(Long jobId, String status);

    /**
     * 특정 작업의 특정 웨이브에서 지정 상태인 에이전트를 ID 오름차순으로 조회한다.
     *
     * @param jobId  배포 작업 ID
     * @param wave   웨이브 번호
     * @param status 상태
     * @return 에이전트 상태 목록
     */
    List<FileDistributionAgentStatus> findByJobIdAndWaveAndStatusOrderByIdAsc(Long jobId, Integer wave, String status);

    /**
     * 특정 작업에서 에이전트 ID로 상태를 조회한다.
     *
     * @param jobId   배포 작업 ID
     * @param agentId 에이전트 ID (MAC 주소)
     * @return 에이전트 상태 (없으면 empty)
     */
    Optional<FileDistributionAgentStatus> findByJobIdAndAgentId(Long jobId, String agentId);

    /**
     * 특정 작업에서 지정 상태인 에이전트 수를 집계한다.
     *
     * @param jobId  배포 작업 ID
     * @param status 상태
     * @return 에이전트 수
     */
    long countByJobIdAndStatus(Long jobId, String status);

    /**
     * 특정 작업의 특정 웨이브에서 지정 상태인 에이전트 수를 집계한다.
     *
     * @param jobId  배포 작업 ID
     * @param wave   웨이브 번호
     * @param status 상태
     * @return 에이전트 수
     */
    long countByJobIdAndWaveAndStatus(Long jobId, Integer wave, String status);
}
