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
import com.itextpdf.layout.element.Text;
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
            Cell headerCell = new Cell()
                    .add(new Paragraph(title)
                            .setFontSize(22).setBold().setFontColor(ColorConstants.WHITE)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginTop(8)
                            .setMarginBottom(4))
                    .add(new Paragraph("기간: " + period)
                            .setFontSize(11).setFontColor(new DeviceRgb(0xd1, 0xfa, 0xe5))
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginBottom(2))
                    .add(new Paragraph("생성: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                            .setFontSize(10).setFontColor(new DeviceRgb(0xe5, 0xe7, 0xeb))
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginBottom(8))
                    .setBackgroundColor(new DeviceRgb(0x1f, 0x29, 0x37))
                    .setBorder(null);
            Table header = new Table(UnitValue.createPercentArray(new float[]{100}))
                    .setWidth(UnitValue.createPercentValue(100));
            header.addCell(headerCell);
            doc.add(header);
            doc.add(new Paragraph(" ").setMarginBottom(8));

            // ── 1. 요약 ───────────────────────────────────────────────────
            List<PcMaster> allPcs = pcRepository.findAll();
            long totalPc = allPcs.size();
            long onlinePc = allPcs.stream().filter(p -> "ONLINE".equalsIgnoreCase(p.getStatus())).count();

            List<TrafficLog> trafficLogs = trafficRepository.findByTimestampBetween(from, to);
                Map<String, PcMaster> pcByMac = allPcs.stream()
                    .collect(Collectors.toMap(PcMaster::getMacAddress, p -> p, (a, b) -> a));
                Map<Long, String> groupNameById = groupRepository.findAll().stream()
                    .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName, (a, b) -> a));
            long totalInKbps = trafficLogs.stream()
                    .mapToLong(t -> t.getInboundKbps() != null ? t.getInboundKbps() : 0L).sum();
            long totalOutKbps = trafficLogs.stream()
                    .mapToLong(t -> t.getOutboundKbps() != null ? t.getOutboundKbps() : 0L).sum();

            List<CommandQueue> cmdLogs = commandRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
            long cmdTotal = cmdLogs.size();
            long cmdSuccess = cmdLogs.stream().filter(c -> "COMPLETED".equalsIgnoreCase(c.getStatus())).count();
            long cmdFail = cmdLogs.stream().filter(c -> "FAILED".equalsIgnoreCase(c.getStatus())).count();

            double avgUptime = computeAverageUptimePct(from, to);

            doc.add(section("1. 핵심 요약"));
            Table kpiCards = new Table(UnitValue.createPercentArray(new float[]{33, 33, 34}))
                    .setWidth(UnitValue.createPercentValue(100));
            kpiCards.addCell(card("총 PC", totalPc + "대", new DeviceRgb(0x1d, 0x4e, 0x89)));
            kpiCards.addCell(card("현재 온라인", onlinePc + "대", new DeviceRgb(0x16, 0xa3, 0x4a)));
            kpiCards.addCell(card("평균 가동률", String.format("%.1f%%", avgUptime), new DeviceRgb(0xea, 0x58, 0x0c)));
            doc.add(kpiCards);

                Table sumTbl = twoColTable();
                sumTbl.addCell(label("총 인바운드 트래픽")); sumTbl.addCell(value(kbToReadable(totalInKbps)));
                sumTbl.addCell(label("총 아웃바운드 트래픽")); sumTbl.addCell(value(kbToReadable(totalOutKbps)));
                sumTbl.addCell(label("명령 실행 건수")); sumTbl.addCell(value(cmdTotal + "건 (성공 " + cmdSuccess + " / 실패 " + cmdFail + ")"));
                doc.add(sumTbl.setMarginTop(8));
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

                // ── 4. 수업시간대 사용 분석 ─────────────────────────────────
                doc.add(section("4. 수업시간대 사용 분석"));
                Map<String, Long> bySlot = computeUsageByTimeSlot(trafficLogs);
                List<Map<String, Object>> slotRows = buildClassTimeUsageRows(trafficLogs, pcByMac, groupNameById);
                Table slotTbl = new Table(UnitValue.createPercentArray(new float[]{17, 16, 20, 14, 19, 14}))
                        .setWidth(UnitValue.createPercentValue(100));
                for (String h : new String[]{"구분", "시간", "트래픽 합계", "활성 PC", "최다 사용 강의실", "강의실 트래픽"}) {
                    slotTbl.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(10)));
                }
                for (Map<String, Object> row : slotRows) {
                    slotTbl.addCell(cell(str(row, "slotName")));
                    slotTbl.addCell(cell(str(row, "timeRange")));
                    slotTbl.addCell(cell(kbToReadable(asLong(row.get("totalTraffic")))));
                    slotTbl.addCell(cell(str(row, "activePcCount") + "대"));
                    slotTbl.addCell(cell(str(row, "topClassroom")));
                    slotTbl.addCell(cell(kbToReadable(asLong(row.get("topClassroomTraffic")))));
                }
                doc.add(slotTbl);
                doc.add(new Paragraph("* 활성 PC: 해당 시간대에 트래픽 로그가 1회 이상 기록된 PC 수").setFontSize(9));
                doc.add(new Paragraph("\n"));

                // ── 5. 강의실 트래픽 TOP 5 ─────────────────────────────────
                doc.add(section("5. 강의실 트래픽 TOP 5"));
                List<Map<String, Object>> topClassrooms = computeTopClassroomsByTraffic(trafficLogs, pcByMac, groupNameById, 5);
                if (topClassrooms.isEmpty()) {
                    doc.add(new Paragraph("  (강의실 트래픽 데이터 없음)").setFontSize(10));
                } else {
                    Table topTbl = new Table(UnitValue.createPercentArray(new float[]{10, 40, 25, 25}))
                            .setWidth(UnitValue.createPercentValue(100));
                    for (String h : new String[]{"순위", "강의실", "트래픽 합계", "점유율"}) {
                        topTbl.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(10)));
                    }
                    long totalTraffic = Math.max(1L, trafficLogs.stream()
                            .mapToLong(l -> (l.getInboundKbps() != null ? l.getInboundKbps() : 0L)
                                    + (l.getOutboundKbps() != null ? l.getOutboundKbps() : 0L)).sum());
                    int rank = 1;
                    for (Map<String, Object> row : topClassrooms) {
                        long value = asLong(row.get("traffic"));
                        double share = (double) value / totalTraffic * 100.0;
                        topTbl.addCell(cell(String.valueOf(rank++)));
                        topTbl.addCell(cell(str(row, "groupName")));
                        topTbl.addCell(cell(kbToReadable(value)));
                        topTbl.addCell(cell(String.format("%.1f%%", share)));
                    }
                    doc.add(topTbl);
                }
                doc.add(new Paragraph("\n"));

                // ── 6. 명령 실행 이력 요약 ────────────────────────────────────
                doc.add(section("6. 명령 실행 이력 요약"));
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

                // ── 7. 운영 인사이트 ────────────────────────────────────────
            doc.add(new Paragraph("\n"));
                doc.add(section("7. 운영 인사이트"));
            double failRate = cmdTotal == 0 ? 0.0 : (double) cmdFail / cmdTotal * 100.0;
            String peakSlot = peakSlotLabel(bySlot);
                String peakClass = topClassrooms.isEmpty() ? "-" : str(topClassrooms.get(0), "groupName");
            Paragraph insight = new Paragraph()
                    .add(new Text("• 피크 사용 시간대: ").setBold())
                    .add(peakSlot + "\n")
                    .add(new Text("• 트래픽 상위 강의실: ").setBold())
                    .add(peakClass + "\n")
                    .add(new Text("• 명령 실패율: ").setBold())
                    .add(String.format("%.1f%%", failRate) + "\n")
                    .add(new Text("• 점검 권고: ").setBold())
                    .add(failRate >= 10.0
                            ? "명령 실패율이 높아 에이전트 연결/정책 점검이 필요합니다."
                            : "명령 실패율이 안정적입니다. 현재 운영 상태는 양호합니다.");
            insight.setFontSize(11);
            doc.add(insight);

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

        // N+1 방지: PC 목록과 트래픽 로그를 한 번에 로드 후 메모리에서 분류
        Map<Long, List<PcMaster>> pcsByGroup = pcRepository.findAll().stream()
                .filter(pc -> pc.getGroupId() != null)
                .collect(Collectors.groupingBy(PcMaster::getGroupId));
        Map<String, List<TrafficLog>> logsByMac = trafficRepository.findByTimestampBetween(from, to)
                .stream()
                .collect(Collectors.groupingBy(TrafficLog::getMacAddress));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ClassroomGroup g : groups) {
            List<PcMaster> pcs = pcsByGroup.getOrDefault(g.getId(), List.of());
            if (pcs.isEmpty()) continue;

            long totalOnlineAccum = 0;
            int anomalyCount = 0;
            for (PcMaster pc : pcs) {
                List<TrafficLog> logs = logsByMac.getOrDefault(pc.getMacAddress(), List.of());
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
        if (kbps >= 1_000_000_000L) return String.format("%.2f TB", kbps / 1_000_000_000.0);
        if (kbps >= 1_000_000L)     return String.format("%.2f GB", kbps / 1_000_000.0);
        if (kbps >= 1_000L)         return String.format("%.2f MB", kbps / 1_000.0);
        return kbps + " KB";
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

    private static Cell card(String title, String value, DeviceRgb accent) {
        Paragraph p = new Paragraph()
                .add(new Text(title + "\n").setFontSize(10).setFontColor(new DeviceRgb(0x33, 0x41, 0x55)))
                .add(new Text(value).setBold().setFontSize(18).setFontColor(accent));
        return new Cell()
                .add(p)
                .setBackgroundColor(new DeviceRgb(0xf8, 0xfa, 0xfc))
                .setPadding(10);
    }

    private static String peakSlotLabel(Map<String, Long> bySlot) {
        String bestKey = bySlot.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("morning");
        return switch (bestKey) {
            case "afternoon" -> "오후(12~18시)";
            case "night" -> "야간(18~24시)";
            default -> "오전(00~12시)";
        };
    }

    private List<Map<String, Object>> buildClassTimeUsageRows(
            List<TrafficLog> logs,
            Map<String, PcMaster> pcByMac,
            Map<Long, String> groupNameById) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(buildSingleClassTimeRow("morning", "오전 수업", "08:00~12:00", logs, pcByMac, groupNameById));
        rows.add(buildSingleClassTimeRow("afternoon", "오후 수업", "12:00~18:00", logs, pcByMac, groupNameById));
        rows.add(buildSingleClassTimeRow("night", "야간 수업", "18:00~22:00", logs, pcByMac, groupNameById));
        return rows;
    }

    private Map<String, Object> buildSingleClassTimeRow(
            String slotKey,
            String slotName,
            String timeRange,
            List<TrafficLog> logs,
            Map<String, PcMaster> pcByMac,
            Map<Long, String> groupNameById) {
        Set<String> activeMacs = new HashSet<>();
        Map<String, Long> trafficByClass = new HashMap<>();
        long total = 0L;

        for (TrafficLog log : logs) {
            if (log.getTimestamp() == null) continue;
            if (!slotKey.equals(resolveSlot(log.getTimestamp().getHour()))) continue;

            long value = (log.getInboundKbps() != null ? log.getInboundKbps() : 0L)
                    + (log.getOutboundKbps() != null ? log.getOutboundKbps() : 0L);
            total += value;
            if (log.getMacAddress() != null) {
                activeMacs.add(log.getMacAddress());
            }

            String groupName = "미분류";
            PcMaster pc = log.getMacAddress() == null ? null : pcByMac.get(log.getMacAddress());
            if (pc != null && pc.getGroupId() != null) {
                groupName = groupNameById.getOrDefault(pc.getGroupId(), "강의실#" + pc.getGroupId());
            }
            trafficByClass.merge(groupName, value, Long::sum);
        }

        Map.Entry<String, Long> top = trafficByClass.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(Map.entry("-", 0L));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slotName", slotName);
        row.put("timeRange", timeRange);
        row.put("totalTraffic", total);
        row.put("activePcCount", activeMacs.size());
        row.put("topClassroom", top.getKey());
        row.put("topClassroomTraffic", top.getValue());
        return row;
    }

    private List<Map<String, Object>> computeTopClassroomsByTraffic(
            List<TrafficLog> logs,
            Map<String, PcMaster> pcByMac,
            Map<Long, String> groupNameById,
            int limit) {
        Map<String, Long> byClass = new HashMap<>();
        for (TrafficLog log : logs) {
            if (log.getTimestamp() == null) continue;
            long value = (log.getInboundKbps() != null ? log.getInboundKbps() : 0L)
                    + (log.getOutboundKbps() != null ? log.getOutboundKbps() : 0L);
            String groupName = "미분류";
            PcMaster pc = log.getMacAddress() == null ? null : pcByMac.get(log.getMacAddress());
            if (pc != null && pc.getGroupId() != null) {
                groupName = groupNameById.getOrDefault(pc.getGroupId(), "강의실#" + pc.getGroupId());
            }
            byClass.merge(groupName, value, Long::sum);
        }
        return byClass.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("groupName", e.getKey());
                    m.put("traffic", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private static String resolveSlot(int hour) {
        if (hour >= 8 && hour < 12) return "morning";
        if (hour >= 12 && hour < 18) return "afternoon";
        if (hour >= 18 && hour < 22) return "night";
        return "night";
    }

    private static long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
