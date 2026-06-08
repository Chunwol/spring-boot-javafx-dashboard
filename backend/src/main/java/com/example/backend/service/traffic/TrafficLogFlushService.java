package com.example.backend.service.traffic;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 트래픽 로그 집계·저장 서비스.
 *
 * <h2>데이터 흐름</h2>
 * <pre>
 *  Agent (12초마다) ──→ Heartbeat ──→ enqueue(TrafficLog)
 *                                       │
 *                                       ├─→ Redis: live traffic (TTL 5min, 대시보드용)
 *                                       └─→ 인메모리 집계 버퍼 (mac별)
 *                                                │
 *                                                └─→ 5분마다 aggregate → DB (1 row/agent/5min)
 * </pre>
 *
 * <h2>DB 저장량 감소</h2>
 * <ul>
 *   <li>이전: 800대 × (60/12) × 60 × 24 = 8,640,000 rows/day</li>
 *   <li>현재: 800대 × (60/5)  × 24       =   230,400 rows/day  (약 37배 감소)</li>
 * </ul>
 *
 * <h2>대시보드 실시간 조회</h2>
 * <p>최신 5분 트래픽은 Redis 키 {@code traffic:live:{mac}} 에서 즉시 조회 (DB 부담 없음).
 * 5분 이상 과거는 DB의 5분 집계 행에서 조회.</p>
 */
@Service
public class TrafficLogFlushService {

    private static final Logger log = LoggerFactory.getLogger(TrafficLogFlushService.class);
    private static final String REDIS_LIVE_KEY = "traffic:live:";
    private static final Duration LIVE_TTL = Duration.ofMinutes(5);

    private final TrafficLogsRepository trafficLogsRepository;
    private final StringRedisTemplate redis;

    /** mac → 누적 합계 (집계 윈도우 내). aggregate()가 5분마다 비움. */
    private final Map<String, AggregateBucket> buckets = new ConcurrentHashMap<>();

    @Autowired(required = false) // Redis가 없는 환경(테스트)에서도 동작
    public TrafficLogFlushService(TrafficLogsRepository trafficLogsRepository,
                                   StringRedisTemplate redis) {
        this.trafficLogsRepository = trafficLogsRepository;
        this.redis = redis;
    }

    /** 단건 enqueue: Redis 라이브 갱신 + 집계 버킷 누적. */
    public void enqueue(TrafficLog t) {
        if (t == null || t.getMacAddress() == null) return;
        updateLive(t);
        accumulate(t);
    }

    /** 다중 샘플 enqueue. */
    public void enqueueAll(List<TrafficLog> logs) {
        if (logs == null || logs.isEmpty()) return;
        for (TrafficLog t : logs) enqueue(t);
    }

    /** 5분마다 집계 결과를 DB에 1 row/agent로 저장. */
    @Scheduled(fixedDelayString = "${app.traffic.aggregate-interval-ms:300000}",
               initialDelayString = "${app.traffic.aggregate-initial-delay-ms:60000}")
    @Transactional
    public void aggregate() {
        if (buckets.isEmpty()) return;
        // 키 스냅샷 후 키별 원자적 remove — clear()와 달리,
        // remove 시점까지 누적된 값을 정확히 가져오고 이후 들어온 샘플은
        // 다음 윈도우로 안전하게 이월된다 (TOCTOU 유실 방지).
        List<String> macs = new ArrayList<>(buckets.keySet());
        List<TrafficLog> aggregated = new ArrayList<>(macs.size());
        LocalDateTime now = LocalDateTime.now();
        for (String mac : macs) {
            AggregateBucket b = buckets.remove(mac);
            if (b == null || b.sampleCount <= 0) continue;
            TrafficLog row = new TrafficLog();
            row.setMacAddress(mac);
            row.setInboundKbps(b.inboundSum / b.sampleCount);
            row.setOutboundKbps(b.outboundSum / b.sampleCount);
            row.setTimestamp(now);
            aggregated.add(row);
        }
        if (aggregated.isEmpty()) return;
        try {
            trafficLogsRepository.saveAll(aggregated);
            log.debug("트래픽 5분 집계 저장: {}개 agent", aggregated.size());
        } catch (Exception ex) {
            log.error("트래픽 집계 저장 실패 ({}개 유실): {}", aggregated.size(), ex.getMessage());
        }
    }

    /** 현재 집계 중인 agent 수 (모니터링). */
    public int pendingCount() {
        return buckets.size();
    }

    private void updateLive(TrafficLog t) {
        if (redis == null) return;
        try {
            String key = REDIS_LIVE_KEY + t.getMacAddress();
            String value = (t.getInboundKbps() != null ? t.getInboundKbps() : 0L)
                    + "," + (t.getOutboundKbps() != null ? t.getOutboundKbps() : 0L);
            redis.opsForValue().set(key, value, LIVE_TTL);
        } catch (Exception ex) {
            log.debug("Redis 라이브 트래픽 기록 실패 (무시): {}", ex.getMessage());
        }
    }

    private void accumulate(TrafficLog t) {
        buckets.compute(t.getMacAddress(), (k, prev) -> {
            AggregateBucket b = prev == null ? new AggregateBucket() : prev;
            b.inboundSum += t.getInboundKbps() != null ? t.getInboundKbps() : 0L;
            b.outboundSum += t.getOutboundKbps() != null ? t.getOutboundKbps() : 0L;
            b.sampleCount++;
            return b;
        });
    }

    /** 단일 agent의 5분 합/카운트. */
    private static class AggregateBucket {
        long inboundSum;
        long outboundSum;
        int sampleCount;
    }
}
