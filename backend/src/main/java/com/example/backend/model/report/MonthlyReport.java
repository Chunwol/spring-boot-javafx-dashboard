package com.example.backend.model.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 월간 운영 보고서. 매월 1일 03:00 전월 데이터 기준 1행 생성.
 * 데이터 보존 정책으로 원본 행이 삭제되어도 집계 결과는 영구 보존.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "monthly_reports")
public class MonthlyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "YYYY-MM" — 보고 대상 월 (UNIQUE). */
    @Column(name = "year_month", nullable = false, unique = true, length = 7)
    private String yearMonth;

    @Column(name = "total_pcs", nullable = false)
    private Integer totalPcs;

    @Column(name = "avg_online_pcs", nullable = false)
    private Integer avgOnlinePcs;

    @Column(name = "peak_online_pcs", nullable = false)
    private Integer peakOnlinePcs;

    @Column(name = "online_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal onlineRatio;

    @Column(name = "total_commands", nullable = false)
    private Integer totalCommands;

    @Column(name = "failed_commands", nullable = false)
    private Integer failedCommands;

    @Column(name = "total_alerts", nullable = false)
    private Integer totalAlerts;

    @Column(name = "total_traffic_gb", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalTrafficGb;

    /** 강의실별 / 시간대별 / 명령 타입별 상세 통계 JSON. */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();
}
