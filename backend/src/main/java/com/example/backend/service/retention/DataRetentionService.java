package com.example.backend.service.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 데이터 보존 정책 서비스.
 *
 * <p>로그성 테이블이 무한히 누적되는 것을 방지하기 위해 3개월(90일) 보존 후 자동 삭제.
 * DB 용량 상한 30 GB 이내로 유지하기 위한 안전장치.</p>
 *
 * <h2>스케줄</h2>
 * <ul>
 *   <li>분기 정리 — 매 분기 1일 03:30 (1/4/7/10월 1일)</li>
 *   <li>월간 보고서가 먼저 실행되도록 30분 지연 (월간 보고서는 03:00)</li>
 * </ul>
 *
 * <h2>대상 테이블 + 보존 기간</h2>
 * <ul>
 *   <li>traffic_logs        — 90일 (5분 집계 후 230k rows/day × 90 = 21M rows)</li>
 *   <li>audit_logs          — 180일 (감사 추적 보존 강화)</li>
 *   <li>command_queue       — COMPLETED/FAILED만 90일</li>
 *   <li>operational_alerts  — 180일</li>
 *   <li>file_distribution_* — 종료된 작업 365일</li>
 * </ul>
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    private final JdbcTemplate jdbc;

    public DataRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 분기별 (1/4/7/10월 1일 03:30) 자동 정리.
     * <p>주의: {@code @Transactional}을 붙이면 안 됨. 뒤이은 VACUUM은 트랜잭션 블록 안에서
     * 실행할 수 없다(PostgreSQL "VACUUM cannot run inside a transaction block").
     * 각 DELETE는 테이블별 독립이라 원자성이 불필요하므로 autocommit으로 개별 실행한다.</p>
     */
    @Scheduled(cron = "${app.retention.quarterly-cron:0 30 3 1 1,4,7,10 ?}")
    public void quarterlyCleanup() {
        LocalDateTime now = LocalDateTime.now();
        log.info("=== 분기 데이터 정리 시작 ({}) ===", now);

        Map<String, Long> deleted = new LinkedHashMap<>();
        deleted.put("traffic_logs", deleteOlderThan(
                "DELETE FROM traffic_logs WHERE timestamp < ?", now.minusDays(90)));
        deleted.put("audit_logs", deleteOlderThan(
                "DELETE FROM audit_logs WHERE created_at < ?", now.minusDays(180)));
        deleted.put("command_queue", deleteOlderThan(
                "DELETE FROM command_queue WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND updated_at < ?",
                now.minusDays(90)));
        deleted.put("operational_alerts", deleteOlderThan(
                "DELETE FROM operational_alerts WHERE created_at < ?", now.minusDays(180)));
        deleted.put("file_distribution_events", deleteOlderThan(
                "DELETE FROM file_distribution_events WHERE created_at < ?", now.minusDays(365)));

        long total = deleted.values().stream().mapToLong(Long::longValue).sum();
        log.info("=== 분기 데이터 정리 완료: 총 {}건 삭제 ({}) ===", total, deleted);

        runVacuum(); // 트랜잭션 밖에서 실행 (autocommit)
    }

    /** 주간 (매 일요일 04:00) 가벼운 정리 — completed command만. */
    @Scheduled(cron = "${app.retention.weekly-cron:0 0 4 ? * SUN}")
    public void weeklyCleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        long removed = deleteOlderThan(
                "DELETE FROM command_queue WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND updated_at < ?",
                cutoff);
        if (removed > 0) {
            log.info("주간 정리: command_queue 완료된 행 {}건 삭제", removed);
        }
    }

    private long deleteOlderThan(String sql, LocalDateTime cutoff) {
        try {
            int n = jdbc.update(sql, Timestamp.valueOf(cutoff));
            log.info("  - {} → {}건", abbreviate(sql), n);
            return n;
        } catch (Exception e) {
            log.warn("정리 실패 ({}): {}", abbreviate(sql), e.getMessage());
            return 0;
        }
    }

    /** PostgreSQL VACUUM ANALYZE — 삭제 후 디스크 공간 회수. */
    private void runVacuum() {
        try {
            jdbc.execute("VACUUM ANALYZE traffic_logs");
            jdbc.execute("VACUUM ANALYZE audit_logs");
            log.info("VACUUM ANALYZE 완료 (traffic_logs, audit_logs)");
        } catch (Exception e) {
            log.debug("VACUUM 스킵: {}", e.getMessage());
        }
    }

    private static String abbreviate(String sql) {
        int from = sql.toUpperCase().indexOf("FROM ");
        if (from < 0) return sql;
        int end = sql.indexOf(" WHERE", from);
        return sql.substring(from + 5, end > 0 ? end : Math.min(from + 30, sql.length()));
    }
}
