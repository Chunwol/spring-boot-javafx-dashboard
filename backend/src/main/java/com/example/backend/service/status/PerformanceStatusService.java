package com.example.backend.service.status;

import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceStatusService {

    private final PcMasterRepository pcMasterRepository;
    private final CommandQueueRepository commandQueueRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    private final CacheManager cacheManager;

    @Value("${app.command.timeout-seconds:180}")
    private long commandTimeoutSeconds;

    public PerformanceStatusService(PcMasterRepository pcMasterRepository,
                                    CommandQueueRepository commandQueueRepository,
                                    AgentAuthTokenRepository agentAuthTokenRepository,
                                    CacheManager cacheManager) {
        this.pcMasterRepository = pcMasterRepository;
        this.commandQueueRepository = commandQueueRepository;
        this.agentAuthTokenRepository = agentAuthTokenRepository;
        this.cacheManager = cacheManager;
    }

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

        Map<String, Object> pressure = new LinkedHashMap<>();
        pressure.put("queuePressure", queued > 200 ? "HIGH" : queued > 80 ? "MEDIUM" : "LOW");
        pressure.put("stalePressure", (staleDelivered + staleAcked) > 30 ? "HIGH" : (staleDelivered + staleAcked) > 10 ? "MEDIUM" : "LOW");

        resp.put("timestamp", LocalDateTime.now());
        resp.put("pc", pc);
        resp.put("command", command);
        resp.put("token", token);
        resp.put("cache", cache);
        resp.put("pressure", pressure);
        return resp;
    }

    private Map<String, Object> cacheStats() {
        Map<String, Object> cache = new LinkedHashMap<>();
        Cache springCache = cacheManager.getCache("ipRangeCache");
        if (springCache instanceof CaffeineCache caffeineCache) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
            var stats = nativeCache.stats();
            cache.put("name", "ipRangeCache");
            cache.put("estimatedSize", nativeCache.estimatedSize());
            cache.put("hitRate", stats.hitRate());
            cache.put("hitCount", stats.hitCount());
            cache.put("missCount", stats.missCount());
            cache.put("evictionCount", stats.evictionCount());
            return cache;
        }
        cache.put("name", "ipRangeCache");
        cache.put("enabled", false);
        cache.put("message", "Caffeine cache not initialized");
        return cache;
    }
}
