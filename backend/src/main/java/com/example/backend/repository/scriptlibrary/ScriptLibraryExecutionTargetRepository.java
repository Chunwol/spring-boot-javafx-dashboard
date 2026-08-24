package com.example.backend.repository.scriptlibrary;

import com.example.backend.model.scriptlibrary.ScriptLibraryExecutionTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptLibraryExecutionTargetRepository extends JpaRepository<ScriptLibraryExecutionTarget, Long> {
    List<ScriptLibraryExecutionTarget> findByExecutionIdOrderByMacAddressAsc(Long executionId);
}
