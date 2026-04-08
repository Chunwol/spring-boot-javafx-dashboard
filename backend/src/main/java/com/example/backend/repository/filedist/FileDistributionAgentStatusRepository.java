package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionAgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileDistributionAgentStatusRepository extends JpaRepository<FileDistributionAgentStatus, Long> {
    List<FileDistributionAgentStatus> findByJobIdOrderByWaveAscIdAsc(Long jobId);
    List<FileDistributionAgentStatus> findByJobIdAndWaveOrderByIdAsc(Long jobId, Integer wave);
    List<FileDistributionAgentStatus> findByJobIdAndStatusOrderByWaveAscIdAsc(Long jobId, String status);
    List<FileDistributionAgentStatus> findByJobIdAndWaveAndStatusOrderByIdAsc(Long jobId, Integer wave, String status);
    Optional<FileDistributionAgentStatus> findByJobIdAndAgentId(Long jobId, String agentId);

    long countByJobIdAndStatus(Long jobId, String status);
    long countByJobIdAndWaveAndStatus(Long jobId, Integer wave, String status);
}
