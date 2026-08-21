package com.example.backend.controller.report;

import com.example.backend.model.report.MonthlyReport;
import com.example.backend.repository.report.MonthlyReportRepository;
import com.example.backend.service.report.MonthlyReportService;
import com.example.backend.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 보고서 생성 컨트롤러.
 *
 * <p>트래픽, 가동률, 강의실 사용 현황 등을 PDF 또는 JSON 형식으로 제공합니다.</p>
 *
 * <ul>
 *   <li>{@code GET /api/v1/reports/pdf} — PDF 다운로드 (주간/월간/사용자 지정 기간)</li>
 *   <li>{@code GET /api/v1/reports/traffic} — 일별 인바운드/아웃바운드 Kbps JSON</li>
 *   <li>{@code GET /api/v1/reports/uptime} — 강의실별 가동률 JSON</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "보고서 생성 API")
public class ReportController {

    static final Duration MAX_TRAFFIC_REPORT_RANGE = Duration.ofDays(31);

    private final ReportService reportService;
    private final MonthlyReportService monthlyReportService;
    private final MonthlyReportRepository monthlyReportRepository;

    public ReportController(ReportService reportService,
                              MonthlyReportService monthlyReportService,
                              MonthlyReportRepository monthlyReportRepository) {
        this.reportService = reportService;
        this.monthlyReportService = monthlyReportService;
        this.monthlyReportRepository = monthlyReportRepository;
    }

    // ─── 월간 보고서 (DB 영구 보존) ──────────────────────────────────────────

    @GetMapping("/monthly")
    @Operation(summary = "월간 보고서 목록", description = "분기 정리 이전 집계 스냅샷 (최신 월 우선)")
    public ResponseEntity<List<MonthlyReport>> listMonthlyReports() {
        return ResponseEntity.ok(monthlyReportRepository.findAllByOrderByYearMonthDesc());
    }

