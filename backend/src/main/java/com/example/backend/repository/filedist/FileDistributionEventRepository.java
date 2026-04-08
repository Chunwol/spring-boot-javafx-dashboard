package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileDistributionEventRepository extends JpaRepository<FileDistributionEvent, Long> {
    List<FileDistributionEvent> findByJobIdOrderByCreatedAtDesc(Long jobId);
}
