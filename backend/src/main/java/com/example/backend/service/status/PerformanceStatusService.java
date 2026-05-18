package com.example.backend.service.status;

import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시스템 성능 상태 종합 스냅샷 서비스.
 *
 * <p>담당 기능:
 * <ul>
 *   <li>PC 온라인/오프라인 현황</li>
 *   <li>명령 큐 상태 (QUEUED/DELIVERED/ACKED/COMPLETED/FAILED) 및 stale 명령 감지</li>
 *   <li>에이전트 인증 토큰 현황</li>
 *   <li>캐시 히트율 (Caffeine 통계)</li>
 *   <li>운영 메트릭 (레이턴시·전송·요청 오류율) 및 압력 레벨(LOW/MEDIUM/HIGH) 계산</li>
 * </ul>
 *
 * <p>압력 레벨 판정 임계치:
 * <ul>
 *   <li>큐 압력: 80 이상 MEDIUM, 200 이상 HIGH</li>
 *   <li>Stale 압력: 10 이상 MEDIUM, 30 이상 HIGH</li>
 *   <li>명령/전송 실패율: 10% 이상 MEDIUM, 25% 이상 HIGH</li>
 *   <li>HTTP 오류율: 5% 이상 MEDIUM, 15% 이상 HIGH</li>
 * </ul>
 */
@Service
public class PerformanceStatusService {

    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    private final CacheManager cacheManager;
    private final OperationalMetricsService operationalMetricsService;

    @Value("${app.command.timeout-seconds:180}")
    private long commandTimeoutSeconds;

    @Value("${app.cache.provider:redis}")
    private String cacheProvider;

    public PerformanceStatusService(PcMasterRepository pcMasterRepository,
                                    CommandQueueRepository commandQueueRepository,
                                    AgentAuthTokenRepository agentAuthTokenRepository,
                                    CacheManager cacheManager,
                                    OperationalMetricsService operationalMetricsService) {
        this.pcMasterRepository = pcMasterRepository;
        this.commandQueueRepository = commandQueueRepository;
        this.agentAuthTokenRepository = agentAuthTokenRepository;
        this.cacheManager = cacheManager;
        this.operationalMetricsService = operationalMetricsService;
    }

    /**
     * 시스템 성능 상태 전체 스냅샷을 수집하여 반환한다.
     *
     * <p>PC 현황, 명령 큐, 토큰, 캐시, 운영 메트릭을 조합하고
     * 각 영역별 압력 레벨(LOW/MEDIUM/HIGH)을 계산하여 포함한다.
     *
     * @return timestamp, pc, command, token, cache, operational, pressure 섹션을 포함한 맵
     */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Map<String, Object> resp = new LinkedHashMap<>();

        long totalPc = pcMasterRepository.count();
        long onlinePc = pcMasterRepository.countByStatus("ONLINE");
        long offlinePc = pcMasterRepository.countByStatus("OFFLINE");

        long queued = commandQueueRepository.countByStatus("QUEUED");
        long delivered = commandQueueRepository.countByStatus("DELIVERED");
        long acked = commandQueueRepository.countByStatus("ACKED");
        long completed = commandQueueRepository.countByStatus("COMPLETED");
        long failed = commandQueueRepository.countByStatus("FAILED");

        LocalDateTime threshold = LocalDateTime.now().minusSeconds(commandTimeoutSeconds);
        long staleDelivered = commandQueueRepository.countByStatusAndDeliveredAtBefore("DELIVERED", threshold);
        long staleAcked = commandQueueRepository.countByStatusAndAckedAtBefore("ACKED", threshold);

        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("total", totalPc);
        pc.put("online", onlinePc);
        pc.put("offline", offlinePc);
        pc.put("onlineRatio", totalPc == 0 ? 0.0 : (onlinePc * 100.0 / totalPc));

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("queued", queued);
        command.put("delivered", delivered);
        command.put("acked", acked);
        command.put("completed", completed);
        command.put("failed", failed);
        command.put("inFlight", commandQueueRepository.countByStatusIn(List.of("QUEUED", "DELIVERED", "ACKED")));
        command.put("staleDelivered", staleDelivered);
        command.put("staleAcked", staleAcked);

        Map<String, Object> token = new LinkedHashMap<>();
        token.put("active", agentAuthTokenRepository.countByRevoked(false));
        token.put("revoked", agentAuthTokenRepository.countByRevoked(true));

        Map<String, Object> cache = cacheStats();
        Map<String, Object> operational = operationalMetricsService.snapshot();
        Map<String, Object> opLatency = toMap(operational.get("latency"));
        Map<String, Object> opTransfer = toMap(operational.get("transfer"));
        Map<String, Object> opRequest = toMap(operational.get("request"));

        Map<String, Object> pressure = new LinkedHashMap<>();
        pressure.put("queuePressure", queued > 200 ? "HIGH" : queued > 80 ? "MEDIUM" : "LOW");
        pressure.put("stalePressure", (staleDelivered + staleAcked) > 30 ? "HIGH" : (staleDelivered + staleAcked) > 10 ? "MEDIUM" : "LOW");
        pressure.put("commandFailurePressure", toDouble(opLatency.get("failureRate")) > 0.25 ? "HIGH"
                : toDouble(opLatency.get("failureRate")) > 0.10 ? "MEDIUM" : "LOW");
        pressure.put("transferFailurePressure", toDouble(opTransfer.get("failureRate")) > 0.25 ? "HIGH"
                : toDouble(opTransfer.get("failureRate")) > 0.10 ? "MEDIUM" : "LOW");
        pressure.put("requestErrorPressure", toDouble(opRequest.get("errorRate")) > 0.15 ? "HIGH"
                : toDouble(opRequest.get("errorRate")) > 0.05 ? "MEDIUM" : "LOW");

        resp.put("timestamp", LocalDateTime.now());
        resp.put("pc", pc);
        resp.put("command", command);
        resp.put("token", token);
        resp.put("cache", cache);
        resp.put("operational", operational);
        resp.put("pressure", pressure);
        return resp;
    }

    /**
     * 캐시 통계를 수집한다.
     *
     * <p>Caffeine 캐시인 경우 히트율·히트수·미스수·축출 수를 반환한다.
     * 다른 캐시 프로바이더(Redis 등)는 provider-specific 통계를 지원하지 않아 메시지만 반환한다.
     *
     * <p><b>WHY</b>: {@code ipRangeCache}는 IP 범위 조회 성능에 직접 영향을 주므로
     * 히트율을 모니터링하여 캐시 유효성을 확인할 수 있어야 한다.
     *
     * @return 캐시 통계 맵
     */
    private Map<String, Object> cacheStats() {
        Map<String, Object> cache = new LinkedHashMap<>();
        Cache springCache = cacheManager.getCache("ipRangeCache");
        cache.put("name", "ipRangeCache");
        cache.put("provider", cacheProvider);
        cache.put("cacheManager", cacheManager.getClass().getSimpleName());

        if (springCache == null) {
            cache.put("enabled", false);
            cache.put("message", "Cache not initialized");
            return cache;
        }

        if (springCache instanceof CaffeineCache caffeineCache) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
            var stats = nativeCache.stats();
            cache.put("enabled", true);
            cache.put("provider", "caffeine");
            cache.put("estimatedSize", nativeCache.estimatedSize());
            cache.put("hitRate", stats.hitRate());
            cache.put("hitCount", stats.hitCount());
            cache.put("missCount", stats.missCount());
            cache.put("evictionCount", stats.evictionCount());
            return cache;
        }

        cache.put("enabled", true);
        cache.put("message", "Provider-specific cache stats are unavailable");
        return cache;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }
}
