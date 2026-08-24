package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileDistributionJobRepository extends JpaRepository<FileDistributionJob, Long> {
    FileDistributionJob findByUploadId(String uploadId);
    List<FileDistributionJob> findTop50ByOrderByCreatedAtDesc();
    Optional<FileDistributionJob> findFirstByStatusInAndArtifactIdIsNotNullAndTargetVersionIsNotNullOrderByCreatedAtDesc(List<String> statuses);
    Optional<FileDistributionJob> findFirstByStatusAndArtifactIdIsNotNullAndTargetVersionIsNotNullOrderByCompletedAtDescIdDesc(String status);
    Optional<FileDistributionJob> findFirstByStatusAndArtifactIdIsNotNullAndTargetVersionIsNotNullAndIdLessThanOrderByCompletedAtDescIdDesc(String status, Long id);
    List<FileDistributionJob> findByUpdatePackageTypeAndStatusIn(String updatePackageType, List<String> statuses);
    boolean existsBySourceAgentIdIgnoreCaseAndStatusIn(String sourceAgentId, List<String> statuses);
}
