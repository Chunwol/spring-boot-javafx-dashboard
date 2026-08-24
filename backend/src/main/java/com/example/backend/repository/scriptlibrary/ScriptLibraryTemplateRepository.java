package com.example.backend.repository.scriptlibrary;

import com.example.backend.model.scriptlibrary.ScriptLibraryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScriptLibraryTemplateRepository extends JpaRepository<ScriptLibraryTemplate, Long> {
    List<ScriptLibraryTemplate> findByActiveTrueOrderByBuiltInKeyDescNameAsc();
    Optional<ScriptLibraryTemplate> findByBuiltInKey(String builtInKey);
}
