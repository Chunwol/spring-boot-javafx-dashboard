package com.example.backend.repository.script;

import com.example.backend.model.script.ScriptArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptArtifactRepository extends JpaRepository<ScriptArtifact, Long> {
    ScriptArtifact findFirstBySha256(String sha256);
}

