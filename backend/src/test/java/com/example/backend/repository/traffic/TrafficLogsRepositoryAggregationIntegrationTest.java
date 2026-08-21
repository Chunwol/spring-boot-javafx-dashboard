package com.example.backend.repository.traffic;

import com.example.backend.Application;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.service.report.ReportService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TrafficLogsRepositoryAggregationIntegrationTest {

    private static final Path DB_PATH = Path.of(
            "build", "tmp", "traffic-aggregation-it-" + System.currentTimeMillis() + ".db")
            .toAbsolutePath()
            .normalize();

    @Autowired
    private TrafficLogsRepository repository;

    @Autowired
    private ReportService reportService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "false");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
        registry.add("app.jwt.secret", () -> "traffic-aggregation-integration-test-secret-2026");
    }

    @AfterAll
    static void cleanUp() {
        deleteIfAvailable(DB_PATH);
        deleteIfAvailable(Path.of(DB_PATH + "-journal"));
        deleteIfAvailable(Path.of(DB_PATH + "-wal"));
        deleteIfAvailable(Path.of(DB_PATH + "-shm"));
    }

    @Test
    void aggregatesByCalendarDayAndHourOnSqliteDialect() {
        repository.saveAll(List.of(
                traffic("AA:AA:AA:AA:AA:01", 100L, 20L, LocalDateTime.of(2026, 8, 1, 8, 0)),
                traffic("AA:AA:AA:AA:AA:02", 200L, 30L, LocalDateTime.of(2026, 8, 1, 8, 30)),
                traffic("AA:AA:AA:AA:AA:01", 400L, 50L, LocalDateTime.of(2026, 8, 2, 14, 0))));
        repository.flush();

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 2, 23, 59, 59);
        ReportService.TrafficSummary summary = reportService.aggregateTraffic(null, from, to);
        List<TrafficLogsRepository.DailyTrafficAggregate> daily = repository.aggregateDailyBetweenSqlite(from, to);
        List<TrafficLogsRepository.HourlyTrafficAggregate> hourly = repository.aggregateHourlyBetweenSqlite(from, to);
        List<TrafficLogsRepository.DailyTrafficAggregate> oneMac =
                repository.aggregateDailyForMacAddressesBetweenSqlite(List.of("AA:AA:AA:AA:AA:01"), from, to);

        assertThat(summary.totalInboundKbps()).isEqualTo(700L);
        assertThat(summary.totalOutboundKbps()).isEqualTo(100L);
        assertThat(summary.dailyInbound()).containsExactly(
                org.assertj.core.api.Assertions.entry(java.time.LocalDate.of(2026, 8, 1), 300L),
                org.assertj.core.api.Assertions.entry(java.time.LocalDate.of(2026, 8, 2), 400L));
        assertThat(summary.byTimeSlot()).containsExactly(
                org.assertj.core.api.Assertions.entry("morning", 350L),
                org.assertj.core.api.Assertions.entry("afternoon", 450L),
                org.assertj.core.api.Assertions.entry("night", 0L));
        assertThat(daily)
                .extracting(
                        TrafficLogsRepository.DailyTrafficAggregate::getYearPart,
                        TrafficLogsRepository.DailyTrafficAggregate::getMonthPart,
                        TrafficLogsRepository.DailyTrafficAggregate::getDayPart,
                        TrafficLogsRepository.DailyTrafficAggregate::getInboundKbps,
                        TrafficLogsRepository.DailyTrafficAggregate::getOutboundKbps)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2026, 8, 1, 300L, 50L),
                        org.assertj.core.groups.Tuple.tuple(2026, 8, 2, 400L, 50L));
        assertThat(daily.get(0).getYearPart()).isEqualTo(2026);
        assertThat(daily.get(0).getMonthPart()).isEqualTo(8);
        assertThat(daily.get(0).getDayPart()).isEqualTo(1);
        assertThat(daily.get(0).getInboundKbps()).isEqualTo(300L);
        assertThat(daily.get(0).getOutboundKbps()).isEqualTo(50L);
        assertThat(hourly).extracting(TrafficLogsRepository.HourlyTrafficAggregate::getHourPart)
                .containsExactly(8, 14);
        assertThat(hourly.get(0).getInboundKbps()).isEqualTo(300L);
        assertThat(oneMac).extracting(TrafficLogsRepository.DailyTrafficAggregate::getInboundKbps)
                .containsExactly(100L, 400L);
    }

    private static TrafficLog traffic(String mac, long inbound, long outbound, LocalDateTime timestamp) {
        TrafficLog log = new TrafficLog();
        log.setMacAddress(mac);
        log.setInboundKbps(inbound);
        log.setOutboundKbps(outbound);
        log.setTimestamp(timestamp);
        return log;
    }

    private static void deleteIfAvailable(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
