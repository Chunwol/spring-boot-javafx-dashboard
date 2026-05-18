package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.dto.heartbeat.HeartbeatOfflineDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.realtime.WebSocketService;
import com.example.backend.service.status.OperationalMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

    @Mock PcMasterRepository pcMasterRepository;
    @Mock ClassroomResolutionService classroomResolutionService;
    @Mock TrafficLogsRepository trafficLogsRepository;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock AuditService auditService;
    @Mock FileDistributionOrchestrator fileDistributionOrchestrator;
    @Mock WebSocketService webSocketService;
    @Mock OperationalMetricsService operationalMetricsService;

    @InjectMocks HeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(heartbeatService, "heartbeatPersistIntervalSeconds", 30);
        ReflectionTestUtils.setField(heartbeatService, "trafficLogMinIntervalSeconds", 30);
        ReflectionTestUtils.setField(heartbeatService, "trafficSampleIntervalSeconds", 2);
        ReflectionTestUtils.setField(heartbeatService, "maxCommandRetries", 3);
    }

    @Test
    void processHeartbeat_agentSessionChanged_requeuesInFlightCommands() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:FF");
        heartbeat.setHostname("pc-01");
        heartbeat.setIpAddress("10.10.0.20");
        heartbeat.setAgentSessionId("new-session");
        heartbeat.setInboundKbps(10.0);
        heartbeat.setOutboundKbps(5.0);

        PcMaster pc = new PcMaster();
        pc.setMacAddress("AA:BB:CC:DD:EE:FF");
        pc.setPcName("pc-01");
        pc.setIpAddress("10.10.0.20");
        pc.setStatus("ONLINE");
        pc.setRole("STUDENT");
        pc.setLastAgentSessionId("old-session");

        CommandQueue acked = new CommandQueue();
        acked.setTaskId(7L);
        acked.setMacAddress(pc.getMacAddress());
        acked.setStatus("ACKED");
        acked.setRetryCount(1);

        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupIdByIp("10.10.0.20")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.20", pc.getMacAddress()))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("DELIVERED", "ACKED")))
                .thenReturn(List.of(acked));
        when(commandQueueRepository.findNextDispatchCandidates(eq(pc.getMacAddress()), any()))
                .thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        CommandQueue saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getRetryCount()).isEqualTo(2);
        assertThat(saved.getAckedAt()).isNull();
        assertThat(saved.getDeliveredAt()).isNull();
        assertThat(saved.getIsExecuted()).isFalse();
    }

    @Test
    void processHeartbeat_noSessionId_keepsExistingInFlightCommandsUntouched() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:FF");
        heartbeat.setHostname("pc-01");
        heartbeat.setIpAddress("10.10.0.20");

        PcMaster pc = new PcMaster();
        pc.setMacAddress("AA:BB:CC:DD:EE:FF");
        pc.setLastAgentSessionId("old-session");

        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupIdByIp("10.10.0.20")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.20", pc.getMacAddress()))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(pc.getMacAddress()), any()))
                .thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        verify(commandQueueRepository, never()).findByMacAddressAndStatusInOrderByCreatedAtAsc(
                eq(pc.getMacAddress()), any());
    }

    @Test
    void processHeartbeat_prioritizesListFilesCommandDispatch() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:11");
        heartbeat.setHostname("pc-02");
        heartbeat.setIpAddress("10.10.0.21");
        heartbeat.setInboundKbps(1.0);
        heartbeat.setOutboundKbps(1.0);

        when(pcMasterRepository.findById("AA:BB:CC:DD:EE:11")).thenReturn(Optional.empty());
        when(classroomResolutionService.resolveGroupIdByIp("10.10.0.21")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.21", "AA:BB:CC:DD:EE:11"))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        CommandQueue listFiles = new CommandQueue();
        listFiles.setTaskId(99L);
        listFiles.setMacAddress("AA:BB:CC:DD:EE:11");
        listFiles.setCommandType("LIST_FILES");
        listFiles.setStatus("QUEUED");
        listFiles.setPayloadJson("{\"path\":\"\"}");
        listFiles.setCreatedAt(java.time.LocalDateTime.now().minusSeconds(3));

        when(commandQueueRepository.findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:11"), any()))
                .thenReturn(List.of(listFiles));

        var response = heartbeatService.processHeartbeat(heartbeat);

        assertThat(response).isNotNull();
        assertThat(response.get("taskId")).isEqualTo(99L);
        assertThat(response.get("type")).isEqualTo("LIST_FILES");
        verify(commandQueueRepository).save(listFiles);
    }

    @SuppressWarnings("unchecked")
    @Test
    void processHeartbeat_withTrafficSamples_persistsEachSampleToDb() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:99");
        heartbeat.setHostname("pc-99");
        heartbeat.setIpAddress("10.10.0.99");
        heartbeat.setTrafficSamples(List.of(
                new double[]{12.4, 45.6},
                new double[]{20.1, 30.9}
        ));

        when(pcMasterRepository.findById("AA:BB:CC:DD:EE:99")).thenReturn(Optional.empty());
        when(classroomResolutionService.resolveGroupIdByIp("10.10.0.99")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.99", "AA:BB:CC:DD:EE:99"))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());
        when(commandQueueRepository.findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:99"), any()))
                .thenReturn(List.of());

        heartbeatService.processHeartbeat(heartbeat);

        ArgumentCaptor<List> logsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLogsRepository, times(1)).saveAll(logsCaptor.capture());
        List<TrafficLog> saved = (List<TrafficLog>) logsCaptor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getInboundKbps()).isEqualTo(12L);
        assertThat(saved.get(0).getOutboundKbps()).isEqualTo(46L);
        assertThat(saved.get(1).getInboundKbps()).isEqualTo(20L);
        assertThat(saved.get(1).getOutboundKbps()).isEqualTo(31L);
        assertThat(saved.get(0).getTimestamp()).isBeforeOrEqualTo(saved.get(1).getTimestamp());
    }

    @Test
    void processGracefulOffline_marksPcOfflineAndBroadcastsDashboardUpdate() {
        String mac = "AA:BB:CC:DD:EE:77";
        PcMaster pc = new PcMaster();
        pc.setMacAddress(mac);
        pc.setPcName("pc-07");
        pc.setIpAddress("10.10.0.77");
        pc.setStatus("ONLINE");
        pc.setLastAgentSessionId("old-session");

        HeartbeatOfflineDto offline = new HeartbeatOfflineDto();
        offline.setMacAddress(mac);
        offline.setHostname("pc-07");
        offline.setIpAddress("10.10.0.77");
        offline.setAgentSessionId("new-session");
        offline.setAgentVersion("1.2.3");
        offline.setReason("AGENT_SHUTDOWN");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));

        heartbeatService.processGracefulOffline(offline);

        ArgumentCaptor<PcMaster> pcCaptor = ArgumentCaptor.forClass(PcMaster.class);
        verify(pcMasterRepository).save(pcCaptor.capture());
        PcMaster saved = pcCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("OFFLINE");
        assertThat(saved.getLastAgentSessionId()).isEqualTo("new-session");
        assertThat(saved.getAgentVersion()).isEqualTo("1.2.3");
        assertThat(saved.getLastSeen()).isNotNull();
        verify(webSocketService).sendDashboardUpdate(any());
    }
}
