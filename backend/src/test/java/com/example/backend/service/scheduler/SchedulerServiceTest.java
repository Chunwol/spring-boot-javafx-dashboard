package com.example.backend.service.scheduler;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.alert.OperationalAlertRepository;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.status.OperationalMetricsService;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.realtime.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock TrafficLogsRepository trafficRepo;
    @Mock PcMasterRepository pcRepo;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock OperationalAlertRepository operationalAlertRepository;
    @Mock ClassroomGroupRepository classroomGroupRepository;
    @Mock OperationalMetricsService operationalMetricsService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;
    @Mock FileDistributionOrchestrator fileDistributionOrchestrator;
    @Mock WebSocketService webSocketService;

    @InjectMocks SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(schedulerService, "commandTimeoutSeconds", 45L);
        ReflectionTestUtils.setField(schedulerService, "commandQueueTtlSeconds", 300L);
        ReflectionTestUtils.setField(schedulerService, "maxCommandRetries", 3);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOfflineTimeoutSeconds", 120L);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOfflineSilenceSeconds", 40L);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOnlineTimeoutSeconds", 240L);
        ReflectionTestUtils.setField(schedulerService, "heartbeatTimeoutMinutes", 5L);

        ObjectMapper realMapper = new ObjectMapper();
        lenient().when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> realMapper.readTree(invocation.getArgument(0, String.class)));
        lenient().when(operationalMetricsService.snapshot()).thenReturn(java.util.Map.of());
        lenient().when(operationalMetricsService.snapshotRecent(anyInt())).thenReturn(java.util.Map.of());
    }

    @Test
    void armPostExecutionVerification_rebootCompleted_setsPendingOffline() {
        CommandQueue cmd = new CommandQueue();
        cmd.setCommandType("REBOOT");
        cmd.setStatus("COMPLETED");
        cmd.setExitCode(0);

        LocalDateTime now = LocalDateTime.of(2026, 4, 2, 12, 0, 0);
        schedulerService.armPostExecutionVerification(cmd, now);

        assertThat(cmd.getVerificationStatus()).isEqualTo("PENDING_OFFLINE");
        assertThat(cmd.getVerificationStartedAt()).isEqualTo(now);
        assertThat(cmd.getVerificationDeadlineAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void reconcilePowerStateVerification_shutdownOfflineObserved_confirms() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(10L);
        cmd.setMacAddress("AA:BB:CC:DD:EE:FF");
        cmd.setCommandType("SHUTDOWN");
        cmd.setVerificationStatus("PENDING_OFFLINE");
        cmd.setVerificationDeadlineAt(LocalDateTime.now().plusSeconds(30));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("OFFLINE");

        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcilePowerStateVerificationNow();

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo("CONFIRMED");
        assertThat(captor.getValue().getVerificationCompletedAt()).isNotNull();
    }

    @Test
    void reconcileStaleCommands_ackedShutdownWithoutResult_confirmsOfflineWithoutRetry() {
        LocalDateTime now = LocalDateTime.now();
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(13L);
        cmd.setMacAddress("AA:BB:CC:DD:EE:13");
        cmd.setCommandType("SHUTDOWN");
        cmd.setStatus("ACKED");
        cmd.setAckedAt(now.minusMinutes(2));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("OFFLINE");

        when(commandQueueRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("QUEUED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                org.mockito.ArgumentMatchers.eq("DELIVERED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                org.mockito.ArgumentMatchers.eq("ACKED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of(cmd));
        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcileStaleCommandsNow();

        assertThat(cmd.getStatus()).isEqualTo("COMPLETED");
        assertThat(cmd.getExitCode()).isZero();
        assertThat(cmd.getIsExecuted()).isTrue();
        assertThat(cmd.getRetryCount()).isZero();
        assertThat(cmd.getVerificationStatus()).isEqualTo("CONFIRMED");
        assertThat(cmd.getVerificationCompletedAt()).isNotNull();
        assertThat(cmd.getStderr()).contains("verifying device state");
    }

    @Test
    void reconcileStaleCommands_expiredQueuedCommand_isCancelledWithoutRetry() {
        CommandQueue command = new CommandQueue();
        command.setTaskId(14L);
        command.setMacAddress("AA:BB:CC:DD:EE:14");
        command.setCommandType("SHUTDOWN");
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(commandQueueRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("QUEUED"), any(LocalDateTime.class)))
                .thenReturn(List.of(command));
        when(commandQueueRepository.cancelQueuedIfPending(
                org.mockito.ArgumentMatchers.eq(14L), any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(commandQueueRepository.findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                org.mockito.ArgumentMatchers.eq("DELIVERED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                org.mockito.ArgumentMatchers.eq("ACKED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM")))
                .thenReturn(List.of());

        schedulerService.reconcileStaleCommandsNow();

        assertThat(command.getStatus()).isEqualTo("CANCELLED");
        assertThat(command.getRetryCount()).isZero();
        assertThat(command.getStderr()).contains("queue TTL 300s");
        verify(commandQueueRepository).cancelQueuedIfPending(
                org.mockito.ArgumentMatchers.eq(14L), any(LocalDateTime.class), anyString());
    }

    @Test
    void reconcileStaleCommands_expiredQueuedScmateCommand_cancelsWorkflowWithoutFalseFailure() {
        CommandQueue command = new CommandQueue();
        command.setTaskId(15L);
        command.setMacAddress("AA:BB:CC:DD:EE:15");
        command.setCommandType("SCMATE_CONTROL");
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(command.getMacAddress());
        pc.setScmateWorkflowStage("INSTALL_REQUESTED");
        pc.setScmateError("old error");

        when(commandQueueRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("QUEUED"), any(LocalDateTime.class)))
                .thenReturn(List.of(command));
        when(commandQueueRepository.cancelQueuedIfPending(
                org.mockito.ArgumentMatchers.eq(15L), any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(commandQueueRepository.findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                org.mockito.ArgumentMatchers.eq("DELIVERED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                org.mockito.ArgumentMatchers.eq("ACKED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM")))
                .thenReturn(List.of());
        when(pcRepo.findById(command.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcileStaleCommandsNow();

        assertThat(command.getStatus()).isEqualTo("CANCELLED");
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("CANCELLED");
        assertThat(pc.getScmateError()).isNull();
        verify(pcRepo).save(pc);
    }

    @Test
    void reconcileStaleCommands_scmateCommandTimesOut_failsWithoutRetry() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(130L);
        cmd.setMacAddress("AA:BB:CC:DD:EE:30");
        cmd.setCommandType("SCMATE_CONTROL");
        cmd.setStatus("ACKED");
        cmd.setAckedAt(LocalDateTime.now().minusMinutes(2));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setScmateWorkflowStage("RESTORE_REQUESTED");

        when(commandQueueRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("QUEUED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                org.mockito.ArgumentMatchers.eq("DELIVERED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                org.mockito.ArgumentMatchers.eq("ACKED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(List.of(cmd));
        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM")))
                .thenReturn(List.of());
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcileStaleCommandsNow();

        assertThat(cmd.getStatus()).isEqualTo("FAILED");
        assertThat(cmd.getRetryCount()).isZero();
        assertThat(cmd.getStderr()).contains("non-retryable SCMATE command timed out");
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("FAILED");
        assertThat(pc.getScmateError()).contains("응답 시간이 초과");
        verify(commandQueueRepository).save(cmd);
        verify(pcRepo).save(pc);
    }

    @Test
    void reconcileStaleCommands_scmateWorkflowRebootBeforeAck_failsWithoutRetry() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(131L);
        cmd.setMacAddress("AA:BB:CC:DD:EE:31");
        cmd.setCommandType("REBOOT");
        cmd.setPayloadJson("{\"scmateWorkflowId\":\"workflow-1\",\"scmateAction\":\"REBOOT_TO_OPERATOR\"}");
        cmd.setStatus("DELIVERED");
        cmd.setDeliveredAt(LocalDateTime.now().minusMinutes(2));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setScmateWorkflowStage("WAITING_REBOOT");

        when(commandQueueRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq("QUEUED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                org.mockito.ArgumentMatchers.eq("DELIVERED"), any(LocalDateTime.class))).thenReturn(List.of(cmd));
        when(commandQueueRepository.findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                org.mockito.ArgumentMatchers.eq("ACKED"), any(LocalDateTime.class))).thenReturn(List.of());
        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM")))
                .thenReturn(List.of());
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcileStaleCommandsNow();

        assertThat(cmd.getStatus()).isEqualTo("FAILED");
        assertThat(cmd.getRetryCount()).isZero();
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("FAILED");
        verify(commandQueueRepository).save(cmd);
        verify(pcRepo).save(pc);
    }

    @Test
    void scanForOfflinePcs_broadcastsDashboardUpdateAfterBulkTransition() {
        PcMaster timedOut = new PcMaster();
        timedOut.setMacAddress("AA:BB:CC:DD:EE:14");
        timedOut.setPcName("109-1-01");
        timedOut.setStatus("ONLINE");
        timedOut.setLastSeen(LocalDateTime.now().minusMinutes(10));

        when(pcRepo.findOnlinePcsLastSeenBefore(any(LocalDateTime.class))).thenReturn(List.of(timedOut));
        when(pcRepo.markOfflineByLastSeenBefore(any(LocalDateTime.class))).thenReturn(1);

        schedulerService.scanForOfflinePcs();

        verify(webSocketService).sendDashboardUpdate(
                org.mockito.ArgumentMatchers.argThat(payload ->
                        payload instanceof java.util.Map<?, ?> map
                                && "PC_STATUS".equals(map.get("eventType"))
                                && Integer.valueOf(1).equals(map.get("offlineCount"))));
    }

    @Test
    void reconcilePowerStateVerification_rebootTimeout_marksFailed() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(11L);
        cmd.setMacAddress("11:22:33:44:55:66");
        cmd.setCommandType("REBOOT");
        cmd.setStatus("COMPLETED");
        cmd.setVerificationStatus("PENDING_ONLINE");
        cmd.setVerificationObservedOfflineAt(past.minusMinutes(1));
        cmd.setVerificationDeadlineAt(past);

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("OFFLINE");
        pc.setLastSeen(past.minusMinutes(2));

        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcilePowerStateVerificationNow();

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getStderr()).contains("did not come back online");
    }

    @Test
    void reconcilePowerStateVerification_agentUpdateConfirmed_acknowledgesAlert() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(12L);
        cmd.setMacAddress("AA:11:BB:22:CC:33");
        cmd.setCommandType("AGENT_UPDATE");
        cmd.setVerificationStatus("PENDING_AGENT_RESTART");
        cmd.setPayloadJson("{\"targetVersion\":\"1.2.0\",\"preUpdateSessionId\":\"session-old\"}");
        cmd.setCompletedAt(LocalDateTime.now().minusMinutes(5));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("ONLINE");
        pc.setAgentVersion("1.2.0");
        pc.setLastAgentSessionId("session-new");
        pc.setLastSeen(LocalDateTime.now());

        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcilePowerStateVerificationNow();

        ArgumentCaptor<CommandQueue> commandCaptor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getVerificationStatus()).isEqualTo("CONFIRMED");
        assertThat(commandCaptor.getValue().getVerificationCompletedAt()).isNotNull();

        verify(operationalAlertRepository).acknowledgeUnreadBySourceAndType(
                org.mockito.ArgumentMatchers.eq(cmd.getMacAddress()),
                org.mockito.ArgumentMatchers.eq("UPDATE_REBOOT_REQUIRED"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("system-auto"));
        verify(operationalAlertRepository).acknowledgeUnreadBySourceAndType(
                org.mockito.ArgumentMatchers.eq(cmd.getMacAddress()),
                org.mockito.ArgumentMatchers.eq("UPDATE_RESTART_PENDING"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("system-auto"));
    }

    @Test
    void reconcilePowerStateVerification_fullBundleWaitsForMatchingBundleVersion() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(14L);
        cmd.setMacAddress("AA:11:BB:22:CC:44");
        cmd.setCommandType("AGENT_BUNDLE_UPDATE");
        cmd.setVerificationStatus("PENDING_AGENT_RESTART");
        cmd.setVerificationDeadlineAt(LocalDateTime.now().plusMinutes(2));
        cmd.setPayloadJson("{\"jobId\":77,\"targetVersion\":\"1.1.20\",\"preUpdateSessionId\":\"session-old\"}");
        cmd.setCompletedAt(LocalDateTime.now().minusMinutes(1));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("ONLINE");
        pc.setAgentVersion("1.1.20");
        pc.setBundleVersion("1.1.19");
        pc.setLastAgentSessionId("session-new");
        pc.setLastSeen(LocalDateTime.now());

        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_AGENT_RESTART", "PENDING_REBOOT_CONFIRM")))
                .thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcilePowerStateVerificationNow();

        assertThat(cmd.getVerificationStatus()).isEqualTo("PENDING_AGENT_RESTART");
        verifyNoInteractions(fileDistributionOrchestrator);

        pc.setBundleVersion("1.1.20");
        schedulerService.reconcilePowerStateVerificationNow();

        assertThat(cmd.getVerificationStatus()).isEqualTo("CONFIRMED");
        verify(fileDistributionOrchestrator).reportAgentResult(77L, cmd.getMacAddress(), true, null);
    }

    @Test
    void publishClassroomPowerStateNotifications_emitsInfoOnlyAfterStateChanges() {
        ClassroomGroupRepository.GroupWithPcStatsProjection initial =
                classroomSnapshot(214L, "214호", 41L, 0L);
        ClassroomGroupRepository.GroupWithPcStatsProjection poweredOn =
                classroomSnapshot(214L, "214호", 41L, 1L);
        ClassroomGroupRepository.GroupWithPcStatsProjection poweredOff =
                classroomSnapshot(214L, "214호", 41L, 0L);
        when(classroomGroupRepository.findAllWithPcStats(any(LocalDateTime.class)))
                .thenReturn(List.of(initial), List.of(poweredOn), List.of(poweredOff));

        schedulerService.publishClassroomPowerStateNotifications();
        verifyNoInteractions(eventPublisher);

        schedulerService.publishClassroomPowerStateNotifications();
        schedulerService.publishClassroomPowerStateNotifications();

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AlertEvent::getSeverity, AlertEvent::getAlertType, AlertEvent::getMessage)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INFO", "CLASSROOM_POWER_ON", "214호 강의실 켜짐 (현재 온라인 1대)"),
                        org.assertj.core.groups.Tuple.tuple("INFO", "CLASSROOM_POWER_OFF", "214호 강의실 꺼짐 (현재 온라인 0대)"));
    }

    private ClassroomGroupRepository.GroupWithPcStatsProjection classroomSnapshot(
            Long groupId, String groupName, Long total, Long online) {
        return new ClassroomGroupRepository.GroupWithPcStatsProjection() {
            @Override public Long getGroupId() { return groupId; }
            @Override public String getGroupName() { return groupName; }
            @Override public Long getTotalCount() { return total; }
            @Override public Long getOnlineCount() { return online; }
        };
    }
}
