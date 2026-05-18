package com.example.backend.service.traffic;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 트래픽 로그 비동기 배치 저장 서비스.
 *
 * <h2>도입 배경</h2>
 * <p>850대 에이전트가 30초 주기로 하트비트를 전송하면 트래픽 로그가
 * {@code heartbeat 트랜잭션 내에서 동기적으로} 저장된다.
 * 평균 26.7 writes/sec, 부팅 버스트 시 순간 수백 writes/sec이 발생하여
 * DB 커넥션 풀을 점유하고 하트비트 응답 지연을 유발한다.</p>
 *
 * <h2>해결 방식</h2>
 * <ol>
 *   <li>하트비트 처리 중 트래픽 로그를 {@link ConcurrentLinkedQueue} 메모리 버퍼에 적재
 *       → 하트비트 트랜잭션에서 DB 쓰기 완전 제거</li>
 *   <li>{@link #flush()} 가 5초마다 배치로 {@code saveAll()} 호출
 *       → 26.7 writes/sec → 최대 1 batch/5s</li>
 * </ol>
 *
 * <h2>데이터 손실 허용 범위</h2>
 * <p>서버 비정상 종료 시 버퍼의 미처리 로그(최대 5초치)는 유실될 수 있다.
 * 트래픽 로그는 모니터링/통계 목적의 소프트 데이터이므로 허용 가능한 범위다.</p>
 *
 * <h2>배압(Backpressure) 처리</h2>
 * <p>버퍼가 {@link #MAX_BUFFER_SIZE}를 초과하면 신규 로그를 무시한다.
 * flush가 정상 동작하는 한 이 상황은 발생하지 않는다.
 * (850대 × 최대 10 samples = 8,500개 → flush 주기 5초 기준 충분)</p>
 */
@Service
public class TrafficLogFlushService {

    private static final Logger log = LoggerFactory.getLogger(TrafficLogFlushService.class);

    /** 850대 × 최대 샘플 수(10) × 여유(2배) */
    private static final int MAX_BUFFER_SIZE = 20_000;

    /** 1회 flush당 최대 처리 건수 (Hibernate batch_size=50에 맞춰 배수로 설정) */
    private static final int FLUSH_BATCH_LIMIT = 5_000;

    private final TrafficLogsRepository trafficLogsRepository;

    /** 멀티스레드 안전 비차단(non-blocking) 큐 */
    private final ConcurrentLinkedQueue<TrafficLog> buffer = new ConcurrentLinkedQueue<>();

    public TrafficLogFlushService(TrafficLogsRepository trafficLogsRepository) {
        this.trafficLogsRepository = trafficLogsRepository;
    }

    /**
     * 트래픽 로그 단건을 버퍼에 적재한다.
     *
     * <p>버퍼가 가득 찬 경우 해당 로그는 무시된다(배압 처리).
     * 하트비트 처리 스레드에서 호출되므로 블로킹 없이 즉시 반환해야 한다.</p>
     *
     * @param trafficLog 저장할 트래픽 로그 (null 무시)
     */
    public void enqueue(TrafficLog trafficLog) {
        if (trafficLog == null) return;
        if (buffer.size() < MAX_BUFFER_SIZE) {
            buffer.offer(trafficLog);
        } else {
            log.warn("TrafficLog 버퍼 가득 참 ({}개) — 로그 1건 무시", MAX_BUFFER_SIZE);
        }
    }

    /**
     * 트래픽 로그 목록을 버퍼에 일괄 적재한다.
     *
     * <p>다중 샘플(trafficSamples) 하트비트에서 호출된다.
     * 버퍼 여유 공간만큼만 적재하고 초과분은 무시한다.</p>
     *
     * @param logs 저장할 트래픽 로그 목록
     */
    public void enqueueAll(List<TrafficLog> logs) {
        if (logs == null || logs.isEmpty()) return;
        int available = MAX_BUFFER_SIZE - buffer.size();
        if (available <= 0) {
            log.warn("TrafficLog 버퍼 가득 참 — {} 건 무시", logs.size());
            return;
        }
        int toAdd = Math.min(logs.size(), available);
        if (toAdd < logs.size()) {
            log.warn("TrafficLog 버퍼 여유 부족 — {} 건 중 {} 건만 적재", logs.size(), toAdd);
        }
        logs.stream().limit(toAdd).forEach(buffer::offer);
    }

    /**
     * 버퍼에 쌓인 트래픽 로그를 5초마다 배치로 DB에 저장한다.
     *
     * <p>Hibernate {@code jdbc.batch_size=50} 설정과 연동되어
     * 단일 {@code saveAll()} 호출이 자동으로 배치 INSERT로 변환된다.
     * 버퍼가 비어 있으면 즉시 반환하여 불필요한 트랜잭션을 열지 않는다.</p>
     */
    @Scheduled(fixedDelayString = "${app.traffic.flush-interval-ms:5000}",
               initialDelayString = "${app.traffic.flush-initial-delay-ms:10000}")
    @Transactional
    public void flush() {
        if (buffer.isEmpty()) return;

        List<TrafficLog> batch = new ArrayList<>(Math.min(buffer.size(), FLUSH_BATCH_LIMIT));
        TrafficLog entry;
        while ((entry = buffer.poll()) != null && batch.size() < FLUSH_BATCH_LIMIT) {
            batch.add(entry);
        }

        if (batch.isEmpty()) return;

        try {
            trafficLogsRepository.saveAll(batch);
            log.debug("TrafficLog 배치 저장 완료: {}건", batch.size());
        } catch (Exception e) {
            // 소프트 데이터 — 저장 실패 시 해당 배치 유실 허용 (재시도 없음)
            log.error("TrafficLog 배치 저장 실패 ({}건 유실): {}", batch.size(), e.getMessage());
        }
    }

    /** 현재 버퍼에 대기 중인 로그 건수 (모니터링용) */
    public int pendingCount() {
        return buffer.size();
    }
}
