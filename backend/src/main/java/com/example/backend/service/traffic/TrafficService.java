package com.example.backend.service.traffic;

import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 트래픽 로그 저장 및 PC 상태 갱신 서비스.
 *
 * <p>에이전트 heartbeat 수신 시 PC 마스터 정보를 갱신하고 트래픽 로그를 저장한다.
 * 트래픽 임계값 기준은 {@code app.traffic.alert-threshold-mbps} 설정을 따른다.
 *
 * <p><b>이상 감지 알림 분리</b>: 트래픽 임계치 초과 알림은 {@code SchedulerService.scanForTrafficAnomalies()}에서
 * 주기적으로 일괄 처리한다. 이 서비스에서 직접 AlertEvent를 발행하지 않는 이유는
 * heartbeat마다 알림이 발생하는 중복 알림을 방지하기 위함이다.
 */
@Service
@RequiredArgsConstructor
public class TrafficService {

    private final PcMasterRepository pcMasterRepository;
    private final TrafficLogsRepository trafficLogRepository;

    /**
     * 에이전트 heartbeat를 처리한다.
     *
     * <p>PC 마스터 레코드를 upsert하고 트래픽 로그를 저장한다.
     * PC가 최초 등록인 경우 새 레코드를 생성하고, 기존 PC는 상태와 마지막 접속 시각을 갱신한다.
     *
     * @param mac     에이전트 MAC 주소 (PcMaster 기본 키)
     * @param pcName  PC 이름
     * @param ip      현재 IP 주소
     * @param traffic 트래픽 데이터 맵 ({@code "in"}: 인바운드 Kbps, {@code "out"}: 아웃바운드 Kbps)
     */
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
