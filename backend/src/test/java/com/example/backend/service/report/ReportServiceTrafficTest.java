package com.example.backend.service.report;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTrafficTest {

    @Test
    void aggregateTrafficUsesBoundedDatabaseProjectionsInsteadOfLoadingEntities() {
        TrafficLogsRepository traffic = mock(TrafficLogsRepository.class);
        ReportService service = new ReportService(
                mock(PcMasterRepository.class),
                traffic,
                mock(ClassroomGroupRepository.class),
                mock(CommandQueueRepository.class));
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 7, 23, 59);

        TrafficLogsRepository.DailyTrafficAggregate firstDay = daily(2026, 8, 1, 100L, 40L);
        TrafficLogsRepository.DailyTrafficAggregate secondDay = daily(2026, 8, 2, 250L, 60L);
        TrafficLogsRepository.HourlyTrafficAggregate morning = hourly(8, 10L, 5L);
        TrafficLogsRepository.HourlyTrafficAggregate afternoon = hourly(14, 20L, 7L);
        TrafficLogsRepository.HourlyTrafficAggregate night = hourly(22, 30L, 9L);
        when(traffic.aggregateDailyBetween(from, to)).thenReturn(List.of(firstDay, secondDay));
        when(traffic.aggregateHourlyBetween(from, to)).thenReturn(List.of(morning, afternoon, night));

        ReportService.TrafficSummary result = service.aggregateTraffic(null, from, to);

        assertThat(result.totalInboundKbps()).isEqualTo(350L);
        assertThat(result.totalOutboundKbps()).isEqualTo(100L);
        assertThat(result.dailyInbound()).containsExactly(
                org.assertj.core.api.Assertions.entry(LocalDate.of(2026, 8, 1), 100L),
                org.assertj.core.api.Assertions.entry(LocalDate.of(2026, 8, 2), 250L));
        assertThat(result.byTimeSlot()).containsExactly(
                org.assertj.core.api.Assertions.entry("morning", 15L),
                org.assertj.core.api.Assertions.entry("afternoon", 27L),
                org.assertj.core.api.Assertions.entry("night", 39L));
        verify(traffic, never()).findByTimestampBetween(from, to);
    }

    @Test
    void nullMacLoadsAllTrafficForTheRequestedRange() {
        TrafficLogsRepository traffic = mock(TrafficLogsRepository.class);
        ReportService service = new ReportService(
                mock(PcMasterRepository.class),
                traffic,
                mock(ClassroomGroupRepository.class),
                mock(CommandQueueRepository.class));
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 7, 23, 59);
        List<TrafficLog> expected = List.of(new TrafficLog());
        when(traffic.findByTimestampBetween(from, to)).thenReturn(expected);

        assertThat(service.getTrafficForMacBetween(null, from, to)).isSameAs(expected);
        verify(traffic).findByTimestampBetween(from, to);
        verify(traffic, never()).findByMacAddressAndTimestampBetween(null, from, to);
    }

    private static TrafficLogsRepository.DailyTrafficAggregate daily(
            int year, int month, int day, long inbound, long outbound) {
        TrafficLogsRepository.DailyTrafficAggregate row = mock(TrafficLogsRepository.DailyTrafficAggregate.class);
        when(row.getYearPart()).thenReturn(year);
        when(row.getMonthPart()).thenReturn(month);
        when(row.getDayPart()).thenReturn(day);
        when(row.getInboundKbps()).thenReturn(inbound);
        when(row.getOutboundKbps()).thenReturn(outbound);
        return row;
    }

    private static TrafficLogsRepository.HourlyTrafficAggregate hourly(int hour, long inbound, long outbound) {
        TrafficLogsRepository.HourlyTrafficAggregate row = mock(TrafficLogsRepository.HourlyTrafficAggregate.class);
        when(row.getHourPart()).thenReturn(hour);
        when(row.getInboundKbps()).thenReturn(inbound);
        when(row.getOutboundKbps()).thenReturn(outbound);
        return row;
    }
}
