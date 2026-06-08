package com.example.backend.service.report;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 보고서 PDF를 가짜 데이터로 실제 생성해 build/report-sample.pdf 에 저장한다.
 * 한글 렌더링(malgun.ttf 로컬)·레이아웃·표/그래프 채움을 육안 검증하기 위한 테스트.
 */
class ReportServiceVisualTest {

    @Test
    void generateSampleWeeklyReport() throws Exception {
        PcMasterRepository       pcRepo      = mock(PcMasterRepository.class);
        TrafficLogsRepository    trafficRepo = mock(TrafficLogsRepository.class);
        ClassroomGroupRepository groupRepo   = mock(ClassroomGroupRepository.class);
        CommandQueueRepository   cmdRepo     = mock(CommandQueueRepository.class);

        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);

        // ── 강의실 3개 ──
        List<ClassroomGroup> groups = List.of(
                group(1L, "109-1호"), group(2L, "109-2호"), group(3L, "정보실"));
        when(groupRepo.findAll()).thenReturn(groups);

        // ── PC 9개 (강의실당 3개, 각 강의실 1개는 교사) ──
        List<PcMaster> pcs = new ArrayList<>();
        String[] macs = new String[9];
        int idx = 0;
        long[] gids = {1L, 2L, 3L};
        for (long gid : gids) {
            for (int i = 0; i < 3; i++) {
                String mac = String.format("AA:BB:CC:00:%02d:%02d", gid, i);
                macs[idx++] = mac;
                PcMaster p = new PcMaster();
                p.setMacAddress(mac);
                p.setPcName("PC-" + gid + "-" + (i + 1));
                p.setGroupId(gid);
                p.setStatus(i == 2 ? "OFFLINE" : "ONLINE");
                p.setRole(i == 0 ? "TEACHER" : "STUDENT");
                p.setLastSeen(to.minusMinutes(i * 3L));
                pcs.add(p);
            }
        }
        when(pcRepo.findAll()).thenReturn(pcs);

        // ── 트래픽 로그 (7일 × 4시간대 × 9 PC) ──
        List<TrafficLog> logs = new ArrayList<>();
        int[] hours = {9, 11, 14, 19};
        for (int d = 0; d < 7; d++) {
            for (int h : hours) {
                for (int m = 0; m < macs.length; m++) {
                    TrafficLog t = new TrafficLog();
                    t.setMacAddress(macs[m]);
                    t.setTimestamp(to.minusDays(d).withHour(h).withMinute(0).withSecond(0).withNano(0));
                    t.setInboundKbps((long) (5000 + (m * 1500L) + (h * 800L) + (d * 200L)));
                    t.setOutboundKbps((long) (1500 + (m * 400L) + (h * 200L)));
                    logs.add(t);
                }
            }
        }
        when(trafficRepo.findByTimestampBetween(any(), any())).thenReturn(logs);

        // ── 명령 이력 ──
        List<CommandQueue> cmds = new ArrayList<>();
        addCmd(cmds, "RUN_PS1",  "COMPLETED", 8);
        addCmd(cmds, "RUN_PS1",  "FAILED",    2);
        addCmd(cmds, "REBOOT",   "COMPLETED", 5);
        addCmd(cmds, "SHUTDOWN", "COMPLETED", 4);
        addCmd(cmds, "SHUTDOWN", "QUEUED",    1);
        addCmd(cmds, "AGENT_UPDATE", "COMPLETED", 3);
        when(cmdRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(cmds);

        ReportService svc = new ReportService(pcRepo, trafficRepo, groupRepo, cmdRepo);
        byte[] pdf = svc.generatePdfReport(from, to, "WEEKLY");

        assertTrue(pdf.length > 2000, "PDF가 비정상적으로 작음: " + pdf.length + " bytes");

        File out = new File("build/report-sample.pdf");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), pdf);
        System.out.println("[ReportServiceVisualTest] PDF 저장: "
                + out.getAbsolutePath() + " (" + pdf.length + " bytes)");

        // ── PDF 각 페이지를 PNG로 렌더 (육안 검증용) ──
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(pdf)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                java.awt.image.BufferedImage img = renderer.renderImageWithDPI(i, 110);
                File png = new File("build/report-page" + (i + 1) + ".png");
                javax.imageio.ImageIO.write(img, "png", png);
                System.out.println("  page " + (i + 1) + " → " + png.getAbsolutePath());
            }
        }
    }

    private static ClassroomGroup group(Long id, String name) {
        ClassroomGroup g = new ClassroomGroup();
        g.setId(id);
        g.setName(name);
        return g;
    }

    private static void addCmd(List<CommandQueue> list, String type, String status, int count) {
        for (int i = 0; i < count; i++) {
            CommandQueue c = new CommandQueue();
            c.setCommandType(type);
            c.setStatus(status);
            c.setMacAddress("AA:BB:CC:00:01:00");
            list.add(c);
        }
    }
}