    @GetMapping("/monthly/{yearMonth}")
    @Operation(summary = "특정 월 보고서 조회", description = "yearMonth='2026-05' 형식")
    public ResponseEntity<MonthlyReport> getMonthlyReport(@PathVariable String yearMonth) {
        return monthlyReportRepository.findByYearMonth(yearMonth)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/monthly/{yearMonth}/regenerate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "월간 보고서 강제 재생성",
               description = "ADMIN 전용 — 누락된 월 또는 데이터 보정 후 재집계")
    public ResponseEntity<MonthlyReport> regenerate(@PathVariable String yearMonth) {
        try {
            YearMonth ym = YearMonth.parse(yearMonth);
            return ResponseEntity.ok(monthlyReportService.generate(ym));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ─── PDF 보고서 ────────────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/pdf?type=WEEKLY
     * GET /api/v1/reports/pdf?type=MONTHLY&year=2026&month=3
         * GET /api/v1/reports/pdf?type=WEEKLY&fromDate=2026-03-01&toDate=2026-03-07
     */
    @GetMapping("/pdf")
    @Operation(summary = "PDF 보고서 생성",
               description = "fromDate/toDate(yyyy-MM-dd) 지정 가능. 미지정 시 type=WEEKLY 또는 type=MONTHLY&year=&month= 기준")
    @ApiResponse(responseCode = "200", description = "PDF 파일 반환")
    @ApiResponse(responseCode = "500", description = "PDF 생성 실패")
    public ResponseEntity<byte[]> generatePdf(
            @RequestParam(defaultValue = "WEEKLY") String type,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        LocalDateTime from;
        LocalDateTime to;

        LocalDate parsedFrom = parseLocalDate(fromDate);
        LocalDate parsedTo = parseLocalDate(toDate);

        if (parsedFrom != null && parsedTo != null) {
            LocalDate start = parsedFrom.isAfter(parsedTo) ? parsedTo : parsedFrom;
            LocalDate end = parsedFrom.isAfter(parsedTo) ? parsedFrom : parsedTo;
            from = start.atStartOfDay();
            to = end.atTime(23, 59, 59);
        } else if ("MONTHLY".equalsIgnoreCase(type) && year != null && month != null) {
            YearMonth ym = YearMonth.of(year, month);
            from = ym.atDay(1).atStartOfDay();
            to   = ym.atEndOfMonth().atTime(23, 59, 59);
        } else {
            // 이번 주 월요일 00:00 ~ 일요일 23:59:59
            LocalDate today = LocalDate.now();
            LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
            from = monday.atStartOfDay();
            to   = monday.plusDays(6).atTime(23, 59, 59);
        }

        byte[] pdf = reportService.generatePdfReport(from, to, type);
        if (pdf == null || pdf.length == 0) {
            return ResponseEntity.internalServerError().build();
        }

        String filename = "report_" + type.toLowerCase() + "_"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    private static LocalDate parseLocalDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            LoggerFactory.getLogger(ReportController.class).debug("날짜 파싱 실패: {}", raw);
            return null;
        }
    }

    // ─── 트래픽 JSON 집계 ─────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/traffic?from=2026-03-11T00:00:00&to=2026-03-17T23:59:59&groupId=1
     */
    @GetMapping("/traffic")
    @Operation(summary = "트래픽 집계 JSON",
               description = "기간 내 일별 인바운드/아웃바운드 Kbps 합산. from/to 미지정 시 최근 7일.")
    @ApiResponse(responseCode = "200", description = "트래픽 집계 결과 반환")
    public ResponseEntity<Map<String, Object>> getTrafficSummary(
            @Parameter(description = "시작 일시 (yyyy-MM-dd'T'HH:mm:ss)", example = "2026-05-01T00:00:00")
            @RequestParam(required = false) String from,
            @Parameter(description = "종료 일시 (yyyy-MM-dd'T'HH:mm:ss)", example = "2026-05-07T23:59:59")
            @RequestParam(required = false) String to,
            @Parameter(description = "강의실 ID (미지정 시 전체)") @RequestParam(required = false) Long groupId) {

        LocalDateTime[] range = parseTrafficDateRange(from, to);
        ReportService.TrafficSummary summary = reportService.aggregateTraffic(groupId, range[0], range[1]);

        Map<String, Long> dailyInStr = new LinkedHashMap<>();
        summary.dailyInbound().forEach((date, val) -> dailyInStr.put(date.toString(), val));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("from", range[0].toString());
        resp.put("to", range[1].toString());
        resp.put("totalInboundKbps", summary.totalInboundKbps());
        resp.put("totalOutboundKbps", summary.totalOutboundKbps());
        resp.put("dailyInbound", dailyInStr);
        resp.put("byTimeSlot", summary.byTimeSlot());
        return ResponseEntity.ok(resp);
    }

    // ─── 가동률 JSON ──────────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/uptime?from=&to=&groupId=
     */
    @GetMapping("/uptime")
    @Operation(summary = "강의실별 가동률 JSON",
               description = "기간 내 강의실별 PC 가동률(%)을 반환합니다. groupId 지정 시 해당 강의실만 필터링.")
    @ApiResponse(responseCode = "200", description = "가동률 집계 결과 반환")
    public ResponseEntity<Map<String, Object>> getUptimeSummary(
            @Parameter(description = "시작 일시 (yyyy-MM-dd'T'HH:mm:ss)") @RequestParam(required = false) String from,
            @Parameter(description = "종료 일시 (yyyy-MM-dd'T'HH:mm:ss)") @RequestParam(required = false) String to,
            @Parameter(description = "강의실 ID (미지정 시 전체)") @RequestParam(required = false) Long groupId) {

        LocalDateTime[] range = parseDateRange(from, to, 7);
        List<Map<String, Object>> rows = reportService.computeUptimeByGroup(range[0], range[1]);

        if (groupId != null) {
            rows = rows.stream()
                    .filter(r -> groupId.equals(r.get("groupId")))
                    .collect(java.util.stream.Collectors.toList());
        }

        double avg = rows.stream()
                .mapToDouble(r -> {
                    try { return Double.parseDouble(r.get("uptimePct").toString()); }
                    catch (Exception e) { return 0.0; }
                }).average().orElse(0.0);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("from", range[0].toString());
        resp.put("to", range[1].toString());
        resp.put("averageUptimePct", String.format("%.1f", avg));
        resp.put("groups", rows);
        return ResponseEntity.ok(resp);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private static LocalDateTime[] parseDateRange(String from, String to, int defaultDays) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        LocalDateTime f, t;
        try {
            f = from != null ? LocalDateTime.parse(from, dtf) : LocalDate.now().minusDays(defaultDays).atStartOfDay();
            t = to   != null ? LocalDateTime.parse(to, dtf)   : LocalDate.now().atTime(23, 59, 59);
        } catch (Exception e) {
            f = LocalDate.now().minusDays(defaultDays).atStartOfDay();
            t = LocalDate.now().atTime(23, 59, 59);
        }
        return new LocalDateTime[]{f, t};
    }

    private static LocalDateTime[] parseTrafficDateRange(String from, String to) {
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
            LocalDate today = LocalDate.now();
            return new LocalDateTime[]{today.minusDays(6).atStartOfDay(), today.atTime(23, 59, 59)};
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from과 to를 함께 입력해야 합니다.");
        }

        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(from, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            end = LocalDateTime.parse(to, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 또는 to 형식이 올바르지 않습니다.");
        }

        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 늦을 수 없습니다.");
        }
        if (Duration.between(start, end).compareTo(MAX_TRAFFIC_REPORT_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "트래픽 보고서는 최대 31일까지 조회할 수 있습니다.");
        }
        return new LocalDateTime[]{start, end};
    }
}
