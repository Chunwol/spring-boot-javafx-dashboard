package com.example.backend.repository.scriptlibrary;

import com.example.backend.model.scriptlibrary.ScriptLibraryExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptLibraryExecutionRepository extends JpaRepository<ScriptLibraryExecution, Long> {
    List<ScriptLibraryExecution> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
