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

/**
 * 비동기 감사 로그(Audit Log) 기록 서비스.
 * <p>
 * 모든 감사 항목은 인메모리 큐에 먼저 적재되고, 주기적 flush(기본 2초)를 통해 DB에 배치 저장된다.
 * 큐가 queueMaxSize를 초과하면 신규 항목을 드롭하고 경고 로그를 남긴다.
 * 애플리케이션 종료 시 @PreDestroy로 잔여 항목을 모두 소진한다.
 * </p>
 */
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

    /**
     * 감사 로그를 큐에 추가한다. DB 저장은 비동기 flush로 처리된다.
     *
     * @param actor      작업 수행자 (사용자명, 에이전트 MAC 등)
     * @param action     수행된 작업 유형
     * @param targetType 대상 엔티티 타입 (nullable)
     * @param targetId   대상 엔티티 ID (nullable)
     * @param detailJson 상세 정보 JSON (nullable)
     * @param ipAddress  요청 IP 주소 (nullable)
     */
    public void log(String actor, String action, String targetType, String targetId,
                    String detailJson, String ipAddress) {
        enqueue(actor, action, targetType, targetId, detailJson, ipAddress, null);
    }

    /** 사용자 인증 감사 로그에 접속 환경을 함께 기록한다. */
    public void logAccess(String actor, String action, String targetType, String targetId,
                          String detailJson, String ipAddress, String userAgent) {
        enqueue(actor, action, targetType, targetId, detailJson, ipAddress, userAgent);
    }

    private void enqueue(String actor, String action, String targetType, String targetId,
                         String detailJson, String ipAddress, String userAgent) {
        AuditLog entry = new AuditLog();
        entry.setActor(actor != null ? actor : "system");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetailJson(detailJson);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setCreatedAt(LocalDateTime.now());

        int current = queueSize.incrementAndGet();
        if (current > Math.max(1, queueMaxSize)) {
            queueSize.decrementAndGet();
            long dropped = droppedCount.incrementAndGet();
            // 드롭 발생 첫번째와 100건 단위로만 경고 (로그 홍수 방지)
            if (dropped == 1 || dropped % 100 == 0) {
                log.warn("Audit queue full; dropped={} queueSize={} queueMaxSize={}",
                        dropped, queueSize.get(), queueMaxSize);
            }
            return;
        }
        queue.offer(entry);
    }

    /** 작업·수행자만 기록하는 단축 오버로드. */
    public void log(String actor, String action) {
        log(actor, action, null, null, null, null);
    }

    /** 대상 엔티티 정보까지 기록하는 단축 오버로드. */
    public void log(String actor, String action, String targetType, String targetId) {
        log(actor, action, targetType, targetId, null, null);
    }

    /**
     * 주기적으로 큐를 DB에 배치 저장한다.
     * @Transactional은 실제 DB 저장(saveAll) 범위에만 적용된다.
     */
    @Scheduled(fixedDelayString = "${app.audit.flush-interval-ms:2000}")
    @Transactional
    public void flushQueue() {
        flushQueueBatch();
    }

    /** 애플리케이션 종료 시 잔여 큐 항목을 모두 소진한다. */
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
