package com.example.backend.service.report;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final PcMasterRepository pcRepository;
    private final TrafficLogsRepository trafficRepository;
    private final ClassroomGroupRepository groupRepository;
    private final CommandQueueRepository commandRepository;

    public ReportService(PcMasterRepository pcRepository,
                         TrafficLogsRepository trafficRepository,
                         ClassroomGroupRepository groupRepository,
                         CommandQueueRepository commandRepository) {
        this.pcRepository = pcRepository;
        this.trafficRepository = trafficRepository;
        this.groupRepository = groupRepository;
        this.commandRepository = commandRepository;
    }

    // ─── PDF 생성 진입점 ───────────────────────────────────────────────────

    /**
     * 기간 지정 PDF 보고서 생성.
     * type = "WEEKLY" or "MONTHLY"
     */
    public byte[] generatePdfReport(LocalDateTime from, LocalDateTime to, String type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String period = from.format(dtf) + " ~ " + to.format(dtf);
            String title = "WEEKLY".equalsIgnoreCase(type)
                    ? "PC 관제 시스템 주간 보고서"
                    : "PC 관제 시스템 월간 보고서";

            // ── 표지 ──────────────────────────────────────────────────────
            doc.add(new Paragraph(title)
                    .setFontSize(22).setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(6));
            doc.add(new Paragraph("기간: " + period)
                    .setFontSize(11).setTextAlignment(TextAlignment.CENTER).setMarginBottom(4));
            doc.add(new Paragraph("생성: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .setFontSize(10).setTextAlignment(TextAlignment.CENTER).setMarginBottom(20));

            // ── 1. 요약 ───────────────────────────────────────────────────
            List<PcMaster> allPcs = pcRepository.findAll();
            long totalPc = allPcs.size();
            long onlinePc = allPcs.stream().filter(p -> "ONLINE".equalsIgnoreCase(p.getStatus())).count();

            List<TrafficLog> trafficLogs = trafficRepository.findByTimestampBetween(from, to);
            long totalInKbps = trafficLogs.stream()
                    .mapToLong(t -> t.getInboundKbps() != null ? t.getInboundKbps() : 0L).sum();
            long totalOutKbps = trafficLogs.stream()
                    .mapToLong(t -> t.getOutboundKbps() != null ? t.getOutboundKbps() : 0L).sum();

            List<CommandQueue> cmdLogs = commandRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
            long cmdTotal = cmdLogs.size();
            long cmdSuccess = cmdLogs.stream().filter(c -> "COMPLETED".equalsIgnoreCase(c.getStatus())).count();
            long cmdFail = cmdLogs.stream().filter(c -> "FAILED".equalsIgnoreCase(c.getStatus())).count();

            double avgUptime = computeAverageUptimePct(from, to);

            doc.add(section("1. 요약"));
            Table sumTbl = twoColTable();
            sumTbl.addCell(label("총 PC 수")); sumTbl.addCell(value(totalPc + "대"));
            sumTbl.addCell(label("현재 온라인")); sumTbl.addCell(value(onlinePc + "대"));
            sumTbl.addCell(label("평균 가동률")); sumTbl.addCell(value(String.format("%.1f%%", avgUptime)));
            sumTbl.addCell(label("총 인바운드 트래픽")); sumTbl.addCell(value(kbToReadable(totalInKbps)));
            sumTbl.addCell(label("총 아웃바운드 트래픽")); sumTbl.addCell(value(kbToReadable(totalOutKbps)));
            sumTbl.addCell(label("명령 실행 건수")); sumTbl.addCell(value(cmdTotal + "건 (성공 " + cmdSuccess + " / 실패 " + cmdFail + ")"));
            doc.add(sumTbl);
            doc.add(new Paragraph("\n"));

            // ── 2. 강의실별 가동률 ────────────────────────────────────────
            doc.add(section("2. 강의실별 가동률"));
            List<Map<String, Object>> uptimeRows = computeUptimeByGroup(from, to);
            if (uptimeRows.isEmpty()) {
                doc.add(new Paragraph("  (강의실 데이터 없음)").setFontSize(10));
            } else {
                Table uptimeTbl = new Table(UnitValue.createPercentArray(new float[]{30, 12, 12, 14, 12}))
                        .setWidth(UnitValue.createPercentValue(100));
                for (String h : new String[]{"강의실", "총 PC", "평균 온라인", "가동률", "트래픽 이상"}) {
                    uptimeTbl.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(10)));
                }
                for (Map<String, Object> row : uptimeRows) {
                    uptimeTbl.addCell(cell(str(row, "groupName")));
                    uptimeTbl.addCell(cell(str(row, "totalPc")));
                    uptimeTbl.addCell(cell(str(row, "avgOnline")));
                    uptimeTbl.addCell(cell(str(row, "uptimePct") + "%"));
                    uptimeTbl.addCell(cell(str(row, "anomalyCount") + "건"));
                }
                doc.add(uptimeTbl);
            }
            doc.add(new Paragraph("\n"));

            // ── 3. 일별 트래픽 (인바운드) ────────────────────────────────
            doc.add(section("3. 일별 인바운드 트래픽"));
            Map<LocalDate, Long> dailyIn = dailyInboundAggregation(trafficLogs, from, to);
            if (dailyIn.isEmpty()) {
                doc.add(new Paragraph("  (트래픽 데이터 없음)").setFontSize(10));
            } else {
                Table trafficTbl = new Table(UnitValue.createPercentArray(new float[]{20, 40, 40}))
                        .setWidth(UnitValue.createPercentValue(100));
                trafficTbl.addHeaderCell(new Cell().add(new Paragraph("날짜").setBold().setFontSize(10)));
                trafficTbl.addHeaderCell(new Cell().add(new Paragraph("인바운드").setBold().setFontSize(10)));
                trafficTbl.addHeaderCell(new Cell().add(new Paragraph("바 그래프").setBold().setFontSize(10)));

                long maxVal = dailyIn.values().stream().mapToLong(Long::longValue).max().orElse(1L);
                for (Map.Entry<LocalDate, Long> e : new TreeMap<>(dailyIn).entrySet()) {
                    float pct = (float) e.getValue() / maxVal;
                    trafficTbl.addCell(cell(e.getKey().toString()));
                    trafficTbl.addCell(cell(kbToReadable(e.getValue())));
                    // 비율 바
                    Table bar = new Table(UnitValue.createPercentArray(new float[]{pct * 100f, (1 - pct) * 100f + 0.001f}))
                            .setWidth(UnitValue.createPercentValue(100));
                    bar.addCell(new Cell().setHeight(10)
                            .setBackgroundColor(new DeviceRgb(0x27, 0xae, 0x60))
                            .setBorder(null));
                    bar.addCell(new Cell().setBorder(null));
                    trafficTbl.addCell(new Cell().add(bar).setBorder(null));
                }
                doc.add(trafficTbl);
            }
            doc.add(new Paragraph("\n"));

            // ── 4. 명령 실행 이력 요약 ────────────────────────────────────
            doc.add(section("4. 명령 실행 이력 요약"));
            Map<String, long[]> cmdSummary = summarizeCommandHistory(cmdLogs);
            if (cmdSummary.isEmpty()) {
                doc.add(new Paragraph("  (명령 이력 없음)").setFontSize(10));
            } else {
                Table cmdTbl = new Table(UnitValue.createPercentArray(new float[]{30, 17, 17, 17, 19}))
                        .setWidth(UnitValue.createPercentValue(100));
                for (String h : new String[]{"명령 유형", "총 건수", "완료", "실패", "진행 중"}) {
                    cmdTbl.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(10)));
                }
                for (Map.Entry<String, long[]> e : cmdSummary.entrySet()) {
                    long[] v = e.getValue(); // [total, completed, failed, inprogress]
                    cmdTbl.addCell(cell(e.getKey()));
                    cmdTbl.addCell(cell(String.valueOf(v[0])));
                    cmdTbl.addCell(cell(String.valueOf(v[1])));
                    Cell failCell = new Cell().add(new Paragraph(String.valueOf(v[2])).setFontSize(10));
                    if (v[2] > 0) failCell.setBackgroundColor(new DeviceRgb(0xe7, 0x4c, 0x3c));
                    cmdTbl.addCell(failCell);
                    cmdTbl.addCell(cell(String.valueOf(v[3])));
                }
                doc.add(cmdTbl);
            }

            doc.close();
        } catch (Exception e) {
            log.error("PDF 보고서 생성 실패: {}", e.getMessage(), e);
            return new byte[0];
        }
        return out.toByteArray();
    }

    // ─── 집계 메서드 ──────────────────────────────────────────────────────

    /**
     * 강의실별 가동률 집계.
     * 가동률 ≈ (기간 내 traffic_log 수신 횟수 / 예상 하트비트 횟수) × 100
     */
    public List<Map<String, Object>> computeUptimeByGroup(LocalDateTime from, LocalDateTime to) {
        List<ClassroomGroup> groups = groupRepository.findAll();
        long periodMinutes = Duration.between(from, to).toMinutes();
        // 2분 간격 하트비트 기준 예상 횟수
        long expectedBeats = Math.max(1, periodMinutes / 2);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ClassroomGroup g : groups) {
            List<PcMaster> pcs = pcRepository.findByGroupId(g.getId());
            if (pcs.isEmpty()) continue;

            long totalOnlineAccum = 0;
            int anomalyCount = 0;
            for (PcMaster pc : pcs) {
                List<TrafficLog> logs = trafficRepository
                        .findByMacAddressAndTimestampBetween(pc.getMacAddress(), from, to);
                totalOnlineAccum += logs.size();
                // 임계 초과: 최대 인바운드가 100Mbps 넘는 경우
                boolean anomaly = logs.stream().anyMatch(t ->
                        t.getInboundKbps() != null && t.getInboundKbps() > 100_000L);
                if (anomaly) anomalyCount++;
            }

            double avgOnline = pcs.isEmpty() ? 0 : (double) totalOnlineAccum / pcs.size();
            double uptimePct = Math.min(100.0, avgOnline / expectedBeats * 100.0);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupId", g.getId());
            row.put("groupName", g.getName());
            row.put("totalPc", pcs.size());
            row.put("avgOnline", String.format("%.1f", avgOnline));
            row.put("uptimePct", String.format("%.1f", uptimePct));
            row.put("anomalyCount", anomalyCount);
            result.add(row);
        }
        return result;
    }

    /**
     * 전체 평균 가동률 계산.
     */
    public double computeAverageUptimePct(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = computeUptimeByGroup(from, to);
        if (rows.isEmpty()) return 0.0;
        return rows.stream()
                .mapToDouble(r -> {
                    try { return Double.parseDouble(r.get("uptimePct").toString()); }
                    catch (Exception e) { return 0.0; }
                })
                .average().orElse(0.0);
    }

    /**
     * 날짜별 인바운드 Kbps 합산.
     */
    public Map<LocalDate, Long> dailyInboundAggregation(List<TrafficLog> logs,
                                                        LocalDateTime from, LocalDateTime to) {
        return logs.stream()
                .filter(l -> l.getTimestamp() != null)
                .collect(Collectors.groupingBy(
                        l -> l.getTimestamp().toLocalDate(),
                        TreeMap::new,
                        Collectors.summingLong(l -> l.getInboundKbps() != null ? l.getInboundKbps() : 0L)));
    }

    /**
     * 명령 유형별 건수 집계.
     * 반환: Map<commandType, long[]{total, completed, failed, inprogress}>
     */
    public Map<String, long[]> summarizeCommandHistory(List<CommandQueue> cmds) {
        Map<String, long[]> summary = new LinkedHashMap<>();
        for (CommandQueue c : cmds) {
            String type = c.getCommandType() != null ? c.getCommandType() : "UNKNOWN";
            long[] v = summary.computeIfAbsent(type, k -> new long[4]);
            v[0]++;
            switch (c.getStatus() != null ? c.getStatus() : "") {
                case "COMPLETED" -> v[1]++;
                case "FAILED"    -> v[2]++;
                default          -> v[3]++;
            }
        }
        return summary;
    }

    /** summarizeCommandHistory 오버로드 - 날짜 범위로 직접 조회 */
    public Map<String, long[]> summarizeCommandHistory(LocalDateTime from, LocalDateTime to) {
        return summarizeCommandHistory(
                commandRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to));
    }

    /**
     * 시간대별 트래픽 합산.
     */
    public Map<String, Long> computeUsageByTimeSlot(List<TrafficLog> logs) {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("morning", 0L);   // 00~12
        totals.put("afternoon", 0L); // 12~18
        totals.put("night", 0L);     // 18~24
        for (TrafficLog log : logs) {
            if (log.getTimestamp() == null) continue;
            int hour = log.getTimestamp().getHour();
            long val = (log.getInboundKbps() != null ? log.getInboundKbps() : 0L)
                    + (log.getOutboundKbps() != null ? log.getOutboundKbps() : 0L);
            if (hour < 12) totals.merge("morning", val, Long::sum);
            else if (hour < 18) totals.merge("afternoon", val, Long::sum);
            else totals.merge("night", val, Long::sum);
        }
        return totals;
    }

    /** 특정 MAC의 트래픽 조회 */
    public List<TrafficLog> getTrafficForMacBetween(String mac, LocalDateTime start, LocalDateTime end) {
        return trafficRepository.findByMacAddressAndTimestampBetween(mac, start, end);
    }

    /** 그룹 내 전체 PC 트래픽 조회 */
    public List<TrafficLog> getTrafficForGroupBetween(Long groupId, LocalDateTime start, LocalDateTime end) {
        List<String> macs = pcRepository.findByGroupId(groupId).stream()
                .map(PcMaster::getMacAddress).collect(Collectors.toList());
        if (macs.isEmpty()) return List.of();
        return trafficRepository.findByMacAddressInAndTimestampBetween(macs, start, end);
    }

    // ─── 유틸 ─────────────────────────────────────────────────────────────

    private static String kbToReadable(long kbps) {
        if (kbps >= 1_000_000_000L) return String.format("%.2f PB", kbps / 1_000_000_000.0);
        if (kbps >= 1_000_000L)     return String.format("%.2f TB", kbps / 1_000_000.0);
        if (kbps >= 1_000L)         return String.format("%.2f GB", kbps / 1_000.0);
        return kbps + " MB";
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "-" : v.toString();
    }

    private static Paragraph section(String text) {
        return new Paragraph(text).setFontSize(13).setBold().setMarginTop(10).setMarginBottom(4);
    }

    private static Table twoColTable() {
        return new Table(UnitValue.createPercentArray(new float[]{40, 60}))
                .setWidth(UnitValue.createPercentValue(100));
    }

    private static Cell label(String text) {
        return new Cell().add(new Paragraph(text).setFontSize(10).setBold())
                .setBackgroundColor(new DeviceRgb(0xec, 0xf0, 0xf1));
    }

    private static Cell value(String text) {
        return new Cell().add(new Paragraph(text).setFontSize(10));
    }

    private static Cell cell(String text) {
        return new Cell().add(new Paragraph(text).setFontSize(10));
    }
}
