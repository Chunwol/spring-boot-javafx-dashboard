package com.example.backend.repository.filedist;

import com.example.backend.model.filedist.FileChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {
    // 추가 쿼리 필요시 선언
}
