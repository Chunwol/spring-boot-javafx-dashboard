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
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    // ── 앱 브랜드 컬러 (CSS 변수와 동일)
    private static final DeviceRgb C_PRIMARY      = new DeviceRgb(0x25, 0x63, 0xeb); // #2563eb
    private static final DeviceRgb C_PRIMARY_DARK = new DeviceRgb(0x1e, 0x40, 0xaf); // #1e40af
    private static final DeviceRgb C_SUCCESS      = new DeviceRgb(0x16, 0xa3, 0x4a); // #16a34a
    private static final DeviceRgb C_WARNING      = new DeviceRgb(0xd9, 0x77, 0x06); // #d97706
    private static final DeviceRgb C_DANGER       = new DeviceRgb(0xdc, 0x26, 0x26); // #dc2626
    private static final DeviceRgb C_NAVY         = new DeviceRgb(0x0f, 0x17, 0x2a); // #0f172a
    private static final DeviceRgb C_SLATE        = new DeviceRgb(0x47, 0x55, 0x69); // #475569
    private static final DeviceRgb C_MUTED        = new DeviceRgb(0x94, 0xa3, 0xb8); // #94a3b8
    private static final DeviceRgb C_BG_LIGHT     = new DeviceRgb(0xf8, 0xfa, 0xfc); // #f8fafc
    private static final DeviceRgb C_BORDER       = new DeviceRgb(0xe2, 0xe8, 0xf0); // #e2e8f0
    private static final DeviceRgb C_ROW_ALT      = new DeviceRgb(0xf1, 0xf5, 0xf9); // #f1f5f9

    private final PcMasterRepository pcRepository;
    private final TrafficLogsRepository trafficRepository;
    private final ClassroomGroupRepository groupRepository;
    private final CommandQueueRepository commandRepository;

    @Value("${spring.jpa.properties.hibernate.dialect:org.hibernate.dialect.PostgreSQLDialect}")
    private String hibernateDialect;

    public record TrafficSummary(long totalInboundKbps,
                                 long totalOutboundKbps,
                                 Map<LocalDate, Long> dailyInbound,
                                 Map<String, Long> byTimeSlot) {
    }

    public ReportService(PcMasterRepository pcRepository,
                         TrafficLogsRepository trafficRepository,
                         ClassroomGroupRepository groupRepository,
                         CommandQueueRepository commandRepository) {
        this.pcRepository = pcRepository;
        this.trafficRepository = trafficRepository;
        this.groupRepository = groupRepository;
        this.commandRepository = commandRepository;
    }

    // ─── PDF 생성 진입점 ──────────────────────────────────────────────────────

    public byte[] generatePdfReport(LocalDateTime from, LocalDateTime to, String type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);

            // ── 한국어 폰트 로드 (Malgun Gothic → 시스템 폰트 순 탐색) ──────────
            PdfFont fontRegular = loadKoreanFont(false);
            PdfFont fontBold    = loadKoreanFont(true);

            // A4 여백 설정
            Document doc = new Document(pdf);
            doc.setMargins(55, 50, 65, 50);
            doc.setFont(fontRegular);

            // ── 페이지 이벤트 핸들러 등록 (푸터) ────────────────────────────────
            String generatedAt = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE,
                    new FooterHandler(fontRegular, generatedAt));

            // ── 데이터 수집 ──────────────────────────────────────────────────────
            String title = "WEEKLY".equalsIgnoreCase(type)
                    ? "스마트 강의실 주간 운영 보고서"
                    : "스마트 강의실 월간 운영 보고서";
            String periodStr = from.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                    + " – " + to.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));

            List<PcMaster> allPcs = pcRepository.findAll();
            long totalPc   = allPcs.size();
            long onlinePc  = allPcs.stream().filter(p -> "ONLINE".equalsIgnoreCase(p.getStatus())).count();

            List<TrafficLog> trafficLogs = trafficRepository.findByTimestampBetween(from, to);
            Map<String, PcMaster> pcByMac = allPcs.stream()
                    .collect(Collectors.toMap(PcMaster::getMacAddress, p -> p, (a, b) -> a));
            Map<Long, String> groupNameById = groupRepository.findAll().stream()
                    .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName, (a, b) -> a));

            long totalInKbps  = trafficLogs.stream().mapToLong(t -> t.getInboundKbps()  != null ? t.getInboundKbps()  : 0L).sum();
            long totalOutKbps = trafficLogs.stream().mapToLong(t -> t.getOutboundKbps() != null ? t.getOutboundKbps() : 0L).sum();

            List<CommandQueue> cmdLogs = commandRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
            long cmdTotal   = cmdLogs.size();
            long cmdSuccess = cmdLogs.stream().filter(c -> "COMPLETED".equalsIgnoreCase(c.getStatus())).count();
            long cmdFail    = cmdLogs.stream().filter(c -> "FAILED".equalsIgnoreCase(c.getStatus())).count();
            double failRate = cmdTotal == 0 ? 0.0 : (double) cmdFail / cmdTotal * 100.0;
            double avgUptime = computeAverageUptimePct(from, to);

            List<Map<String, Object>> uptimeRows    = computeUptimeByGroup(from, to);
            List<Map<String, Object>> topClassrooms = computeTopClassroomsByTraffic(trafficLogs, pcByMac, groupNameById, 5);
            Map<LocalDate, Long>      dailyIn       = dailyInboundAggregation(trafficLogs, from, to);
            Map<String, Long>         bySlot        = computeUsageByTimeSlot(trafficLogs);
            List<Map<String, Object>> slotRows      = buildClassTimeUsageRows(trafficLogs, pcByMac, groupNameById);
            Map<String, long[]>       cmdSummary    = summarizeCommandHistory(cmdLogs);

            // ══════════════════════════════════════════════════════════════════
            // 표지
            // ══════════════════════════════════════════════════════════════════
            addCoverPage(doc, pdf, fontRegular, fontBold, title, periodStr, generatedAt, type);

            // ══════════════════════════════════════════════════════════════════
            // 1. 핵심 KPI
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "1", "핵심 지표 요약"));

            // KPI 카드 3개 한 줄
            Table kpiRow = new Table(UnitValue.createPercentArray(new float[]{33.3f, 33.3f, 33.4f}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6);
            kpiRow.addCell(kpiCard(fontRegular, fontBold, "총 등록 PC",   totalPc + "대",              C_PRIMARY));
            kpiRow.addCell(kpiCard(fontRegular, fontBold, "현재 온라인", onlinePc + "대",              C_SUCCESS));
            kpiRow.addCell(kpiCard(fontRegular, fontBold, "평균 가동률",  String.format("%.1f%%", avgUptime), C_WARNING));
            doc.add(kpiRow);

            // KPI 카드 두 번째 줄
            Table kpiRow2 = new Table(UnitValue.createPercentArray(new float[]{50f, 50f}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(14);
            kpiRow2.addCell(kpiCard(fontRegular, fontBold, "명령 실행 건수",
                    cmdTotal + "건  (성공 " + cmdSuccess + " / 실패 " + cmdFail + ")",
                    failRate >= 10 ? C_DANGER : C_SLATE));
            kpiRow2.addCell(kpiCard(fontRegular, fontBold, "총 네트워크 트래픽",
                    "↓ " + kbToReadable(totalInKbps) + "   ↑ " + kbToReadable(totalOutKbps),
                    C_PRIMARY));
            doc.add(kpiRow2);

            // ══════════════════════════════════════════════════════════════════
            // 2. 강의실별 가동률
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "2", "강의실별 가동률"));
            if (uptimeRows.isEmpty()) {
                doc.add(emptyNote(fontRegular, "강의실 데이터가 없습니다."));
            } else {
                String[] headers2 = {"강의실", "등록 PC", "평균 온라인 횟수", "가동률", "이상 탐지"};
                float[]  widths2  = {30, 12, 20, 14, 14};
                Table uptimeTbl = dataTable(headers2, widths2, fontRegular, fontBold);
                for (int i = 0; i < uptimeRows.size(); i++) {
                    Map<String, Object> row = uptimeRows.get(i);
                    DeviceRgb rowBg = (i % 2 == 1) ? C_ROW_ALT : null;
                    double upt = 0.0;
                    try { upt = Double.parseDouble(str(row, "uptimePct")); } catch (Exception ignored) {}
                    uptimeTbl.addCell(dataCell(fontRegular, str(row, "groupName"), rowBg, TextAlignment.LEFT));
                    uptimeTbl.addCell(dataCell(fontRegular, str(row, "totalPc") + "대", rowBg, TextAlignment.CENTER));
                    uptimeTbl.addCell(dataCell(fontRegular, str(row, "avgOnline") + "회", rowBg, TextAlignment.CENTER));
                    uptimeTbl.addCell(dataCellColored(fontBold, str(row, "uptimePct") + "%", rowBg,
                            upt >= 80 ? C_SUCCESS : (upt >= 50 ? C_WARNING : C_DANGER)));
                    int anomaly = 0;
                    try { anomaly = Integer.parseInt(str(row, "anomalyCount")); } catch (Exception ignored) {}
                    uptimeTbl.addCell(dataCellColored(fontRegular, anomaly + "건", rowBg,
                            anomaly > 0 ? C_DANGER : C_SUCCESS));
                }
                doc.add(uptimeTbl);
            }
            doc.add(spacer());

            // ══════════════════════════════════════════════════════════════════
            // 3. 일별 인바운드 트래픽
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "3", "일별 인바운드 트래픽"));
            if (dailyIn.isEmpty()) {
                doc.add(emptyNote(fontRegular, "해당 기간의 트래픽 데이터가 없습니다."));
            } else {
                String[] hdrs3 = {"날짜", "인바운드 트래픽", "비율 (막대)"};
                float[] wds3 = {18, 22, 60};
                Table trafficTbl = dataTable(hdrs3, wds3, fontRegular, fontBold);
                long maxVal = dailyIn.values().stream().mapToLong(Long::longValue).max().orElse(1L);
                int rowIdx = 0;
                for (Map.Entry<LocalDate, Long> e : new TreeMap<>(dailyIn).entrySet()) {
                    DeviceRgb rowBg = (rowIdx++ % 2 == 1) ? C_ROW_ALT : null;
                    trafficTbl.addCell(dataCell(fontRegular, e.getKey().toString(), rowBg, TextAlignment.LEFT));
                    trafficTbl.addCell(dataCell(fontRegular, kbToReadable(e.getValue()), rowBg, TextAlignment.LEFT));

                    // 인라인 바 그래프 (테이블 셀 내부)
                    float pct = Math.max(0.01f, (float) e.getValue() / maxVal);
                    Table bar = new Table(UnitValue.createPercentArray(new float[]{pct * 100f, (1f - pct) * 100f + 0.01f}))
                            .setWidth(UnitValue.createPercentValue(100));
                    bar.addCell(new Cell().setHeight(10).setBackgroundColor(C_PRIMARY).setBorder(Border.NO_BORDER));
                    bar.addCell(new Cell().setHeight(10).setBorder(Border.NO_BORDER));
                    Cell barCell = new Cell().add(bar).setBorder(Border.NO_BORDER);
                    if (rowBg != null) barCell.setBackgroundColor(rowBg);
                    trafficTbl.addCell(barCell);
                }
                doc.add(trafficTbl);
            }
            doc.add(spacer());

            // ══════════════════════════════════════════════════════════════════
            // 4. 수업 시간대별 사용 분석
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "4", "수업 시간대별 사용 분석"));
            String[] hdrs4 = {"구분", "시간", "트래픽 합계", "활성 PC", "최다 사용 강의실", "강의실 트래픽"};
            float[]  wds4  = {13, 14, 17, 12, 26, 18};
            Table slotTbl = dataTable(hdrs4, wds4, fontRegular, fontBold);
            for (int i = 0; i < slotRows.size(); i++) {
                Map<String, Object> row = slotRows.get(i);
                DeviceRgb rowBg = (i % 2 == 1) ? C_ROW_ALT : null;
                slotTbl.addCell(dataCell(fontBold,    str(row, "slotName"),   rowBg, TextAlignment.LEFT));
                slotTbl.addCell(dataCell(fontRegular, str(row, "timeRange"),   rowBg, TextAlignment.CENTER));
                slotTbl.addCell(dataCell(fontRegular, kbToReadable(asLong(row.get("totalTraffic"))), rowBg, TextAlignment.RIGHT));
                slotTbl.addCell(dataCell(fontRegular, str(row, "activePcCount") + "대", rowBg, TextAlignment.CENTER));
                slotTbl.addCell(dataCell(fontRegular, str(row, "topClassroom"), rowBg, TextAlignment.LEFT));
                slotTbl.addCell(dataCell(fontRegular, kbToReadable(asLong(row.get("topClassroomTraffic"))), rowBg, TextAlignment.RIGHT));
            }
            doc.add(slotTbl);
            doc.add(noteText(fontRegular, "* 활성 PC: 해당 시간대에 트래픽 로그가 1회 이상 기록된 PC 수"));
            doc.add(spacer());

            // ══════════════════════════════════════════════════════════════════
            // 5. 강의실 트래픽 TOP 5
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "5", "강의실 트래픽 TOP 5"));
            if (topClassrooms.isEmpty()) {
                doc.add(emptyNote(fontRegular, "강의실 트래픽 데이터가 없습니다."));
            } else {
                long totalTraffic = Math.max(1L, trafficLogs.stream()
                        .mapToLong(l -> (l.getInboundKbps() != null ? l.getInboundKbps() : 0L)
                                      + (l.getOutboundKbps() != null ? l.getOutboundKbps() : 0L)).sum());
                String[] hdrs5 = {"순위", "강의실", "트래픽 합계", "점유율", "점유율 (막대)"};
                float[] wds5 = {8, 28, 20, 14, 30};
                Table topTbl = dataTable(hdrs5, wds5, fontRegular, fontBold);
                int rank = 1;
                for (Map<String, Object> row : topClassrooms) {
                    long val = asLong(row.get("traffic"));
                    double share = (double) val / totalTraffic * 100.0;
                    float sharePct = (float) share / 100f;
                    DeviceRgb rowBg = (rank % 2 == 0) ? C_ROW_ALT : null;
                    // 순위 번호에 메달 표시
                    String rankLabel = rank == 1 ? "1위" : rank == 2 ? "2위" : rank == 3 ? "3위" : rank + "위";
                    Cell rankCell = new Cell().add(new Paragraph(rankLabel).setFont(fontBold).setFontSize(10)
                            .setFontColor(rank <= 3 ? C_PRIMARY : C_SLATE).setTextAlignment(TextAlignment.CENTER))
                            .setBorder(Border.NO_BORDER)
                            .setBorderBottom(new SolidBorder(C_BORDER, 0.5f));
                    if (rowBg != null) rankCell.setBackgroundColor(rowBg);
                    topTbl.addCell(rankCell);
                    topTbl.addCell(dataCell(fontBold,    str(row, "groupName"), rowBg, TextAlignment.LEFT));
                    topTbl.addCell(dataCell(fontRegular, kbToReadable(val), rowBg, TextAlignment.RIGHT));
                    topTbl.addCell(dataCellColored(fontBold, String.format("%.1f%%", share), rowBg, C_PRIMARY));

                    // 점유율 바
                    Table sBar = new Table(UnitValue.createPercentArray(
                            new float[]{Math.max(0.01f, sharePct * 100f), Math.max(0.01f, (1f - sharePct) * 100f)}))
                            .setWidth(UnitValue.createPercentValue(100));
                    sBar.addCell(new Cell().setHeight(10).setBackgroundColor(C_PRIMARY).setBorder(Border.NO_BORDER));
                    sBar.addCell(new Cell().setHeight(10).setBorder(Border.NO_BORDER));
                    Cell sBarCell = new Cell().add(sBar).setBorder(Border.NO_BORDER)
                            .setBorderBottom(new SolidBorder(C_BORDER, 0.5f));
                    if (rowBg != null) sBarCell.setBackgroundColor(rowBg);
                    topTbl.addCell(sBarCell);
                    rank++;
                }
                doc.add(topTbl);
            }
            doc.add(spacer());

            // ══════════════════════════════════════════════════════════════════
            // 6. 명령 실행 이력 요약
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "6", "명령 실행 이력 요약"));
            if (cmdSummary.isEmpty()) {
                doc.add(emptyNote(fontRegular, "해당 기간의 명령 이력이 없습니다."));
            } else {
                String[] hdrs6 = {"명령 유형", "총 건수", "완료", "실패", "진행 중"};
                float[] wds6 = {32, 17, 17, 17, 17};
                Table cmdTbl = dataTable(hdrs6, wds6, fontRegular, fontBold);
                int i = 0;
                for (Map.Entry<String, long[]> e : cmdSummary.entrySet()) {
                    long[] v = e.getValue();
                    DeviceRgb rowBg = (i++ % 2 == 1) ? C_ROW_ALT : null;
                    cmdTbl.addCell(dataCell(fontBold, e.getKey(), rowBg, TextAlignment.LEFT));
                    cmdTbl.addCell(dataCell(fontRegular, String.valueOf(v[0]), rowBg, TextAlignment.CENTER));
                    cmdTbl.addCell(dataCellColored(fontRegular, String.valueOf(v[1]), rowBg, C_SUCCESS));
                    cmdTbl.addCell(dataCellColored(fontBold, String.valueOf(v[2]), rowBg, v[2] > 0 ? C_DANGER : C_SUCCESS));
                    cmdTbl.addCell(dataCell(fontRegular, String.valueOf(v[3]), rowBg, TextAlignment.CENTER));
                }
                doc.add(cmdTbl);
            }
            doc.add(spacer());

            // ══════════════════════════════════════════════════════════════════
            // 7. 운영 인사이트 & 권고사항
            // ══════════════════════════════════════════════════════════════════
            doc.add(sectionHeader(fontBold, "7", "운영 인사이트 및 권고사항"));
            String peakSlot  = peakSlotLabel(bySlot);
            String peakClass = topClassrooms.isEmpty() ? "-" : str(topClassrooms.get(0), "groupName");

            Table insightTbl = new Table(UnitValue.createPercentArray(new float[]{40, 60}))
                    .setWidth(UnitValue.createPercentValue(100));

            DeviceRgb overallStatus = (failRate >= 10 || avgUptime < 50) ? C_DANGER
                    : (failRate >= 5 || avgUptime < 80) ? C_WARNING : C_SUCCESS;
            String overallLabel = overallStatus.equals(C_DANGER) ? "점검 필요"
                    : overallStatus.equals(C_WARNING) ? "주의 필요" : "양호";

            insightTbl.addCell(insightRow(fontBold, fontRegular, "전체 운영 상태",   overallLabel, overallStatus));
            insightTbl.addCell(insightRow(fontBold, fontRegular, "피크 사용 시간대", peakSlot,    C_SLATE));
            insightTbl.addCell(insightRow(fontBold, fontRegular, "트래픽 상위 강의실", peakClass, C_SLATE));
            insightTbl.addCell(insightRow(fontBold, fontRegular, "명령 실패율",
                    String.format("%.1f%%", failRate), failRate >= 10 ? C_DANGER : failRate >= 5 ? C_WARNING : C_SUCCESS));
            insightTbl.addCell(insightRow(fontBold, fontRegular, "평균 가동률",
                    String.format("%.1f%%", avgUptime), avgUptime >= 80 ? C_SUCCESS : avgUptime >= 50 ? C_WARNING : C_DANGER));
            insightTbl.addCell(insightRow(fontBold, fontRegular, "점검 권고",
                    failRate >= 10 ? "명령 실패율이 높습니다. 에이전트 연결 상태 및 배포 정책을 점검하세요."
                        : avgUptime < 50 ? "가동률이 낮습니다. 오프라인 PC의 네트워크/전원 상태를 확인하세요."
                        : "현재 운영 상태가 안정적입니다. 정기 유지보수 스케줄을 확인하세요.",
                    C_SLATE));
            doc.add(insightTbl);

            doc.close();

        } catch (Exception e) {
            log.error("PDF 보고서 생성 실패: {}", e.getMessage(), e);
            return new byte[0];
        }
        return out.toByteArray();
    }

    // ─── 표지 ─────────────────────────────────────────────────────────────────

    private static void addCoverPage(Document doc, PdfDocument pdf,
                                     PdfFont fontRegular, PdfFont fontBold,
                                     String title, String period,
                                     String generatedAt, String type) {
        // ── 상단 파란 헤더 배너
        Table banner = new Table(UnitValue.createPercentArray(new float[]{100}))
                .setWidth(UnitValue.createPercentValue(100));
        Cell bannerCell = new Cell()
                .add(new Paragraph("스마트 강의실 관제 시스템")
                        .setFont(fontRegular).setFontSize(11)
                        .setFontColor(new DeviceRgb(0xa5, 0xb4, 0xfc))
                        .setTextAlignment(TextAlignment.CENTER).setMarginTop(18).setMarginBottom(4))
                .add(new Paragraph(title)
                        .setFont(fontBold).setFontSize(26)
                        .setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8))
                .add(new Paragraph(period)
                        .setFont(fontRegular).setFontSize(12)
                        .setFontColor(new DeviceRgb(0xba, 0xc8, 0xff))
                        .setTextAlignment(TextAlignment.CENTER).setMarginBottom(18))
                .setBackgroundColor(C_PRIMARY_DARK)
                .setBorder(Border.NO_BORDER)
                .setPadding(0);
        banner.addCell(bannerCell);
        doc.add(banner);

        // ── 생성 정보 박스
        doc.add(new Paragraph(" ").setMarginBottom(6));
        Table infoBox = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(20);
        infoBox.addCell(new Cell()
                .add(new Paragraph("보고서 종류").setFont(fontBold).setFontSize(9).setFontColor(C_MUTED))
                .add(new Paragraph(("WEEKLY".equalsIgnoreCase(type) ? "주간 보고서" : "월간 보고서"))
                        .setFont(fontBold).setFontSize(13).setFontColor(C_NAVY))
                .setBackgroundColor(C_BG_LIGHT).setBorder(new SolidBorder(C_BORDER, 1)).setPadding(12));
        infoBox.addCell(new Cell()
                .add(new Paragraph("생성 일시").setFont(fontBold).setFontSize(9).setFontColor(C_MUTED))
                .add(new Paragraph(generatedAt).setFont(fontBold).setFontSize(13).setFontColor(C_NAVY))
                .setBackgroundColor(C_BG_LIGHT).setBorder(new SolidBorder(C_BORDER, 1)).setPadding(12));
        doc.add(infoBox);

        // ── 수평 구분선
        doc.add(new Table(UnitValue.createPercentArray(new float[]{100}))
                .setWidth(UnitValue.createPercentValue(100))
                .addCell(new Cell().setHeight(3).setBackgroundColor(C_PRIMARY).setBorder(Border.NO_BORDER)));
        doc.add(new Paragraph(" ").setMarginBottom(10));
    }

    // ─── 레이아웃 헬퍼 ───────────────────────────────────────────────────────

    /** 섹션 헤더: 파란 좌측 바 + 번호 + 제목 */
    private static Table sectionHeader(PdfFont fontBold, String number, String text) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{2, 98}))
                .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6).setMarginTop(4);
        t.addCell(new Cell().setBackgroundColor(C_PRIMARY).setBorder(Border.NO_BORDER).setHeight(24));
        t.addCell(new Cell()
                .add(new Paragraph(number + ".  " + text)
                        .setFont(fontBold).setFontSize(13).setFontColor(C_NAVY)
                        .setMarginLeft(8).setMarginTop(2))
                .setBackgroundColor(C_BG_LIGHT)
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(C_BORDER, 1)));
        return t;
    }

    /** KPI 카드: 흰 배경 + 상단 컬러 바 + 수치 크게 */
    private static Cell kpiCard(PdfFont fontRegular, PdfFont fontBold,
                                String label, String value, DeviceRgb accent) {
        Table inner = new Table(UnitValue.createPercentArray(new float[]{100}))
                .setWidth(UnitValue.createPercentValue(100));
        inner.addCell(new Cell().setHeight(4).setBackgroundColor(accent).setBorder(Border.NO_BORDER));
        inner.addCell(new Cell()
                .add(new Paragraph(label).setFont(fontRegular).setFontSize(9).setFontColor(C_MUTED).setMarginBottom(4))
                .add(new Paragraph(value).setFont(fontBold).setFontSize(16).setFontColor(C_NAVY))
                .setBackgroundColor(C_BG_LIGHT).setBorder(Border.NO_BORDER).setPadding(10));
        return new Cell().add(inner)
                .setBorder(new SolidBorder(C_BORDER, 1))
                .setPadding(0).setMargin(2);
    }

    /** 데이터 테이블: 컬러 헤더 + 교대 행 */
    private static Table dataTable(String[] headers, float[] widths,
                                   PdfFont fontRegular, PdfFont fontBold) {
        Table tbl = new Table(UnitValue.createPercentArray(widths))
                .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(4);
        for (String h : headers) {
            tbl.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(fontBold).setFontSize(9.5f)
                            .setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.CENTER))
                    .setBackgroundColor(C_PRIMARY_DARK)
                    .setBorder(Border.NO_BORDER)
                    .setPadding(6));
        }
        return tbl;
    }

    private static Cell dataCell(PdfFont font, String text, DeviceRgb bg, TextAlignment align) {
        Cell c = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(9.5f)
                        .setFontColor(C_NAVY).setTextAlignment(align))
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(C_BORDER, 0.5f))
                .setPadding(5);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private static Cell dataCellColored(PdfFont font, String text, DeviceRgb bg, DeviceRgb textColor) {
        Cell c = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(9.5f)
                        .setFontColor(textColor).setTextAlignment(TextAlignment.CENTER))
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(C_BORDER, 0.5f))
                .setPadding(5);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    /** 인사이트 테이블 행: 2컬럼 레이블-값 */
    private static Cell insightRow(PdfFont fontBold, PdfFont fontRegular,
                                   String label, String value, DeviceRgb valueColor) {
        return new Cell()
                .add(new Paragraph()
                        .add(new Text(label + "  ").setFont(fontBold).setFontSize(10).setFontColor(C_SLATE))
                        .add(new Text(value).setFont(fontBold).setFontSize(10).setFontColor(valueColor)))
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(C_BORDER, 0.5f))
                .setBackgroundColor(C_BG_LIGHT)
                .setPadding(8);
    }

    private static Paragraph emptyNote(PdfFont font, String text) {
        return new Paragraph(text).setFont(font).setFontSize(9).setFontColor(C_MUTED)
                .setMarginLeft(10).setMarginBottom(8);
    }

    private static Paragraph noteText(PdfFont font, String text) {
        return new Paragraph(text).setFont(font).setFontSize(8.5f).setFontColor(C_MUTED)
                .setMarginTop(3).setMarginBottom(4);
    }

    private static Paragraph spacer() {
        return new Paragraph(" ").setFontSize(4).setMarginBottom(4);
    }

    // ─── 한국어 폰트 로드 ────────────────────────────────────────────────────

    private static PdfFont loadKoreanFont(boolean bold) {
        String[] candidates = bold
                ? new String[]{
                    "C:/Windows/Fonts/malgunbd.ttf",
                    "C:/Windows/Fonts/HANBatangB.ttf",
                    "C:/Windows/Fonts/Hancom Gothic Bold.ttf",
                    "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf",
                    "/System/Library/Fonts/AppleSDGothicNeo.ttc"}
                : new String[]{
                    "C:/Windows/Fonts/malgun.ttf",
                    "C:/Windows/Fonts/HANBatang.ttf",
                    "C:/Windows/Fonts/Hancom Gothic Regular.ttf",
                    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
                    "/System/Library/Fonts/AppleSDGothicNeo.ttc"};
        for (String path : candidates) {
            try {
                if (new File(path).exists()) {
                    return PdfFontFactory.createFont(path,
                            com.itextpdf.io.font.PdfEncodings.IDENTITY_H,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                }
            } catch (Exception ignored) {}
        }
        // 폴백: iText 기본 폰트 (한국어는 깨지지만 크래시 방지)
        try { return PdfFontFactory.createFont(); } catch (Exception e) { return null; }
    }

    // ─── 페이지 푸터 이벤트 핸들러 ──────────────────────────────────────────

    private static class FooterHandler implements IEventHandler {
        private final PdfFont font;
        private final String generatedAt;
        FooterHandler(PdfFont font, String generatedAt) {
            this.font = font;
            this.generatedAt = generatedAt;
        }
        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            int pageNum = pdfDoc.getPageNumber(page);
            Rectangle pageSize = page.getPageSize();
            PdfCanvas canvas = new PdfCanvas(page);
            // 하단 구분선
            canvas.setLineWidth(0.5f).setStrokeColor(C_BORDER)
                  .moveTo(50, 42).lineTo(pageSize.getWidth() - 50, 42).stroke();
            float halfW = (pageSize.getWidth() - 100) / 2f;
            // 좌측: 시스템명 + 생성일시
            try (Canvas left = new Canvas(canvas, new Rectangle(50, 24, halfW, 14))) {
                left.add(new Paragraph("스마트 강의실 관제 시스템  |  생성: " + generatedAt)
                        .setFont(font).setFontSize(8).setFontColor(C_MUTED));
            }
            // 우측: 페이지 번호 (별도 Canvas로 같은 줄 우측 정렬)
            try (Canvas right = new Canvas(canvas, new Rectangle(50 + halfW, 24, halfW, 14))) {
                right.add(new Paragraph(pageNum + " 페이지")
                        .setFont(font).setFontSize(8).setFontColor(C_MUTED)
                        .setTextAlignment(TextAlignment.RIGHT));
            }
        }
    }

    // ─── 집계 메서드 (기존과 동일) ───────────────────────────────────────────

    public List<Map<String, Object>> computeUptimeByGroup(LocalDateTime from, LocalDateTime to) {
        List<ClassroomGroup> groups = groupRepository.findAll();
        long periodMinutes = Duration.between(from, to).toMinutes();
        long expectedBeats = Math.max(1, periodMinutes / 2);

        Map<Long, List<PcMaster>> pcsByGroup = pcRepository.findAll().stream()
                .filter(pc -> pc.getGroupId() != null)
                .collect(Collectors.groupingBy(PcMaster::getGroupId));
        Map<String, List<TrafficLog>> logsByMac = trafficRepository.findByTimestampBetween(from, to)
                .stream().collect(Collectors.groupingBy(TrafficLog::getMacAddress));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ClassroomGroup g : groups) {
            List<PcMaster> pcs = pcsByGroup.getOrDefault(g.getId(), List.of());
            if (pcs.isEmpty()) continue;
            long totalOnlineAccum = 0;
            int  anomalyCount     = 0;
            for (PcMaster pc : pcs) {
                List<TrafficLog> logs = logsByMac.getOrDefault(pc.getMacAddress(), List.of());
                totalOnlineAccum += logs.size();
                boolean anomaly = logs.stream().anyMatch(t ->
                        t.getInboundKbps() != null && t.getInboundKbps() > 100_000L);
                if (anomaly) anomalyCount++;
            }
            double avgOnline = (double) totalOnlineAccum / pcs.size();
            double uptimePct = Math.min(100.0, avgOnline / expectedBeats * 100.0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupId",      g.getId());
            row.put("groupName",    g.getName());
            row.put("totalPc",      pcs.size());
            row.put("avgOnline",    String.format("%.1f", avgOnline));
            row.put("uptimePct",    String.format("%.1f", uptimePct));
            row.put("anomalyCount", anomalyCount);
            result.add(row);
        }
        return result;
    }

    public double computeAverageUptimePct(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = computeUptimeByGroup(from, to);
        if (rows.isEmpty()) return 0.0;
        return rows.stream()
                .mapToDouble(r -> { try { return Double.parseDouble(r.get("uptimePct").toString()); } catch (Exception e) { return 0.0; } })
                .average().orElse(0.0);
    }

    public Map<LocalDate, Long> dailyInboundAggregation(List<TrafficLog> logs, LocalDateTime from, LocalDateTime to) {
        return logs.stream()
                .filter(l -> l.getTimestamp() != null)
                .collect(Collectors.groupingBy(l -> l.getTimestamp().toLocalDate(),
                        TreeMap::new, Collectors.summingLong(l -> l.getInboundKbps() != null ? l.getInboundKbps() : 0L)));
    }

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

    public Map<String, long[]> summarizeCommandHistory(LocalDateTime from, LocalDateTime to) {
        return summarizeCommandHistory(commandRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to));
    }

    public Map<String, Long> computeUsageByTimeSlot(List<TrafficLog> logs) {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("morning", 0L); totals.put("afternoon", 0L); totals.put("night", 0L);
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

    /**
     * 웹 트래픽 보고서용 DB 집계. 조회 건수와 무관하게 일별 최대 31행,
     * 시간별 최대 24행만 애플리케이션으로 가져온다.
     */
    public TrafficSummary aggregateTraffic(Long groupId, LocalDateTime start, LocalDateTime end) {
        List<TrafficLogsRepository.DailyTrafficAggregate> dailyRows;
        List<TrafficLogsRepository.HourlyTrafficAggregate> hourlyRows;

        if (groupId == null) {
            if (usesSqliteDialect()) {
                dailyRows = trafficRepository.aggregateDailyBetweenSqlite(start, end);
                hourlyRows = trafficRepository.aggregateHourlyBetweenSqlite(start, end);
            } else {
                dailyRows = trafficRepository.aggregateDailyBetween(start, end);
                hourlyRows = trafficRepository.aggregateHourlyBetween(start, end);
            }
        } else {
            List<String> macAddresses = pcRepository.findByGroupId(groupId).stream()
                    .map(PcMaster::getMacAddress)
                    .filter(Objects::nonNull)
                    .filter(mac -> !mac.isBlank())
                    .distinct()
                    .toList();
            if (macAddresses.isEmpty()) {
                return emptyTrafficSummary();
            }
            if (usesSqliteDialect()) {
                dailyRows = trafficRepository.aggregateDailyForMacAddressesBetweenSqlite(macAddresses, start, end);
                hourlyRows = trafficRepository.aggregateHourlyForMacAddressesBetweenSqlite(macAddresses, start, end);
            } else {
                dailyRows = trafficRepository.aggregateDailyForMacAddressesBetween(macAddresses, start, end);
                hourlyRows = trafficRepository.aggregateHourlyForMacAddressesBetween(macAddresses, start, end);
            }
        }

        Map<LocalDate, Long> dailyInbound = new TreeMap<>();
        long totalInbound = 0L;
        long totalOutbound = 0L;
        for (TrafficLogsRepository.DailyTrafficAggregate row : dailyRows) {
            if (row.getYearPart() == null || row.getMonthPart() == null || row.getDayPart() == null) {
                continue;
            }
            try {
                LocalDate date = LocalDate.of(row.getYearPart(), row.getMonthPart(), row.getDayPart());
                long inbound = nonNullLong(row.getInboundKbps());
                long outbound = nonNullLong(row.getOutboundKbps());
                dailyInbound.put(date, inbound);
                totalInbound += inbound;
                totalOutbound += outbound;
            } catch (DateTimeException ignored) {
                log.warn("트래픽 일별 집계에서 잘못된 날짜를 제외했습니다: {}-{}-{}",
                        row.getYearPart(), row.getMonthPart(), row.getDayPart());
            }
        }

        Map<String, Long> byTimeSlot = emptyTimeSlots();
        for (TrafficLogsRepository.HourlyTrafficAggregate row : hourlyRows) {
            Integer hour = row.getHourPart();
            if (hour == null || hour < 0 || hour > 23) {
                continue;
            }
            long total = nonNullLong(row.getInboundKbps()) + nonNullLong(row.getOutboundKbps());
            String slot = hour < 12 ? "morning" : hour < 18 ? "afternoon" : "night";
            byTimeSlot.merge(slot, total, Long::sum);
        }

        return new TrafficSummary(totalInbound, totalOutbound, dailyInbound, byTimeSlot);
    }

    private TrafficSummary emptyTrafficSummary() {
        return new TrafficSummary(0L, 0L, new TreeMap<>(), emptyTimeSlots());
    }

    private Map<String, Long> emptyTimeSlots() {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("morning", 0L);
        totals.put("afternoon", 0L);
        totals.put("night", 0L);
        return totals;
    }

    private long nonNullLong(Long value) {
        return value == null ? 0L : value;
    }

    private boolean usesSqliteDialect() {
        return hibernateDialect != null && hibernateDialect.toLowerCase(Locale.ROOT).contains("sqlite");
    }

    public List<TrafficLog> getTrafficForMacBetween(String mac, LocalDateTime start, LocalDateTime end) {
        if (mac == null || mac.isBlank()) {
            return trafficRepository.findByTimestampBetween(start, end);
        }
        return trafficRepository.findByMacAddressAndTimestampBetween(mac, start, end);
    }

    public List<TrafficLog> getTrafficForGroupBetween(Long groupId, LocalDateTime start, LocalDateTime end) {
        List<String> macs = pcRepository.findByGroupId(groupId).stream()
                .map(PcMaster::getMacAddress).collect(Collectors.toList());
        if (macs.isEmpty()) return List.of();
        return trafficRepository.findByMacAddressInAndTimestampBetween(macs, start, end);
    }

    private List<Map<String, Object>> buildClassTimeUsageRows(
            List<TrafficLog> logs, Map<String, PcMaster> pcByMac, Map<Long, String> groupNameById) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(buildSingleClassTimeRow("morning",   "오전 수업", "08:00~12:00", logs, pcByMac, groupNameById));
        rows.add(buildSingleClassTimeRow("afternoon", "오후 수업", "12:00~18:00", logs, pcByMac, groupNameById));
        rows.add(buildSingleClassTimeRow("night",     "야간 수업", "18:00~22:00", logs, pcByMac, groupNameById));
        return rows;
    }

    private Map<String, Object> buildSingleClassTimeRow(String slotKey, String slotName, String timeRange,
            List<TrafficLog> logs, Map<String, PcMaster> pcByMac, Map<Long, String> groupNameById) {
        Set<String> activeMacs = new HashSet<>();
        Map<String, Long> trafficByClass = new HashMap<>();
        long total = 0L;
        for (TrafficLog log : logs) {
            if (log.getTimestamp() == null) continue;
            if (!slotKey.equals(resolveSlot(log.getTimestamp().getHour()))) continue;
            long value = (log.getInboundKbps() != null ? log.getInboundKbps() : 0L)
                       + (log.getOutboundKbps() != null ? log.getOutboundKbps() : 0L);
            total += value;
            if (log.getMacAddress() != null) activeMacs.add(log.getMacAddress());
            String groupName = "미분류";
            PcMaster pc = log.getMacAddress() == null ? null : pcByMac.get(log.getMacAddress());
            if (pc != null && pc.getGroupId() != null) groupName = groupNameById.getOrDefault(pc.getGroupId(), "강의실#" + pc.getGroupId());
            trafficByClass.merge(groupName, value, Long::sum);
        }
        Map.Entry<String, Long> top = trafficByClass.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(Map.entry("-", 0L));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slotName", slotName); row.put("timeRange", timeRange);
        row.put("totalTraffic", total); row.put("activePcCount", activeMacs.size());
        row.put("topClassroom", top.getKey()); row.put("topClassroomTraffic", top.getValue());
        return row;
    }

    private List<Map<String, Object>> computeTopClassroomsByTraffic(
            List<TrafficLog> logs, Map<String, PcMaster> pcByMac, Map<Long, String> groupNameById, int limit) {
        Map<String, Long> byClass = new HashMap<>();
        for (TrafficLog log : logs) {
            if (log.getTimestamp() == null) continue;
            long value = (log.getInboundKbps() != null ? log.getInboundKbps() : 0L)
                       + (log.getOutboundKbps() != null ? log.getOutboundKbps() : 0L);
            String groupName = "미분류";
            PcMaster pc = log.getMacAddress() == null ? null : pcByMac.get(log.getMacAddress());
            if (pc != null && pc.getGroupId() != null) groupName = groupNameById.getOrDefault(pc.getGroupId(), "강의실#" + pc.getGroupId());
            byClass.merge(groupName, value, Long::sum);
        }
        return byClass.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .map(e -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("groupName", e.getKey()); m.put("traffic", e.getValue()); return m; })
                .collect(Collectors.toList());
    }

    private static String resolveSlot(int hour) {
        if (hour >= 8  && hour < 12) return "morning";
        if (hour >= 12 && hour < 18) return "afternoon";
        return "night";
    }

    private static String kbToReadable(long kbps) {
        if (kbps >= 1_000_000_000L) return String.format("%.2f TB", kbps / 1_000_000_000.0);
        if (kbps >= 1_000_000L)     return String.format("%.2f GB", kbps / 1_000_000.0);
        if (kbps >= 1_000L)         return String.format("%.2f MB", kbps / 1_000.0);
        return kbps + " KB";
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key); return v == null ? "-" : v.toString();
    }

    private static long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    private static String peakSlotLabel(Map<String, Long> bySlot) {
        String bestKey = bySlot.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("morning");
        return switch (bestKey) {
            case "afternoon" -> "오후(12~18시)";
            case "night"     -> "야간(18~22시)";
            default          -> "오전(08~12시)";
        };
    }
}
