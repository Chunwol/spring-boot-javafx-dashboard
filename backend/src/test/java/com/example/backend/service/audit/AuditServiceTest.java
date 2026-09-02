package com.example.backend.service.audit;

import com.example.backend.model.audit.AuditLog;
import com.example.backend.repository.audit.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository);
        ReflectionTestUtils.setField(auditService, "queueMaxSize", 100);
        ReflectionTestUtils.setField(auditService, "flushBatchSize", 10);
    }

    @Test
    void log_addsEntryToQueue_andFlushSaves() {
        auditService.log("admin", "LOGIN", "USER", "admin", null, "127.0.0.1");

        auditService.flushQueue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        List<AuditLog> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getActor()).isEqualTo("admin");
        assertThat(saved.get(0).getAction()).isEqualTo("LOGIN");
    }

    @Test
    void log_multipleEntries_flushedInBatch() {
        for (int i = 0; i < 5; i++) {
            auditService.log("user" + i, "ACTION_" + i);
        }

        auditService.flushQueue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }

    @Test
    void accessLogPersistsBrowserEnvironment() {
        auditService.logAccess("admin", "LOGIN_SUCCESS", "USER", "admin", null,
                "192.0.2.20", "test-browser");

        auditService.flushQueue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getUserAgent()).isEqualTo("test-browser");
    }

    @Test
    void flush_noEntries_doesNotCallSave() {
        auditService.flushQueue();

        verify(auditLogRepository, never()).saveAll(anyList());
    }

    @Test
    void log_nullActor_defaultsToSystem() {
        auditService.log(null, "SOME_ACTION");
        auditService.flushQueue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getActor()).isEqualTo("system");
    }

    @Test
    void log_queueFull_dropsEntry() {
        ReflectionTestUtils.setField(auditService, "queueMaxSize", 2);
        auditService.log("a", "A1");
        auditService.log("a", "A2");
        auditService.log("a", "A3"); // should be dropped

        auditService.flushQueue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSizeLessThanOrEqualTo(2);
    }
}
