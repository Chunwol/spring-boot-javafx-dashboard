package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileDistributionJobRepository extends JpaRepository<FileDistributionJob, Long> {
    FileDistributionJob findByUploadId(String uploadId);
    List<FileDistributionJob> findTop50ByOrderByCreatedAtDesc();
}
