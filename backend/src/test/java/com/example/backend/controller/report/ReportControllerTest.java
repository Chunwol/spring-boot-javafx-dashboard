package com.example.backend.controller.report;

import com.example.backend.repository.report.MonthlyReportRepository;
import com.example.backend.service.report.MonthlyReportService;
import com.example.backend.service.report.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    @Test
    void trafficReportRejectsRangesLongerThanThirtyOneDaysBeforeQuerying() {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = controller(reportService);

        assertThatThrownBy(() -> controller.getTrafficSummary(
                "2026-01-01T00:00:00", "2026-02-02T00:00:01", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("최대 31일");

        verify(reportService, never()).aggregateTraffic(any(), any(), any());
    }

    @Test
    void trafficReportReturnsDatabaseAggregateContract() {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = controller(reportService);
        Map<LocalDate, Long> daily = new LinkedHashMap<>();
        daily.put(LocalDate.of(2026, 8, 1), 120L);
        Map<String, Long> slots = Map.of("morning", 150L, "afternoon", 0L, "night", 0L);
        when(reportService.aggregateTraffic(isNull(), any(), any()))
                .thenReturn(new ReportService.TrafficSummary(120L, 30L, daily, slots));

        Map<String, Object> body = controller.getTrafficSummary(
                "2026-08-01T00:00:00", "2026-08-01T23:59:59", null).getBody();

        assertThat(body).containsEntry("totalInboundKbps", 120L)
                .containsEntry("totalOutboundKbps", 30L)
                .containsEntry("dailyInbound", Map.of("2026-08-01", 120L))
                .containsEntry("byTimeSlot", slots);
        verify(reportService).aggregateTraffic(isNull(), any(), any());
    }

    @Test
    void monthlyRegenerationAllowsAdminOrAssignedReportFeature() throws Exception {
        Method method = ReportController.class.getMethod("regenerate", String.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN') or hasAuthority('PERM_REPORT_REGENERATE')");
    }

    private static ReportController controller(ReportService reportService) {
        return new ReportController(
                reportService,
                mock(MonthlyReportService.class),
                mock(MonthlyReportRepository.class));
    }
}
