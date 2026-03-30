package com.example.backend.service.audit;

import com.example.backend.model.audit.AuditLog;
import com.example.backend.repository.audit.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ConcurrentLinkedQueue<AuditLog> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    @Value("${app.audit.queue-max-size:20000}")
    private int queueMaxSize;

    @Value("${app.audit.flush-batch-size:200}")
    private int flushBatchSize;

    public void log(String actor, String action, String targetType, String targetId,
                    String detailJson, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setActor(actor != null ? actor : "system");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetailJson(detailJson);
        entry.setIpAddress(ipAddress);
        entry.setCreatedAt(LocalDateTime.now());

        int current = queueSize.incrementAndGet();
        if (current > Math.max(1, queueMaxSize)) {
            queueSize.decrementAndGet();
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.warn("Audit queue full; dropped={} queueSize={} queueMaxSize={}",
                        dropped, queueSize.get(), queueMaxSize);
            }
            return;
        }
        queue.offer(entry);
    }

    public void log(String actor, String action) {
        log(actor, action, null, null, null, null);
    }

    public void log(String actor, String action, String targetType, String targetId) {
        log(actor, action, targetType, targetId, null, null);
    }

    @Scheduled(fixedDelayString = "${app.audit.flush-interval-ms:2000}")
    @Transactional
    public void flushQueue() {
        flushQueueBatch();
    }

    @PreDestroy
    public void flushOnShutdown() {
        int guard = 0;
        while (queueSize.get() > 0 && guard < 1000) {
            guard++;
            flushQueueBatch();
        }
    }

    private void flushQueueBatch() {
        int batchSize = Math.max(1, flushBatchSize);
        List<AuditLog> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            AuditLog entry = queue.poll();
            if (entry == null) {
                break;
            }
            queueSize.decrementAndGet();
            batch.add(entry);
        }
        if (batch.isEmpty()) {
            return;
        }
        auditLogRepository.saveAll(batch);
    }
}
