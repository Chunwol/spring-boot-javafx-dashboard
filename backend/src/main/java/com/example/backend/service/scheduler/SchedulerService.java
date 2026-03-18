package com.example.backend.service.scheduler;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주기적 스캐너:
 *  - 1분마다: 최근 5분 트래픽 이상(임계치 초과) PC 감지 → AlertEvent 발행
 *  - 5분마다: ONLINE 상태인데 last_seen 이 timeout 초과된 PC → OFFLINE 전환 + AlertEvent
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TrafficLogsRepository trafficRepo;
    private final PcMasterRepository pcRepo;
    private final ApplicationEventPublisher eventPublisher;

    /** application.yml: app.traffic.alert-threshold-mbps (기본 100) */
    @Value("${app.traffic.alert-threshold-mbps:100}")
    private long alertThresholdMbps;

    /** application.yml: app.heartbeat.timeout-minutes (기본 5) */
    @Value("${app.heartbeat.timeout-minutes:5}")
    private long heartbeatTimeoutMinutes;

    /** 중복 알림 방지: MAC → 마지막 알림 시각 */
    private final Map<String, LocalDateTime> lastAlertTime = new ConcurrentHashMap<>();

    public SchedulerService(TrafficLogsRepository trafficRepo,
                            PcMasterRepository pcRepo,
                            ApplicationEventPublisher eventPublisher) {
        this.trafficRepo = trafficRepo;
        this.pcRepo = pcRepo;
        this.eventPublisher = eventPublisher;
    }

    // ─── 트래픽 이상 감지 (1분 주기) ────────────────────────────────────
    @Scheduled(fixedRateString = "${scheduler.fixed-rate:60000}",
               initialDelayString = "${scheduler.initial-delay:5000}")
    public void scanForTrafficAnomalies() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        long thresholdKbps = alertThresholdMbps * 1000L; // Mbps → Kbps

        List<TrafficLog> recentLogs = trafficRepo.findByTimestampBetween(since, LocalDateTime.now());
        for (TrafficLog tlog : recentLogs) {
            long inKbps = tlog.getInboundKbps() != null ? tlog.getInboundKbps() : 0L;
            if (inKbps > thresholdKbps) {
                String mac = tlog.getMacAddress();
                if (isAlertCooldownPassed(mac)) {
                    String msg = String.format(
                            "[트래픽 경고] MAC=%s 인바운드 %.1f Mbps (임계치: %d Mbps) at %s",
                            mac, inKbps / 1000.0, alertThresholdMbps, tlog.getTimestamp());
                    log.warn(msg);
                    eventPublisher.publishEvent(new AlertEvent(this, mac, msg));
                    lastAlertTime.put(mac, LocalDateTime.now());
                }
            }
        }
    }

    // ─── 오프라인 감지 (5분 주기) ────────────────────────────────────────
    @Scheduled(fixedRate = 300_000, initialDelay = 30_000)
    public void scanForOfflinePcs() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(heartbeatTimeoutMinutes);
        List<PcMaster> onlinePcs = pcRepo.findAll().stream()
                .filter(p -> "ONLINE".equalsIgnoreCase(p.getStatus()))
                .toList();

        for (PcMaster pc : onlinePcs) {
            if (pc.getLastSeen() != null && pc.getLastSeen().isBefore(threshold)) {
                pc.setStatus("OFFLINE");
                pcRepo.save(pc);
                log.info("[OFFLINE 감지] {} ({}) - 마지막 하트비트: {}",
                        pc.getPcName(), pc.getMacAddress(), pc.getLastSeen());
                if (isAlertCooldownPassed(pc.getMacAddress())) {
                    String msg = String.format(
                            "[PC 오프라인] %s (%s) - 마지막 접속: %s",
                            pc.getPcName(), pc.getMacAddress(), pc.getLastSeen());
                    eventPublisher.publishEvent(new AlertEvent(this, pc.getMacAddress(), msg));
                    lastAlertTime.put(pc.getMacAddress(), LocalDateTime.now());
                }
            }
        }
    }

    /** 동일 MAC에 대해 10분 이내 중복 알림 방지 */
    private boolean isAlertCooldownPassed(String mac) {
        LocalDateTime last = lastAlertTime.get(mac);
        return last == null || last.isBefore(LocalDateTime.now().minusMinutes(10));
    }
}
