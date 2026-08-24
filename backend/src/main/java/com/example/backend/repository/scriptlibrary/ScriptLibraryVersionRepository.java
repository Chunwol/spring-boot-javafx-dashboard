package com.example.backend.repository.scriptlibrary;

import com.example.backend.model.scriptlibrary.ScriptLibraryVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScriptLibraryVersionRepository extends JpaRepository<ScriptLibraryVersion, Long> {
    Optional<ScriptLibraryVersion> findByTemplateIdAndVersionNo(Long templateId, Integer versionNo);
}
