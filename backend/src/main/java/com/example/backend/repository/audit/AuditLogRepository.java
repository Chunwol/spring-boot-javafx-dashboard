package com.example.backend.repository.audit;

import com.example.backend.model.audit.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
    List<AuditLog> findByActorOrderByCreatedAtDesc(String actor);
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
