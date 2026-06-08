package com.example.backend.service.report;

import com.example.backend.model.report.MonthlyReport;
import com.example.backend.repository.report.MonthlyReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 월간 운영 보고서 생성 서비스.
 *
 * <p>분기 정리(DataRetentionService)로 원본 행이 삭제되기 전에
 * 전월 데이터를 집계해 {@code monthly_reports} 테이블에 1행 보존.</p>
 *
 * <h2>스케줄</h2>
 * <ul>
 *   <li>매월 1일 03:00 — 전월 데이터 집계 → INSERT</li>
 *   <li>분기 정리(03:30)보다 먼저 실행되어야 원본 데이터 보존됨</li>
 * </ul>
 */
@Service
public class MonthlyReportService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportService.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MonthlyReportRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MonthlyReportService(MonthlyReportRepository repository,
                                 JdbcTemplate jdbc,
                                 ObjectMapper json) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 매월 1일 03:00 — 전월 보고서 자동 생성. */
    @Scheduled(cron = "${app.report.monthly-cron:0 0 3 1 * ?}")
    @Transactional
    public void generatePreviousMonthReport() {
        YearMonth previousMonth = YearMonth.now().minusMonths(1);
        try {
            MonthlyReport report = generate(previousMonth);
            log.info("월간 보고서 생성: {} (id={})", report.getYearMonth(), report.getId());
        } catch (Exception e) {
            log.error("월간 보고서 생성 실패: {}", e.getMessage(), e);
        }
    }

    /** 지정 월에 대한 보고서를 생성·저장 (멱등 — 동일 월 재호출 시 갱신). */
    public MonthlyReport generate(YearMonth ym) {
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
        String key = ym.format(YM);

        MonthlyReport report = repository.findByYearMonth(key).orElseGet(MonthlyReport::new);
        report.setYearMonth(key);
        report.setTotalPcs(scalarInt("SELECT COUNT(*) FROM pc_master", null, null));
        // 온라인 PC 수는 traffic_logs 기준으로 산출한다.
        // 온라인 PC는 5분 집계마다 (timestamp, mac) 1행을 남기므로
        // timestamp별 distinct mac 수 = 그 5분 시점의 온라인 PC 수.
        // (audit_logs에는 PC_ONLINE 액션이 없으므로 사용 불가)
        report.setAvgOnlinePcs(scalarInt(
                "SELECT COALESCE(AVG(online_count),0) FROM (" +
                "  SELECT timestamp, COUNT(DISTINCT mac_address) online_count" +
                "  FROM traffic_logs WHERE timestamp >= ? AND timestamp < ?" +
                "  GROUP BY timestamp) t", start, end));
        report.setPeakOnlinePcs(scalarInt(
                "SELECT COALESCE(MAX(online_count),0) FROM (" +
                "  SELECT timestamp, COUNT(DISTINCT mac_address) online_count" +
                "  FROM traffic_logs WHERE timestamp >= ? AND timestamp < ?" +
                "  GROUP BY timestamp) t", start, end));
        report.setOnlineRatio(ratio(report.getAvgOnlinePcs(), report.getTotalPcs()));

        int totalCmds = scalarInt(
                "SELECT COUNT(*) FROM command_queue WHERE created_at >= ? AND created_at < ?", start, end);
        int failedCmds = scalarInt(
                "SELECT COUNT(*) FROM command_queue WHERE status IN ('FAILED','CANCELLED') " +
                "AND created_at >= ? AND created_at < ?", start, end);
        report.setTotalCommands(totalCmds);
        report.setFailedCommands(failedCmds);

        report.setTotalAlerts(scalarInt(
                "SELECT COUNT(*) FROM operational_alerts WHERE created_at >= ? AND created_at < ?", start, end));

        // 트래픽 합계 — 5분 집계 행 합(kbps × 300s ÷ 8 = 바이트)
        BigDecimal trafficKbps = scalarDecimal(
                "SELECT COALESCE(SUM(inbound_kbps + outbound_kbps), 0) FROM traffic_logs " +
                "WHERE timestamp >= ? AND timestamp < ?", start, end);
        BigDecimal trafficGb = trafficKbps
                .multiply(BigDecimal.valueOf(300))           // × 5분(초)
                .divide(BigDecimal.valueOf(8L * 1024 * 1024 * 1024), 2, RoundingMode.HALF_UP); // → GB
        report.setTotalTrafficGb(trafficGb);

        // 강의실별 집계 (Top 10) — JSON
        report.setSummaryJson(buildSummaryJson(start, end));
        report.setGeneratedAt(LocalDateTime.now());

        return repository.save(report);
    }

    private String buildSummaryJson(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> root = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> trafficTop = jdbc.queryForList(
                    "SELECT t.mac_address, COUNT(*) samples, " +
                    "       AVG(inbound_kbps + outbound_kbps)::BIGINT avg_kbps " +
                    "FROM traffic_logs t " +
                    "WHERE timestamp >= ? AND timestamp < ? " +
                    "GROUP BY t.mac_address ORDER BY avg_kbps DESC LIMIT 10",
                    Timestamp.valueOf(start), Timestamp.valueOf(end));
            root.put("trafficTop10", trafficTop);

            List<Map<String, Object>> alertsByType = jdbc.queryForList(
                    "SELECT alert_type, COUNT(*) cnt FROM operational_alerts " +
                    "WHERE created_at >= ? AND created_at < ? " +
                    "GROUP BY alert_type ORDER BY cnt DESC",
                    Timestamp.valueOf(start), Timestamp.valueOf(end));
            root.put("alertsByType", alertsByType);

            List<Map<String, Object>> commandsByType = jdbc.queryForList(
                    "SELECT type, status, COUNT(*) cnt FROM command_queue " +
                    "WHERE created_at >= ? AND created_at < ? " +
                    "GROUP BY type, status ORDER BY cnt DESC LIMIT 20",
                    Timestamp.valueOf(start), Timestamp.valueOf(end));
            root.put("commandsByTypeStatus", commandsByType);

            return json.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("월간 보고서 상세 JSON 생성 실패: {}", e.getMessage());
            return "{}";
        }
    }

    private int scalarInt(String sql, LocalDateTime start, LocalDateTime end) {
        try {
            Integer n = (start == null)
                    ? jdbc.queryForObject(sql, Integer.class)
                    : jdbc.queryForObject(sql, Integer.class, Timestamp.valueOf(start), Timestamp.valueOf(end));
            return n != null ? n : 0;
        } catch (Exception e) {
            log.warn("scalarInt 실패 ({}): {}", e.getMessage(), sql);
            return 0;
        }
    }

    private BigDecimal scalarDecimal(String sql, LocalDateTime start, LocalDateTime end) {
        try {
            BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class,
                    Timestamp.valueOf(start), Timestamp.valueOf(end));
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal ratio(int part, int whole) {
        if (whole <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(2, RoundingMode.HALF_UP);
    }
}
