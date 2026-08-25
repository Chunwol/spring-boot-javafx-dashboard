package com.example.backend.service.dashboard;

import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.traffic.TrafficLogFlushService;
import com.example.dto.dashboard.ClassroomSummaryDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

class ClassroomServiceTrafficSummaryTest {

    @Test
    void sumsOnlyTheLatestTrafficPerPcAndMatchesGroupsById() {
        ClassroomGroupRepository groups = mock(ClassroomGroupRepository.class);
        PcMasterRepository pcs = mock(PcMasterRepository.class);
        ClassroomIpRangeRepository ranges = mock(ClassroomIpRangeRepository.class);
        ClassroomTeacherDeviceRepository teachers = mock(ClassroomTeacherDeviceRepository.class);
        TrafficLogsRepository traffic = mock(TrafficLogsRepository.class);
        CommandQueueRepository commands = mock(CommandQueueRepository.class);
        TrafficLogFlushService liveTraffic = mock(TrafficLogFlushService.class);
        ClassroomService service = spy(new ClassroomService(groups, pcs, ranges, teachers, traffic, commands, liveTraffic));

        ClassroomSummaryDto room218 = classroom(218L, "218호", 40, 41);
        ClassroomSummaryDto room219 = classroom(219L, "219호", 0, 41);
        doReturn(List.of(room218, room219)).when(service).getClassroomSummaries();

        PcMaster first = pc("aa-bb-cc-dd-ee-01", 218L);
        PcMaster second = pc("AA:BB:CC:DD:EE:02", 218L);
        when(pcs.findByGroupIdIn(List.of(218L, 219L))).thenReturn(List.of(first, second));

        LocalDateTime now = LocalDateTime.now();
        when(traffic.findByMacAddressInAndTimestampBetween(anyList(), any(), any())).thenReturn(List.of(
                traffic("AA:BB:CC:DD:EE:01", 1024L, 1024L, now.minusMinutes(2)),
                traffic("aabbccddee01", 2048L, 512L, now.minusMinutes(1)),
                traffic("aa-bb-cc-dd-ee-02", 1024L, 256L, now.minusSeconds(30))));
        when(liveTraffic.getLiveTrafficSnapshot(anyList())).thenReturn(Map.of());

        List<Map<String, Object>> result = service.getClassroomTrafficSummaries();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("groupId", 218L)
                .containsEntry("groupName", "218호")
                .containsEntry("onlinePcs", 40)
                .containsEntry("totalPcs", 41)
                .containsEntry("inboundMbps", 3.0)
                .containsEntry("outboundMbps", 0.75);
        assertThat(result.get(0).get("updatedAt")).isEqualTo(now.minusSeconds(30));
        assertThat(result.get(1)).containsEntry("groupId", 219L)
                .containsEntry("inboundMbps", 0.0)
                .containsEntry("outboundMbps", 0.0)
                .containsEntry("updatedAt", null);
        verify(traffic).findByMacAddressInAndTimestampBetween(anyList(), any(), any());
    }

    @Test
    void liveTrafficOverridesDbValuesInClassroomSummary() {
        ClassroomGroupRepository groups = mock(ClassroomGroupRepository.class);
        PcMasterRepository pcs = mock(PcMasterRepository.class);
        ClassroomIpRangeRepository ranges = mock(ClassroomIpRangeRepository.class);
        ClassroomTeacherDeviceRepository teachers = mock(ClassroomTeacherDeviceRepository.class);
        TrafficLogsRepository traffic = mock(TrafficLogsRepository.class);
        CommandQueueRepository commands = mock(CommandQueueRepository.class);
        TrafficLogFlushService liveTraffic = mock(TrafficLogFlushService.class);
        ClassroomService service = spy(new ClassroomService(groups, pcs, ranges, teachers, traffic, commands, liveTraffic));

        doReturn(List.of(classroom(218L, "218호", 1, 1))).when(service).getClassroomSummaries();
        PcMaster pc = pc("aa-bb-cc-dd-ee-01", 218L);
        when(pcs.findByGroupIdIn(List.of(218L))).thenReturn(List.of(pc));
        LocalDateTime dbAt = LocalDateTime.now().minusMinutes(3);
        LocalDateTime liveAt = LocalDateTime.now();
        when(traffic.findByMacAddressInAndTimestampBetween(anyList(), any(), any()))
                .thenReturn(List.of(traffic("AA:BB:CC:DD:EE:01", 1024L, 1024L, dbAt)));
        when(liveTraffic.getLiveTrafficSnapshot(anyList())).thenReturn(Map.of(
                "AABBCCDDEE01", new TrafficLogFlushService.LiveTraffic(4096L, 512L, liveAt)));

        List<Map<String, Object>> result = service.getClassroomTrafficSummaries();

        assertThat(result.get(0)).containsEntry("inboundMbps", 4.0)
                .containsEntry("outboundMbps", 0.5)
                .containsEntry("updatedAt", liveAt);
    }

    @Test
    void liveTrafficOverridesDbValuesInPcTrafficRows() {
        ClassroomGroupRepository groups = mock(ClassroomGroupRepository.class);
        PcMasterRepository pcs = mock(PcMasterRepository.class);
        ClassroomIpRangeRepository ranges = mock(ClassroomIpRangeRepository.class);
        ClassroomTeacherDeviceRepository teachers = mock(ClassroomTeacherDeviceRepository.class);
        TrafficLogsRepository traffic = mock(TrafficLogsRepository.class);
        CommandQueueRepository commands = mock(CommandQueueRepository.class);
        TrafficLogFlushService liveTraffic = mock(TrafficLogFlushService.class);
        ClassroomService service = new ClassroomService(groups, pcs, ranges, teachers, traffic, commands, liveTraffic);

        PcMaster pc = pc("aa-bb-cc-dd-ee-01", 218L);
        when(pcs.findByGroupId(218L)).thenReturn(List.of(pc));
        LocalDateTime dbAt = LocalDateTime.now().minusMinutes(3);
        LocalDateTime liveAt = LocalDateTime.now();
        when(traffic.findByMacAddressInAndTimestampBetween(anyList(), any(), any()))
                .thenReturn(List.of(traffic("AA:BB:CC:DD:EE:01", 1024L, 1024L, dbAt)));
        when(liveTraffic.getLiveTrafficSnapshot(anyList())).thenReturn(Map.of(
                "AABBCCDDEE01", new TrafficLogFlushService.LiveTraffic(3072L, 256L, liveAt)));

        List<Map<String, Object>> result = service.getClassroomPcTraffic(218L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("inboundMbps", 3.0)
                .containsEntry("outboundMbps", 0.25)
                .containsEntry("updatedAt", liveAt);
    }

    private static ClassroomSummaryDto classroom(Long id, String name, int online, int total) {
        ClassroomSummaryDto room = new ClassroomSummaryDto();
        room.setGroupId(id);
        room.setGroupName(name);
        room.setOnlinePcs(online);
        room.setTotalPcs(total);
        return room;
    }

    private static PcMaster pc(String macAddress, Long groupId) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setGroupId(groupId);
        return pc;
    }

    private static TrafficLog traffic(String macAddress,
                                      Long inboundKbps,
                                      Long outboundKbps,
                                      LocalDateTime timestamp) {
        TrafficLog log = new TrafficLog();
        log.setMacAddress(macAddress);
        log.setInboundKbps(inboundKbps);
        log.setOutboundKbps(outboundKbps);
        log.setTimestamp(timestamp);
        return log;
    }
}
