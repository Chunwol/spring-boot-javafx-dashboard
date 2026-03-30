package com.example.backend.service.traffic;

import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 트래픽 로그 저장 및 이상 감지 서비스.
 * 임계값은 application.yml의 app.traffic.alert-threshold-mbps 설정을 따른다.
 * 트래픽 알림은 SchedulerService.scanForTrafficAnomalies()에서 일괄 처리하므로
 * 여기서는 AlertEvent를 발행하지 않는다 (중복 알림 방지).
 */
@Service
public class TrafficService {

    private final PcMasterRepository pcMasterRepository;
    private final TrafficLogsRepository trafficLogRepository;

    @Autowired
    public TrafficService(PcMasterRepository pcMasterRepository,
                          TrafficLogsRepository trafficLogRepository) {
        this.pcMasterRepository = pcMasterRepository;
        this.trafficLogRepository = trafficLogRepository;
    }

    @Transactional
    public void handleHeartbeat(String mac, String pcName, String ip, Map<String, Long> traffic) {
        PcMaster pc = pcMasterRepository.findById(mac).orElseGet(() -> {
            PcMaster p = new PcMaster();
            p.setMacAddress(mac);
            return p;
        });
        pc.setPcName(pcName);
        pc.setIpAddress(ip);
        pc.setLastSeen(LocalDateTime.now());
        pc.setStatus("ONLINE");
        pcMasterRepository.save(pc);

        // Save traffic log
        TrafficLog log = new TrafficLog();
        log.setMacAddress(mac);
        if (traffic != null) {
            Long inKb = traffic.getOrDefault("in", 0L);
            Long outKb = traffic.getOrDefault("out", 0L);
            log.setInboundKbps(inKb);
            log.setOutboundKbps(outKb);
        }
        log.setTimestamp(LocalDateTime.now());
        trafficLogRepository.save(log);
        // 트래픽 임계치 알림은 SchedulerService가 주기적으로 처리 (중복 알림 방지)
    }
}
