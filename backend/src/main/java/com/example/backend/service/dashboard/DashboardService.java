package com.example.backend.service.dashboard;

import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.NetworkTrafficDto;
import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.traffic.TrafficLogFlushService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 대시보드 핵심 비즈니스 로직 서비스.
 *
 * <p>담당 기능:
 * <ul>
 *   <li>대시보드 전체 스냅샷 조합 ({@link #getRefreshSnapshot()}): 강의실 요약 + 트래픽 집계</li>
 *   <li>네트워크 트래픽 집계 ({@link #getNetworkTraffic()}): PC별 최신값 합산 방식</li>
 *   <li>위젯 아이템 조회: 강의실/PC 상태·IP 범위 관리는 전용 서비스에 위임</li>
 * </ul>
 *
 * <p>트래픽 룩백 윈도우는 heartbeat 인터벌에 맞게 동적으로 결정된다.
 * (고정 윈도우 사용 시 heartbeat 주기보다 짧으면 항상 0을 반환할 수 있음)
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    private static final int MIN_TRAFFIC_LOOKBACK_SECONDS = 60;

    private final DashboardWidgetItemRepository dashboardWidgetItemRepository;
    private final ClassroomService classroomService;
    private final TrafficLogsRepository trafficLogsRepository;
    private final TrafficLogFlushService trafficLogFlushService;
    private final ObjectMapper objectMapper;

    @Value("${app.dashboard.traffic-lookback-seconds:0}")
    private int dashboardTrafficLookbackSeconds;

    @Value("${app.heartbeat.interval-seconds:120}")
    private int heartbeatIntervalSeconds;

    @Value("${app.traffic.log-min-interval-seconds:30}")
    private int trafficLogMinIntervalSeconds;

    public DashboardService(DashboardWidgetItemRepository dashboardWidgetItemRepository,
                           ClassroomService classroomService,
                           TrafficLogsRepository trafficLogsRepository,
                           TrafficLogFlushService trafficLogFlushService,
                           ObjectMapper objectMapper) {
        this.dashboardWidgetItemRepository = dashboardWidgetItemRepository;
        this.classroomService = classroomService;
        this.trafficLogsRepository = trafficLogsRepository;
        this.trafficLogFlushService = trafficLogFlushService;
        this.objectMapper = objectMapper;
    }

    /**
     * 대시보드 표시용 강의실 요약 목록을 반환한다.
     * 실제 집계는 {@link ClassroomService}에 위임한다.
     *
     * @return 강의실 요약 DTO 목록
     */
    @Transactional(readOnly = true)
    public List<ClassroomSummaryDto> getClassroomSummaries() {
        return classroomService.getClassroomSummaries();
    }

    /**
     * 최근 트래픽 로그에서 전체 네트워크 트래픽을 집계하여 반환한다.
     *
     * <p><b>집계 방식</b>: 룩백 윈도우 내 PC별 최신 로그값을 합산한다.
     * 모든 샘플 평균 대신 최신값 합산을 사용하는 이유:
     * heartbeat 사이클이 다른 PC들의 오래된 0값이 포함되면 실제 부하가 과소 표시되기 때문이다.
     *
     * <p><b>룩백 윈도우</b>: {@code app.dashboard.traffic-lookback-seconds} 설정이 있으면 그 값을 사용하고,
     * 없으면 {@code heartbeat 인터벌 × 2}로 자동 결정한다.
     *
     * @return 인바운드/아웃바운드 Mbps (조회 실패 시 0.0 반환)
     */
    @Transactional(readOnly = true)
    public NetworkTrafficDto getNetworkTraffic() {
        try {
            Map<String, TrafficLogFlushService.LiveTraffic> liveTraffic =
                    trafficLogFlushService.getLiveTrafficSnapshot();
            if (liveTraffic != null && !liveTraffic.isEmpty()) {
                long inboundKbps = liveTraffic.values().stream()
                        .mapToLong(TrafficLogFlushService.LiveTraffic::inboundKbps)
                        .sum();
                long outboundKbps = liveTraffic.values().stream()
                        .mapToLong(TrafficLogFlushService.LiveTraffic::outboundKbps)
                        .sum();
                return toNetworkTrafficDto(inboundKbps, outboundKbps);
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusSeconds(resolveTrafficLookbackSeconds());
            List<TrafficLog> recentLogs = trafficLogsRepository.findByTimestampBetween(since, now);
            if (recentLogs.isEmpty()) {
                return new NetworkTrafficDto(0.0, 0.0);
            }

            // 최근 윈도우 내 "PC별 최신값"을 합산해 전체 트래픽을 계산한다.
            // (모든 샘플 평균값은 대부분 0으로 희석되어 실제 부하를 과소표시할 수 있다.)
            Map<String, TrafficLog> latestByMac = new HashMap<>();
            int _anonIdx = 0;
            for (TrafficLog log : recentLogs) {
                if (log == null) {
                    continue;
                }
                String mac = log.getMacAddress();
                String key = (mac == null || mac.isBlank()) ? ("_anon_" + (_anonIdx++)) : mac;
                TrafficLog existing = latestByMac.get(key);
                if (isNewerTrafficLog(log, existing)) {
                    latestByMac.put(key, log);
                }
            }
            if (latestByMac.isEmpty()) {
                return new NetworkTrafficDto(0.0, 0.0);
            }

            long inboundKbps = latestByMac.values().stream()
                    .map(TrafficLog::getInboundKbps)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
            long outboundKbps = latestByMac.values().stream()
                    .map(TrafficLog::getOutboundKbps)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .sum();
            return toNetworkTrafficDto(inboundKbps, outboundKbps);
        } catch (Exception e) {
            logger.warn("트래픽 데이터 조회 실패: {}", e.getMessage());
            return new NetworkTrafficDto(0.0, 0.0);
        }
    }

    private NetworkTrafficDto toNetworkTrafficDto(long inboundKbps, long outboundKbps) {
        double inboundMbps = inboundKbps / 1024.0;
        double outboundMbps = outboundKbps / 1024.0;
        return new NetworkTrafficDto(
                Math.round(inboundMbps * 100.0) / 100.0,
                Math.round(outboundMbps * 100.0) / 100.0);
    }

    private boolean isNewerTrafficLog(TrafficLog candidate, TrafficLog current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        LocalDateTime candidateTs = candidate.getTimestamp();
        LocalDateTime currentTs = current.getTimestamp();
        if (candidateTs == null) {
            return false;
        }
        if (currentTs == null) {
            return true;
        }
        return candidateTs.isAfter(currentTs);
    }

    private int resolveTrafficLookbackSeconds() {
        if (dashboardTrafficLookbackSeconds > 0) {
            return Math.max(MIN_TRAFFIC_LOOKBACK_SECONDS, dashboardTrafficLookbackSeconds);
        }
        int baseInterval = Math.max(heartbeatIntervalSeconds, trafficLogMinIntervalSeconds);
        if (baseInterval <= 0) {
            baseInterval = MIN_TRAFFIC_LOOKBACK_SECONDS;
        }
        return Math.max(MIN_TRAFFIC_LOOKBACK_SECONDS, baseInterval * 2);
    }

    /**
     * 대시보드 전체 새로고침 스냅샷을 조합하여 반환한다.
     *
     * <p>강의실 요약과 네트워크 트래픽을 하나의 DTO로 조합한다.
     * 텍스트/이미지 위젯은 대시보드 범위 스코핑을 위해 별도 API로 제공된다.
     *
     * @return 대시보드 새로고침 DTO
     */
    @Transactional(readOnly = true)
    public DashboardRefreshDto getRefreshSnapshot() {
        DashboardRefreshDto snapshot = new DashboardRefreshDto();
        snapshot.setClassrooms(getClassroomSummaries());
        snapshot.setTraffic(getNetworkTraffic());
        // Text/image widgets are loaded via separate APIs for dashboard-specific scoping
        snapshot.setTexts(List.of());
        snapshot.setImages(List.of());
        return snapshot;
    }

    /**
     * 지정된 타입의 위젯 아이템 목록을 조회한다.
     *
     * @param type 위젯 타입 (대소문자 무시)
     * @return 위젯 응답 맵 목록 (조회 실패 시 빈 목록)
     */
    private List<Map<String, Object>> getWidgetItemsByType(String type) {
        try {
            return dashboardWidgetItemRepository.findByTypeIgnoreCase(type).stream()
                    .map(this::toWidgetResponse)
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch widget items for type {}", type, e);
            return List.of();
        }
    }

    /**
     * {@link DashboardWidgetItem} 엔티티를 클라이언트 응답용 맵으로 변환한다.
     *
     * <p>config JSON 필드를 파싱하여 맵에 병합한다. 파싱 실패 시 경고 로그만 출력하고
     * config 문자열을 그대로 포함하여 부분 응답을 유지한다.
     *
     * @param item 위젯 아이템 엔티티
     * @return 응답용 LinkedHashMap (id, type, 위치/크기, visible, config 필드 포함)
     */
    private Map<String, Object> toWidgetResponse(DashboardWidgetItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.getId());
        out.put("type", item.getType());
        out.put("x", item.getX());
        out.put("y", item.getY());
        out.put("width", item.getWidth());
        out.put("height", item.getHeight());
        out.put("visible", item.isVisible());
        out.put("config", item.getConfig());
        if (item.getConfig() != null && !item.getConfig().isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(item.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                out.putAll(parsed);
            } catch (Exception e) {
                logger.warn("Failed to parse widget config for item {}", item.getId(), e);
            }
        }
        return out;
    }
}
