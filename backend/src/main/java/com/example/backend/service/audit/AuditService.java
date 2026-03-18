package com.example.backend.service.audit;

import com.example.backend.model.audit.AuditLog;
import com.example.backend.repository.audit.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(String actor, String action, String targetType, String targetId,
                    String detailJson, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setActor(actor != null ? actor : "system");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetailJson(detailJson);
        entry.setIpAddress(ipAddress);
        auditLogRepository.save(entry);
    }

    @Async
    public void log(String actor, String action) {
        log(actor, action, null, null, null, null);
    }

    @Async
    public void log(String actor, String action, String targetType, String targetId) {
        log(actor, action, targetType, targetId, null, null);
    }
}
