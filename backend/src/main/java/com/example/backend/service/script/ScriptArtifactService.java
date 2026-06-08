package com.example.backend.service.script;

import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.repository.script.ScriptArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScriptArtifactService {

    private final ScriptArtifactRepository scriptArtifactRepository;

    public ScriptArtifact findBySha256(String sha256) {
        return scriptArtifactRepository.findFirstBySha256(sha256);
    }

    @Transactional
    public ScriptArtifact save(ScriptArtifact artifact) {
        return scriptArtifactRepository.save(artifact);
    }

    public Optional<ScriptArtifact> findById(Long id) {
        return scriptArtifactRepository.findById(id);
    }

    public long getTotalSizeBytes() {
        return scriptArtifactRepository.sumTotalSizeBytes();
    }
}
