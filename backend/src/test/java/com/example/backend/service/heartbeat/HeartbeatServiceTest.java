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
import com.example.backend.service.traffic.TrafficLogFlushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    @Test
    void normalizeScmateMode_preservesAgentModeAndDerivesOnlyFromSchedule() {
        assertThat(HeartbeatService.normalizeScmateMode("PROTECTED", "MANUAL")).isEqualTo("PROTECTED");
        assertThat(HeartbeatService.normalizeScmateMode("OPERATOR", "RESTART")).isEqualTo("OPERATOR");
        assertThat(HeartbeatService.normalizeScmateMode("PROTECTED", "WEEKLY")).isEqualTo("PROTECTED");
        assertThat(HeartbeatService.normalizeScmateMode("OPERATOR", null)).isEqualTo("OPERATOR");
        assertThat(HeartbeatService.normalizeScmateMode("UNKNOWN", "MANUAL")).isEqualTo("OPERATOR");
        assertThat(HeartbeatService.normalizeScmateMode(null, "DAILY_FIRST_BOOT")).isEqualTo("PROTECTED");
        assertThat(HeartbeatService.normalizeScmateMode(null, "OTHER")).isEqualTo("UNKNOWN");
    }

    @Test
    void cliVerifiedProtectedModeIsNotOverwrittenByStaleSameBootHeartbeat() {
        assertThat(HeartbeatService.shouldPreserveCliVerifiedMode(
                100L, 100L,
                "PROTECTED", "RESTART", "SCMATE_CLIENT_MEMORY+SHD_API+SHD_CMD",
                "OPERATOR", "MANUAL", "SCMATE_CLIENT_MEMORY+SHD_API")).isTrue();
        assertThat(HeartbeatService.shouldPreserveCliVerifiedMode(
                100L, 200L,
                "PROTECTED", "RESTART", "SCMATE_CLIENT_MEMORY+SHD_API+SHD_CMD",
                "OPERATOR", "MANUAL", "SCMATE_CLIENT_MEMORY+SHD_API")).isFalse();
        assertThat(HeartbeatService.shouldPreserveCliVerifiedMode(
                100L, 100L,
                "PROTECTED", "RESTART", "SCMATE_CLIENT_MEMORY+SHD_API+SHD_CMD",
                "OPERATOR", "MANUAL", "SCMATE_CLIENT_MEMORY+SHD_API+SHD_CMD")).isFalse();
    }

    @Test
    void confirmedBootChangeRequiresForwardMovementBeyondClockJitter() {
        assertThat(HeartbeatService.isConfirmedBootChange(100L, 200L)).isTrue();
        assertThat(HeartbeatService.isConfirmedBootChange(200L, 100L)).isFalse();
        assertThat(HeartbeatService.isConfirmedBootChange(100L, 105L)).isFalse();
        assertThat(HeartbeatService.isConfirmedBootChange(100L, 106L)).isTrue();
        assertThat(HeartbeatService.isConfirmedBootChange(null, 200L)).isFalse();
    }

    @Mock PcMasterRepository pcMasterRepository;
    @Mock ClassroomResolutionService classroomResolutionService;
    @Mock TrafficLogsRepository trafficLogsRepository;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock AuditService auditService;
    @Mock FileDistributionOrchestrator fileDistributionOrchestrator;
    @Mock WebSocketService webSocketService;
    @Mock OperationalMetricsService operationalMetricsService;
    @Mock TrafficLogFlushService trafficLogFlushService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks HeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(heartbeatService, "heartbeatPersistIntervalSeconds", 30);
        ReflectionTestUtils.setField(heartbeatService, "trafficLogMinIntervalSeconds", 30);
        ReflectionTestUtils.setField(heartbeatService, "trafficSampleIntervalSeconds", 2);
        ReflectionTestUtils.setField(heartbeatService, "maxCommandRetries", 3);
        ReflectionTestUtils.setField(heartbeatService, "commandQueueTtlSeconds", 300L);
        ReflectionTestUtils.setField(heartbeatService, "dispatchCommandOnHeartbeat", true);
        ReflectionTestUtils.setField(heartbeatService, "autoCatchupCheckIntervalSeconds", 300);
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
        when(classroomResolutionService.resolveGroupId("10.10.0.20", pc.getMacAddress())).thenReturn(null);
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
        when(classroomResolutionService.resolveGroupId("10.10.0.20", pc.getMacAddress())).thenReturn(null);
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
        when(classroomResolutionService.resolveGroupId("10.10.0.21", "AA:BB:CC:DD:EE:11")).thenReturn(null);
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
        when(commandQueueRepository.markDeliveredIfQueued(eq(99L), any(LocalDateTime.class)))
                .thenReturn(1);

        var response = heartbeatService.processHeartbeat(heartbeat);

        assertThat(response).isNotNull();
        assertThat(response.get("taskId")).isEqualTo(99L);
        assertThat(response.get("type")).isEqualTo("LIST_FILES");
        verify(commandQueueRepository).markDeliveredIfQueued(eq(99L), any(LocalDateTime.class));
    }

    @Test
    void dispatchNextCommand_doesNotDeliverExpiredQueuedCommand() {
        CommandQueue expired = new CommandQueue();
        expired.setTaskId(100L);
        expired.setMacAddress("AA:BB:CC:DD:EE:12");
        expired.setCommandType("SHUTDOWN");
        expired.setStatus("QUEUED");
        expired.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(commandQueueRepository.findNextDispatchCandidates(
                eq(expired.getMacAddress()), any())).thenReturn(List.of(expired));

        assertThat(heartbeatService.dispatchNextCommand(expired.getMacAddress())).isNull();

        verify(commandQueueRepository, never()).markDeliveredIfQueued(
                eq(expired.getTaskId()), any(LocalDateTime.class));
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
        when(classroomResolutionService.resolveGroupId("10.10.0.99", "AA:BB:CC:DD:EE:99")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.99", "AA:BB:CC:DD:EE:99"))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());
        when(commandQueueRepository.findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:99"), any()))
                .thenReturn(List.of());

        heartbeatService.processHeartbeat(heartbeat);

        ArgumentCaptor<List> logsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trafficLogFlushService, times(1)).enqueueAll(logsCaptor.capture());
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

    @Test
    void processHeartbeat_usesMacForTeacherGroupResolution() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:55");
        heartbeat.setHostname("teacher-pc");
        heartbeat.setIpAddress("10.10.9.55");

        when(pcMasterRepository.findById("AA:BB:CC:DD:EE:55")).thenReturn(Optional.empty());
        when(classroomResolutionService.resolveGroupId("10.10.9.55", "AA:BB:CC:DD:EE:55")).thenReturn(12L);
        when(classroomResolutionService.resolveRole(12L, "10.10.9.55", "AA:BB:CC:DD:EE:55"))
                .thenReturn(ClassroomResolutionService.PcRole.TEACHER);
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());
        when(commandQueueRepository.findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:55"), any()))
                .thenReturn(List.of());

        heartbeatService.processHeartbeat(heartbeat);

        ArgumentCaptor<PcMaster> captor = ArgumentCaptor.forClass(PcMaster.class);
        verify(pcMasterRepository).save(captor.capture());
        assertThat(captor.getValue().getGroupId()).isEqualTo(12L);
        assertThat(captor.getValue().getRole()).isEqualTo("TEACHER");
    }

    @Test
    void processHeartbeat_skipsAutoCatchupWithinCooldownForSameVersion() {
        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:31");
        heartbeat.setHostname("pc-31");
        heartbeat.setIpAddress("10.10.0.31");
        heartbeat.setAgentVersion("1.0.0");

        when(pcMasterRepository.findById("AA:BB:CC:DD:EE:31"))
                .thenReturn(Optional.empty(), Optional.of(savedPc("AA:BB:CC:DD:EE:31", "1.0.0")));
        when(classroomResolutionService.resolveGroupId("10.10.0.31", "AA:BB:CC:DD:EE:31")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.31", "AA:BB:CC:DD:EE:31"))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:31"), any()))
                .thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);
        heartbeatService.processHeartbeat(heartbeat);

        verify(fileDistributionOrchestrator, times(1)).tryAutoCatchUpOnHeartbeat(any());
    }

    @Test
    void processHeartbeat_canDisableCommandDispatchOnHeartbeat() {
        ReflectionTestUtils.setField(heartbeatService, "dispatchCommandOnHeartbeat", false);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress("AA:BB:CC:DD:EE:41");
        heartbeat.setHostname("pc-41");
        heartbeat.setIpAddress("10.10.0.41");

        when(pcMasterRepository.findById("AA:BB:CC:DD:EE:41")).thenReturn(Optional.empty());
        when(classroomResolutionService.resolveGroupId("10.10.0.41", "AA:BB:CC:DD:EE:41")).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.41", "AA:BB:CC:DD:EE:41"))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        var response = heartbeatService.processHeartbeat(heartbeat);

        assertThat(response).isNull();
        verify(commandQueueRepository, never()).findNextDispatchCandidates(eq("AA:BB:CC:DD:EE:41"), any());
    }

    @Test
    void processHeartbeat_operatorModeCompletesRequestWithoutReboot() {
        String mac = "AA:BB:CC:DD:EE:60";
        PcMaster pc = savedPc(mac, "1.1.23");
        pc.setIpAddress("10.10.0.60");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateTransitionPending(true);
        pc.setScmateWorkflowStage("OPERATOR_REQUESTED");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-60");
        heartbeat.setIpAddress("10.10.0.60");
        heartbeat.setAgentSessionId("same-session");
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("OPERATOR");
        heartbeat.setScmateTransitionPending(false);
        heartbeat.setScmateRawState(1L);

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.60", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.60", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_READY");
        assertThat(pc.getScmateMode()).isEqualTo("OPERATOR");
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void processHeartbeat_operatorTransitionIsNotReportedReadyUntilStable() {
        String mac = "AA:BB:CC:DD:EE:64";
        PcMaster pc = savedPc(mac, "1.1.27");
        pc.setIpAddress("10.10.0.64");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("OPERATOR_REQUESTED");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-64");
        heartbeat.setIpAddress("10.10.0.64");
        heartbeat.setAgentSessionId("same-session");
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("OPERATOR");
        heartbeat.setScmateTransitionPending(true);
        heartbeat.setScmateRawState(3L);

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.64", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.64", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_REQUESTED");
        assertThat(pc.getScmateMode()).isEqualTo("OPERATOR");
        assertThat(pc.getScmateTransitionPending()).isTrue();
    }

    @Test
    void processHeartbeat_operatorRequestQueuesProtectionRecoveryWhenDeleteTaskDisablesEngine() {
        String mac = "AA:BB:CC:DD:EE:65";
        PcMaster pc = savedPc(mac, "1.1.41");
        pc.setIpAddress("10.10.0.65");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateWorkflowStage("OPERATOR_REQUESTED");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-65");
        heartbeat.setIpAddress("10.10.0.65");
        heartbeat.setAgentSessionId("same-session");
        heartbeat.setScmateAvailable(false);
        heartbeat.setScmateAvailabilityReason("PROTECT_DISABLED");
        heartbeat.setScmateMode("UNKNOWN");
        heartbeat.setScmateSchedule("UNKNOWN");
        heartbeat.setScmateTransitionPending(false);
        heartbeat.setScmateRawState(9L);

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.65", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.65", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                mac, List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getCommandType()).isEqualTo("SCMATE_CONTROL");
        assertThat(captor.getValue().getPayloadJson())
                .contains("\"action\":\"ENABLE_PROTECTION\"")
                .contains("\"resumeAction\":\"REQUEST_OPERATOR\"");
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_ENABLE_REQUESTED");
    }

    @Test
    void processHeartbeat_afterProtectedReboot_completesWorkflowEvenWhenRebootAckIsLate() throws Exception {
        String mac = "AA:BB:CC:DD:EE:61";
        PcMaster pc = savedPc(mac, "1.1.23");
        pc.setIpAddress("10.10.0.61");
        pc.setLastAgentSessionId("before-protected-reboot");
        pc.setScmateAvailable(true);
        pc.setScmateMode("OPERATOR");
        pc.setScmateTransitionPending(true);
        pc.setScmateWorkflowStage("WAITING_PROTECTED_REBOOT");
        pc.setSystemBootTimeEpochSeconds(100L);
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-61");
        heartbeat.setIpAddress("10.10.0.61");
        heartbeat.setAgentSessionId("after-protected-reboot");
        heartbeat.setSystemBootTimeEpochSeconds(200L);
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("PROTECTED");
        heartbeat.setScmateSchedule("RESTART");
        heartbeat.setScmateTransitionPending(false);
        heartbeat.setScmateRawState(0L);

        CommandQueue reboot = new CommandQueue();
        reboot.setTaskId(161L);
        reboot.setMacAddress(mac);
        reboot.setCommandType("REBOOT");
        reboot.setPayloadJson("{\"scmateWorkflowId\":\"workflow-61\",\"scmateAction\":\"REBOOT_TO_PROTECTED\"}");
        reboot.setStatus("DELIVERED");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.61", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.61", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                mac, List.of("DELIVERED", "ACKED"))).thenReturn(List.of(reboot));
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());
        when(objectMapper.readTree(reboot.getPayloadJson()))
                .thenReturn(new ObjectMapper().readTree(reboot.getPayloadJson()));

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("COMPLETED");
        assertThat(pc.getScmateMode()).isEqualTo("PROTECTED");
        assertThat(pc.getScmateTransitionPending()).isFalse();
        assertThat(reboot.getStatus()).isEqualTo("COMPLETED");
        assertThat(reboot.getExitCode()).isZero();
        assertThat(reboot.getIsExecuted()).isTrue();
        assertThat(reboot.getStderr()).contains("system boot time changed");
        verify(pcMasterRepository).save(pc);
        verify(commandQueueRepository).save(reboot);
    }

    @Test
    void processHeartbeat_afterProtectionRequestAutoReboot_completesScmateWorkflow() {
        String mac = "AA:BB:CC:DD:EE:62";
        PcMaster pc = savedPc(mac, "1.1.23");
        pc.setIpAddress("10.10.0.62");
        pc.setLastAgentSessionId("before-auto-reboot");
        pc.setScmateAvailable(true);
        pc.setScmateMode("OPERATOR");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("PROTECTION_REQUESTED");
        pc.setSystemBootTimeEpochSeconds(100L);
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-62");
        heartbeat.setIpAddress("10.10.0.62");
        heartbeat.setAgentSessionId("after-auto-reboot");
        heartbeat.setSystemBootTimeEpochSeconds(200L);
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("PROTECTED");
        heartbeat.setScmateSchedule("RESTART");
        heartbeat.setScmateTransitionPending(false);
        heartbeat.setScmateRawState(0L);

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.62", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.62", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                mac, List.of("DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("COMPLETED");
        assertThat(pc.getScmateMode()).isEqualTo("PROTECTED");
        assertThat(pc.getScmateTransitionPending()).isFalse();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void processHeartbeat_afterSystemRestoreNewSession_completesWorkflowAndCommand() throws Exception {
        String mac = "AA:BB:CC:DD:EE:63";
        PcMaster pc = savedPc(mac, "1.1.24");
        pc.setIpAddress("10.10.0.63");
        pc.setLastAgentSessionId("before-system-restore");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("RESTORE_REQUESTED");
        pc.setSystemBootTimeEpochSeconds(100L);
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-63");
        heartbeat.setIpAddress("10.10.0.63");
        heartbeat.setAgentSessionId("after-system-restore");
        heartbeat.setSystemBootTimeEpochSeconds(200L);
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("PROTECTED");
        heartbeat.setScmateSchedule("RESTART");
        heartbeat.setScmateTransitionPending(false);
        heartbeat.setScmateRawState(0L);

        CommandQueue restore = new CommandQueue();
        restore.setTaskId(163L);
        restore.setMacAddress(mac);
        restore.setCommandType("SCMATE_CONTROL");
        restore.setPayloadJson("{\"action\":\"RESTORE_BASELINE\"}");
        restore.setStatus("ACKED");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.63", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.63", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                mac, List.of("DELIVERED", "ACKED"))).thenReturn(List.of(restore));
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());
        when(objectMapper.readTree(restore.getPayloadJson()))
                .thenReturn(new ObjectMapper().readTree(restore.getPayloadJson()));

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("COMPLETED");
        assertThat(pc.getScmateMode()).isEqualTo("PROTECTED");
        assertThat(pc.getLastAgentSessionId()).isEqualTo("after-system-restore");
        assertThat(restore.getStatus()).isEqualTo("COMPLETED");
        assertThat(restore.getExitCode()).isZero();
        assertThat(restore.getIsExecuted()).isTrue();
        verify(commandQueueRepository).save(restore);
    }

    @Test
    void processHeartbeat_sessionChangeWithoutBootChange_doesNotCompleteRestore() {
        String mac = "AA:BB:CC:DD:EE:65";
        PcMaster pc = savedPc(mac, "1.1.30");
        pc.setLastAgentSessionId("old-agent-process");
        pc.setSystemBootTimeEpochSeconds(100L);
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateSchedule("RESTART");
        pc.setScmateWorkflowStage("RESTORE_REQUESTED");
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-65");
        heartbeat.setIpAddress("10.10.0.65");
        heartbeat.setAgentSessionId("new-agent-process");
        heartbeat.setSystemBootTimeEpochSeconds(100L);
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateMode("PROTECTED");
        heartbeat.setScmateSchedule("RESTART");
        heartbeat.setScmateTransitionPending(false);

        CommandQueue restore = new CommandQueue();
        restore.setTaskId(165L);
        restore.setMacAddress(mac);
        restore.setCommandType("SCMATE_CONTROL");
        restore.setPayloadJson("{\"action\":\"RESTORE_BASELINE\"}");
        restore.setStatus("ACKED");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.65", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.65", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                mac, List.of("DELIVERED", "ACKED"))).thenReturn(List.of(restore));
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("RESTORE_REQUESTED");
        assertThat(restore.getStatus()).isEqualTo("ACKED");
        verify(commandQueueRepository, never()).save(restore);
    }

    @Test
    void processHeartbeat_persistsScmateDetailFieldsWithoutRawModeReinterpretation() {
        String mac = "AA:BB:CC:DD:EE:66";
        PcMaster pc = savedPc(mac, "1.1.30");
        pc.setIpAddress("10.10.0.66");
        LocalDateTime baseline = LocalDateTime.of(2026, 8, 13, 9, 0);
        LocalDateTime snap1 = baseline.plusHours(1);
        LocalDateTime snap2 = baseline.plusHours(2);

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-66");
        heartbeat.setIpAddress("10.10.0.66");
        heartbeat.setSystemBootTimeEpochSeconds(123456L);
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateAvailabilityReason("available");
        heartbeat.setScmateMode("OPERATOR");
        heartbeat.setScmateSchedule("RESTART");
        heartbeat.setScmateSelectedPoint("SNAP1");
        heartbeat.setScmateProductVariant("RX2");
        heartbeat.setScmateStateSource("client_display");
        heartbeat.setScmateRawState(0L);
        heartbeat.setScmateBaselineCreatedAt(baseline);
        heartbeat.setScmateSnap1CreatedAt(snap1);
        heartbeat.setScmateSnap2CreatedAt(snap2);

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId("10.10.0.66", mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, "10.10.0.66", mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getSystemBootTimeEpochSeconds()).isEqualTo(123456L);
        assertThat(pc.getScmateMode()).isEqualTo("OPERATOR");
        assertThat(pc.getScmateSchedule()).isEqualTo("RESTART");
        assertThat(pc.getScmateAvailabilityReason()).isEqualTo("AVAILABLE");
        assertThat(pc.getScmateSelectedPoint()).isEqualTo("SNAP1");
        assertThat(pc.getScmateProductVariant()).isEqualTo("RX2");
        assertThat(pc.getScmateStateSource()).isEqualTo("CLIENT_DISPLAY");
        assertThat(pc.getScmateBaselineCreatedAt()).isEqualTo(baseline);
        assertThat(pc.getScmateSnap1CreatedAt()).isEqualTo(snap1);
        assertThat(pc.getScmateSnap2CreatedAt()).isEqualTo(snap2);
    }

    @Test
    void dispatchNextCommand_refreshStatusDetailIncludesCredential() throws Exception {
        String mac = "AA:BB:CC:DD:EE:77";
        CommandQueue command = new CommandQueue();
        command.setTaskId(77L);
        command.setMacAddress(mac);
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"REFRESH_STATUS_DETAIL\",\"recoveryPoint\":\"BASELINE\"}");
        command.setCreatedAt(LocalDateTime.now().minusSeconds(1));

        ReflectionTestUtils.setField(heartbeatService, "scmateCliUsername", "Administrator");
        ReflectionTestUtils.setField(heartbeatService, "scmateCliPassword", "2500");
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of(command));
        when(commandQueueRepository.markDeliveredIfQueued(eq(77L), any())).thenReturn(1);
        when(objectMapper.readTree(command.getPayloadJson()))
                .thenReturn(new ObjectMapper().readTree(command.getPayloadJson()));

        Map<String, Object> dispatched = heartbeatService.dispatchNextCommand(mac);

        assertThat(dispatched).containsKey("scmateCredential");
        Map<?, ?> credential = (Map<?, ?>) dispatched.get("scmateCredential");
        assertThat(credential.get("username")).isEqualTo("Administrator");
        assertThat(credential.get("password")).isEqualTo("2500");
    }

    @Test
    void processHeartbeat_preservesWorkflowFailureWhenHeartbeatHasNoScmateError() {
        String mac = "AA:BB:CC:DD:EE:78";
        PcMaster pc = savedPc(mac, "1.1.35");
        pc.setScmateWorkflowStage("FAILED");
        pc.setScmateError("SCMATE detail command failed");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-78");
        heartbeat.setIpAddress(pc.getIpAddress());
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateAvailabilityReason("ACTIVE");
        heartbeat.setScmateMode("OPERATOR");
        heartbeat.setScmateSchedule("MANUAL");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId(pc.getIpAddress(), mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, pc.getIpAddress(), mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("FAILED");
        assertThat(pc.getScmateError()).isEqualTo("SCMATE detail command failed");
    }

    @Test
    void processHeartbeatClearsAlreadyProtectedNoOpFailure() {
        String mac = "AA:BB:CC:DD:EE:7A";
        PcMaster pc = savedPc(mac, "1.1.40");
        pc.setScmateWorkflowStage("FAILED");
        pc.setScmateError("SCMATE_PROTECTION_NOT_DISABLED: SCMATE protection is not in the disabled state.");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-7a");
        heartbeat.setIpAddress(pc.getIpAddress());
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateAvailabilityReason("ACTIVE");
        heartbeat.setScmateMode("PROTECTED");
        heartbeat.setScmateSchedule("RESTART");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId(pc.getIpAddress(), mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, pc.getIpAddress(), mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("COMPLETED");
        assertThat(pc.getScmateError()).isNull();
    }

    @Test
    void processHeartbeat_reconcilesStaleWorkflowStageFromStableScmateState() {
        String mac = "AA:BB:CC:DD:EE:79";
        PcMaster pc = savedPc(mac, "1.1.35");
        pc.setScmateWorkflowStage("INSTALL_PENDING_REBOOT");

        HeartbeatDto heartbeat = new HeartbeatDto();
        heartbeat.setMacAddress(mac);
        heartbeat.setHostname("pc-79");
        heartbeat.setIpAddress(pc.getIpAddress());
        heartbeat.setScmateAvailable(true);
        heartbeat.setScmateAvailabilityReason("ACTIVE");
        heartbeat.setScmateMode("OPERATOR");
        heartbeat.setScmateSchedule("MANUAL");

        when(pcMasterRepository.findById(mac)).thenReturn(Optional.of(pc));
        when(classroomResolutionService.resolveGroupId(pc.getIpAddress(), mac)).thenReturn(null);
        when(classroomResolutionService.resolveRole(null, pc.getIpAddress(), mac))
                .thenReturn(ClassroomResolutionService.PcRole.UNKNOWN);
        when(commandQueueRepository.findNextDispatchCandidates(eq(mac), any())).thenReturn(List.of());
        when(fileDistributionOrchestrator.tryAutoCatchUpOnHeartbeat(any())).thenReturn(Optional.empty());

        heartbeatService.processHeartbeat(heartbeat);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_READY");
        assertThat(pc.getScmateError()).isNull();
    }

    private PcMaster savedPc(String macAddress, String agentVersion) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setPcName("pc");
        pc.setIpAddress("10.10.0.31");
        pc.setStatus("ONLINE");
        pc.setRole("UNKNOWN");
        pc.setAgentVersion(agentVersion);
        return pc;
    }
}
