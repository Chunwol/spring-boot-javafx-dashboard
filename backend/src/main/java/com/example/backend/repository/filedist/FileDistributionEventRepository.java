package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileDistributionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * FileDistributionEvent 엔티티 Repository.
 * 파일 배포 작업의 이벤트 로그(CREATED, CANCELLED, COMPLETED 등)를
 * 작업 ID 기준으로 최신순 조회한다.
 */
@Repository
public interface FileDistributionEventRepository extends JpaRepository<FileDistributionEvent, Long> {

    /**
     * 특정 배포 작업의 이벤트를 최신순으로 조회한다.
     *
     * @param jobId 배포 작업 ID
     * @return 이벤트 목록 (최신순)
     */
    List<FileDistributionEvent> findByJobIdOrderByCreatedAtDesc(Long jobId);
}
