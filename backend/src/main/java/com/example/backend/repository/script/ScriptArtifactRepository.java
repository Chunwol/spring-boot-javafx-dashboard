package com.example.backend.repository.script;

import com.example.backend.model.script.ScriptArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptArtifactRepository extends JpaRepository<ScriptArtifact, Long> {
    ScriptArtifact findFirstBySha256(String sha256);

    ScriptArtifact findFirstBySha256AndArtifactKind(String sha256, String artifactKind);

    /** 저장된 전체 파일 용량 합산 (bytes). 에이전트 수집 파일 총량 제한에 사용. */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM ScriptArtifact a")
    long sumTotalSizeBytes();
}
