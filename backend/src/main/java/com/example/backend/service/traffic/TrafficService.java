package com.example.backend.service.traffic;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class TrafficService {

    private final PcMasterRepository pcMasterRepository;
    private final TrafficLogRepository trafficLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    // threshold in KBps (convert from Mbps); default detection: 500 Mbps -> 500 * 1024 KBps ~ 512000 KBps
    private final long thresholdKb = 500L * 1024L;

    @Autowired
    public TrafficService(PcMasterRepository pcMasterRepository,
                          TrafficLogRepository trafficLogRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.pcMasterRepository = pcMasterRepository;
        this.trafficLogRepository = trafficLogRepository;
        this.eventPublisher = eventPublisher;
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

            // check threshold using inbound Kb
            if (inKb != null && inKb > thresholdKb) {
                String msg = String.format("High inbound traffic detected: %d KB/s > %d KB/s", inKb, thresholdKb);
                eventPublisher.publishEvent(new AlertEvent(this, mac, msg));
            }
        }
        log.setTimestamp(LocalDateTime.now());
        trafficLogRepository.save(log);
    }
}
