package com.example.backend.controller.report;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "보고서 생성 API")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    // ─── PDF 보고서 ────────────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/pdf?type=WEEKLY
     * GET /api/v1/reports/pdf?type=MONTHLY&year=2026&month=3
     */
    @GetMapping("/pdf")
    @Operation(summary = "PDF 보고서 생성",
               description = "type=WEEKLY(이번 주) 또는 type=MONTHLY&year=&month= 로 구분")
    public ResponseEntity<byte[]> generatePdf(
            @RequestParam(defaultValue = "WEEKLY") String type,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        LocalDateTime from;
        LocalDateTime to;

        if ("MONTHLY".equalsIgnoreCase(type) && year != null && month != null) {
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

    // ─── 트래픽 JSON 집계 ─────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/traffic?from=2026-03-11T00:00:00&to=2026-03-17T23:59:59&groupId=1
     */
    @GetMapping("/traffic")
    @Operation(summary = "트래픽 집계 JSON",
               description = "기간 내 일별 인바운드/아웃바운드 Kbps 합산")
    public ResponseEntity<Map<String, Object>> getTrafficSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long groupId) {

        LocalDateTime[] range = parseDateRange(from, to, 7);
        List<TrafficLog> logs = groupId != null
                ? reportService.getTrafficForGroupBetween(groupId, range[0], range[1])
                : reportService.getTrafficForMacBetween(null, range[0], range[1]);

        Map<LocalDate, Long> dailyIn = reportService.dailyInboundAggregation(logs, range[0], range[1]);
        Map<String, Long> bySlot = reportService.computeUsageByTimeSlot(logs);

        long totalIn = logs.stream()
                .mapToLong(l -> l.getInboundKbps() != null ? l.getInboundKbps() : 0L).sum();
        long totalOut = logs.stream()
                .mapToLong(l -> l.getOutboundKbps() != null ? l.getOutboundKbps() : 0L).sum();

        Map<String, Long> dailyInStr = new LinkedHashMap<>();
        dailyIn.forEach((date, val) -> dailyInStr.put(date.toString(), val));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("from", range[0].toString());
        resp.put("to", range[1].toString());
        resp.put("totalInboundKbps", totalIn);
        resp.put("totalOutboundKbps", totalOut);
        resp.put("dailyInbound", dailyInStr);
        resp.put("byTimeSlot", bySlot);
        return ResponseEntity.ok(resp);
    }

    // ─── 가동률 JSON ──────────────────────────────────────────────────────

    /**
     * GET /api/v1/reports/uptime?from=&to=&groupId=
     */
    @GetMapping("/uptime")
    @Operation(summary = "강의실별 가동률 JSON")
    public ResponseEntity<Map<String, Object>> getUptimeSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long groupId) {

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
}
