package com.example.backend.service.dashboard;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.dto.dashboard.NetworkTrafficDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock DashboardWidgetItemRepository dashboardWidgetItemRepository;
    @Mock ClassroomService classroomService;
    @Mock TrafficLogsRepository trafficLogsRepository;
    @Mock ObjectMapper objectMapper;

    @InjectMocks DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dashboardService, "dashboardTrafficLookbackSeconds", 0);
        ReflectionTestUtils.setField(dashboardService, "heartbeatIntervalSeconds", 120);
        ReflectionTestUtils.setField(dashboardService, "trafficLogMinIntervalSeconds", 30);
    }

    @Test
    void getNetworkTraffic_usesAdaptiveLookbackWindow() {
        when(trafficLogsRepository.findByTimestampBetween(any(), any())).thenReturn(List.of());

        dashboardService.getNetworkTraffic();

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(trafficLogsRepository).findByTimestampBetween(sinceCaptor.capture(), nowCaptor.capture());
        long windowSeconds = Duration.between(sinceCaptor.getValue(), nowCaptor.getValue()).getSeconds();
        assertThat(windowSeconds).isBetween(239L, 241L);
    }

    @Test
    void getNetworkTraffic_ignoresNullKbpsValues() {
        // Service sums latest-per-MAC values and divides by 1024.
        // All three logs have null MAC → each gets a unique anonymous key → all included.
        // inbound: (1000 + 3000) / 1024 = 3.91, outbound: (500 + 2000) / 1024 = 2.44
        TrafficLog first = trafficLog(1000L, 500L);
        TrafficLog second = trafficLog(null, 2000L);
        TrafficLog third = trafficLog(3000L, null);
        when(trafficLogsRepository.findByTimestampBetween(any(), any()))
                .thenReturn(List.of(first, second, third));

        NetworkTrafficDto traffic = dashboardService.getNetworkTraffic();

        assertThat(traffic.getInboundMbps()).isEqualTo(3.91);
        assertThat(traffic.getOutboundMbps()).isEqualTo(2.44);
    }

    private TrafficLog trafficLog(Long inboundKbps, Long outboundKbps) {
        TrafficLog log = new TrafficLog();
        log.setInboundKbps(inboundKbps);
        log.setOutboundKbps(outboundKbps);
        return log;
    }
}
