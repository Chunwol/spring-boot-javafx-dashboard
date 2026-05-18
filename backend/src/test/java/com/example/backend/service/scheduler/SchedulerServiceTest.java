package com.example.backend.service.scheduler;

import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.alert.OperationalAlertRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.status.OperationalMetricsService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock TrafficLogsRepository trafficRepo;
    @Mock PcMasterRepository pcRepo;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock OperationalAlertRepository operationalAlertRepository;
    @Mock OperationalMetricsService operationalMetricsService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;

    @InjectMocks SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(schedulerService, "commandTimeoutSeconds", 45L);
        ReflectionTestUtils.setField(schedulerService, "maxCommandRetries", 3);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOfflineTimeoutSeconds", 120L);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOfflineSilenceSeconds", 40L);
        ReflectionTestUtils.setField(schedulerService, "commandVerifyOnlineTimeoutSeconds", 240L);

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
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
        when(pcRepo.findById(cmd.getMacAddress())).thenReturn(Optional.of(pc));

        schedulerService.reconcilePowerStateVerificationNow();

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo("CONFIRMED");
        assertThat(captor.getValue().getVerificationCompletedAt()).isNotNull();
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
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
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
        cmd.setVerificationStatus("PENDING_REBOOT_CONFIRM");
        cmd.setPayloadJson("{\"targetVersion\":\"1.2.0\",\"preUpdateSessionId\":\"session-old\"}");
        cmd.setCompletedAt(LocalDateTime.now().minusMinutes(5));

        PcMaster pc = new PcMaster();
        pc.setMacAddress(cmd.getMacAddress());
        pc.setStatus("ONLINE");
        pc.setAgentVersion("1.2.0");
        pc.setLastAgentSessionId("session-new");
        pc.setLastSeen(LocalDateTime.now());

        when(commandQueueRepository.findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(
                List.of("PENDING_OFFLINE", "PENDING_ONLINE", "PENDING_REBOOT_CONFIRM"))).thenReturn(List.of(cmd));
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
    }
}
