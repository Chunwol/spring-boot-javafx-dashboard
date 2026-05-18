package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * FileChunk 엔티티 Repository.
 * 파일 배포 작업의 분할 청크 CRUD를 제공한다.
 * 청크 상태 조회 등 추가 쿼리가 필요한 경우 이 인터페이스에 선언한다.
 */
@Repository
public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {
    // 추가 쿼리 필요시 선언
}
