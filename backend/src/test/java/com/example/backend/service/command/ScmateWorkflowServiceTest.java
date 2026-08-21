package com.example.backend.service.command;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmateWorkflowServiceTest {

    @Mock PcMasterRepository pcMasterRepository;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock CommandPollRegistry commandPollRegistry;
    @Mock HeartbeatService heartbeatService;

    @InjectMocks ScmateWorkflowService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "scmateCliUsername", "configured-user");
        ReflectionTestUtils.setField(service, "scmateCliPassword", "configured-secret");
        ReflectionTestUtils.setField(service, "heartbeatTimeoutMinutes", 2L);
    }

    @Test
    void perform_staleOnlineStatusIsRejectedBeforeQueueing() {
        PcMaster pc = operatorPc();
        pc.setLastSeen(LocalDateTime.now().minusMinutes(3));
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "REQUEST_OPERATOR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("온라인 PC");

        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void updateBaseline_withoutNewSystemBoot_isRejected() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("WAITING_REBOOT");
        pc.setScmateWorkflowStartBootTimeEpochSeconds(200L);
        pc.setSystemBootTimeEpochSeconds(200L);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "UPDATE_BASELINE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("재부팅이 확인되지 않아");

        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void updateBaseline_afterStableManualReboot_queuesSelectedPoint() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("WAITING_REBOOT");
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);
        pc.setSystemBootTimeEpochSeconds(200L);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(91L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "UPDATE_BASELINE", "SNAP1");

        assertThat(result.get("taskId")).isEqualTo(91L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("BASELINE_REQUESTED");
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"UPDATE_BASELINE\"");
        assertThat(captor.getValue().getPayloadJson()).contains("\"recoveryPoint\":\"SNAP1\"");
        assertThat(captor.getValue().getPayloadJson()).doesNotContain("configured-user", "configured-secret");
    }

    @Test
    void updateBaseline_acceptsBootChangeFromAnotherRebootPath() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("OPERATOR_READY");
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);
        pc.setSystemBootTimeEpochSeconds(200L);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(100L);
            return command;
        });

        assertThat(service.perform(pc.getMacAddress(), "UPDATE_BASELINE").get("taskId")).isEqualTo(100L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("BASELINE_REQUESTED");
    }

    @Test
    void rebootForBaseline_isUserSelectableWithoutStableOperatorMode() {
        PcMaster pc = operatorPc();
        pc.setScmateMode("PROTECTED");
        pc.setScmateWorkflowStage("OPERATOR_READY");
        pc.setScmateTransitionPending(false);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.perform(pc.getMacAddress(), "REBOOT_FOR_BASELINE");

        verify(commandQueueRepository).save(any(CommandQueue.class));
    }

    @Test
    void rebootForBaseline_afterOperatorModeChange_isQueued() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("OPERATOR_READY");
        pc.setScmateTransitionPending(false);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(92L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "REBOOT_FOR_BASELINE");

        assertThat(result.get("taskId")).isEqualTo(92L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("WAITING_REBOOT");
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getCommandType()).isEqualTo("REBOOT");
        assertThat(captor.getValue().getPayloadJson()).contains("REBOOT_FOR_BASELINE");
    }

    @Test
    void dispatchNextCommand_alwaysStartsIndependentTransaction() throws Exception {
        Transactional transactional = HeartbeatService.class
                .getMethod("dispatchNextCommand", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void requestProtected_recordsBootTimeBeforeCommandCanAutoReboot() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("BASELINE_UPDATED");
        pc.setLastAgentSessionId("session-before-protection");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(93L);
            return command;
        });

        service.perform(pc.getMacAddress(), "REQUEST_PROTECTED");

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("PROTECTION_REQUESTED");
        assertThat(pc.getScmateWorkflowStartBootTimeEpochSeconds()).isEqualTo(200L);
    }

    @Test
    void requestProtected_canCancelOperatorWorkWithoutUpdatingBaseline() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("OPERATOR_READY");
        pc.setLastAgentSessionId("operator-session");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(96L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "REQUEST_PROTECTED");

        assertThat(result.get("taskId")).isEqualTo(96L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("PROTECTION_REQUESTED");
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"REQUEST_PROTECTED\"");
        assertThat(captor.getValue().getPayloadJson()).contains("\"recoveryPoint\":\"BASELINE\"");
    }

    @Test
    void rebootToProtected_isAvailableWhileAlreadyProtectedToDiscardChanges() {
        PcMaster pc = protectedPc("1.1.39");
        pc.setScmateWorkflowStage("BASELINE_UPDATED");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(101L);
            return command;
        });

        assertThat(service.perform(pc.getMacAddress(), "REBOOT_TO_PROTECTED").get("taskId")).isEqualTo(101L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("WAITING_PROTECTED_REBOOT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestOperator_expiredLongPollRequeuesUnackedCommand() {
        PcMaster pc = protectedPc("1.1.39");
        pc.setScmateWorkflowStage("COMPLETED");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(97L);
            return command;
        });
        var waiter = (org.springframework.web.context.request.async.DeferredResult<
                org.springframework.http.ResponseEntity<?>>) org.mockito.Mockito.mock(
                org.springframework.web.context.request.async.DeferredResult.class);
        when(commandPollRegistry.take("AABBCCDDEEFF")).thenReturn(waiter);
        when(heartbeatService.dispatchNextCommand(pc.getMacAddress())).thenReturn(java.util.Map.of("taskId", 97L));
        when(waiter.setResult(any())).thenReturn(false);

        service.perform(pc.getMacAddress(), "REQUEST_OPERATOR");

        verify(heartbeatService).requeueDeliveredIfUnacked(97L);
    }

    @Test
    void requestOperator_afterSavedBaselineAlreadyInProtectedMode_isQueued() {
        PcMaster pc = protectedPc("1.1.39");
        pc.setScmateWorkflowStage("BASELINE_UPDATED");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(98L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "REQUEST_OPERATOR");

        assertThat(result.get("taskId")).isEqualTo(98L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_REQUESTED");
    }

    @Test
    void requestOperator_doesNotDependOnStaleWorkflowStage() {
        PcMaster pc = protectedPc("1.1.39");
        pc.setScmateWorkflowStage("WAITING_REBOOT");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(99L);
            return command;
        });

        assertThat(service.perform(pc.getMacAddress(), "REQUEST_OPERATOR").get("taskId")).isEqualTo(99L);
    }

    @Test
    void restoreBaseline_queuesActionAndRecordsBootTime() {
        PcMaster pc = protectedPc("1.1.35");
        pc.setScmateWorkflowStage("COMPLETED");
        pc.setLastAgentSessionId("session-before-restore");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(94L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "RESTORE_BASELINE");

        assertThat(result.get("taskId")).isEqualTo(94L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("RESTORE_REQUESTED");
        assertThat(pc.getScmateWorkflowStartBootTimeEpochSeconds()).isEqualTo(200L);
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getCommandType()).isEqualTo("SCMATE_CONTROL");
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"RESTORE_BASELINE\"");
        assertThat(captor.getValue().getPayloadJson()).contains("\"recoveryPoint\":\"BASELINE\"");
    }

    @Test
    void restoreBaseline_stableOperatorPc_isAlsoAllowed() {
        PcMaster pc = operatorPc();
        pc.setAgentVersion("1.1.35");
        pc.setLastAgentSessionId("operator-session");
        pc.setScmateWorkflowStage("WAITING_REBOOT");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(95L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "RESTORE_BASELINE");

        assertThat(result.get("taskId")).isEqualTo(95L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("RESTORE_REQUESTED");
    }

    @Test
    void updateBaseline_successRecordsVerifiedSaveTime() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("BASELINE_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"UPDATE_BASELINE\",\"recoveryPoint\":\"BASELINE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true,
                "{\"selectedPoint\":{\"point\":\"BASELINE\",\"createdAt\":\"2026-08-13T10:11:12\"}}",
                null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("BASELINE_UPDATED");
        assertThat(pc.getScmateBaselineCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 8, 13, 10, 11, 12));
        assertThat(pc.getScmateBaselineUpdatedAt()).isEqualTo(pc.getScmateBaselineCreatedAt());
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void updateBaseline_withoutAgentCreatedAt_remainsUnverified() {
        PcMaster pc = operatorPc();
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"UPDATE_BASELINE\",\"recoveryPoint\":\"SNAP2\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true, "{\"selectedPoint\":\"SNAP2\"}", null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("RECOVERY_POINT_UNVERIFIED");
        assertThat(pc.getScmateSnap2CreatedAt()).isNull();
        assertThat(pc.getScmateBaselineUpdatedAt()).isNull();
        assertThat(pc.getScmateError()).contains("createdAt");
    }

    @Test
    void operatorResult_acceptsUnknownSelectedRecoveryPoint() {
        PcMaster pc = operatorPc();
        pc.setScmateSelectedPoint("BASELINE");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"REQUEST_OPERATOR\",\"recoveryPoint\":\"BASELINE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true,
                "{\"after\":{\"mode\":\"OPERATOR\",\"schedule\":\"MANUAL\","
                        + "\"selectedPoint\":\"UNKNOWN\",\"raw\":3}}", "");

        assertThat(pc.getScmateMode()).isEqualTo("OPERATOR");
        assertThat(pc.getScmateSchedule()).isEqualTo("MANUAL");
        assertThat(pc.getScmateSelectedPoint()).isEqualTo("UNKNOWN");
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void detailRefreshPrefersCliScheduleOverStaleClientMode() {
        PcMaster pc = operatorPc();
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"REFRESH_STATUS_DETAIL\",\"recoveryPoint\":\"BASELINE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true,
                "{\"after\":{\"mode\":\"OPERATOR\",\"schedule\":\"MANUAL\",\"raw\":3},"
                        + "\"details\":{\"schedule\":\"RESTART\",\"selectedPoint\":\"BASELINE\","
                        + "\"stateSource\":\"SCMATE_CLIENT_MEMORY+SHD_API+SHD_CMD\"}}", "");

        assertThat(pc.getScmateMode()).isEqualTo("PROTECTED");
        assertThat(pc.getScmateSchedule()).isEqualTo("RESTART");
        assertThat(pc.getScmateSelectedPoint()).isEqualTo("BASELINE");
        assertThat(pc.getScmateStateSource()).contains("SHD_CMD");
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("STATUS_DETAIL_UPDATED");
    }

    @Test
    void updateBaseline_parsesActualAgentDetailsObjectAndSelectedPointCreatedAt() {
        PcMaster pc = operatorPc();
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"UPDATE_BASELINE\",\"recoveryPoint\":\"SNAP1\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        String stdout = "{\"action\":\"UPDATE_BASELINE\",\"before\":{},\"after\":{},"
                + "\"details\":{\"baselineCreatedAt\":\"2026-08-13T09:00:00\","
                + "\"snap1CreatedAt\":\"2026-08-13T10:00:00\","
                + "\"snap2CreatedAt\":\"2026-08-13T11:00:00\"}}";

        service.onCommandResult(command, true, stdout, null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("BASELINE_UPDATED");
        assertThat(pc.getScmateSelectedPoint()).isEqualTo("SNAP1");
        assertThat(pc.getScmateBaselineCreatedAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 13, 9, 0));
        assertThat(pc.getScmateSnap1CreatedAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 13, 10, 0));
        assertThat(pc.getScmateSnap2CreatedAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 13, 11, 0));
        assertThat(pc.getScmateBaselineUpdatedAt()).isNull();
    }

    @Test
    void restoreBaseline_oldAgentVersion_isRejected() {
        PcMaster pc = protectedPc("1.1.23");
        pc.setScmateWorkflowStage("IDLE");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "RESTORE_BASELINE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("에이전트 1.1.35 이상");

        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void requestOperator_oldAgentVersion_isRejected() {
        PcMaster pc = protectedPc("1.1.38");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "REQUEST_OPERATOR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("에이전트 1.1.39 이상");

        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void refreshStatusDetail_requiresAgent135AndCarriesRecoveryPoint() {
        PcMaster pc = operatorPc();
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.perform(pc.getMacAddress(), "REFRESH_STATUS_DETAIL", "SNAP2");

        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"REFRESH_STATUS_DETAIL\"");
        assertThat(captor.getValue().getPayloadJson()).contains("\"recoveryPoint\":\"SNAP2\"");

        PcMaster oldPc = operatorPc();
        oldPc.setAgentVersion("1.1.34");
        when(pcMasterRepository.findById("11:22:33:44:55:66")).thenReturn(Optional.of(oldPc));
        oldPc.setMacAddress("11:22:33:44:55:66");
        assertThatThrownBy(() -> service.perform(oldPc.getMacAddress(), "REFRESH_STATUS_DETAIL"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("에이전트 1.1.35 이상");
    }

    @Test
    void restoreBaseline_withoutSystemBootTime_isRejected() {
        PcMaster pc = protectedPc("1.1.35");
        pc.setScmateWorkflowStage("IDLE");
        pc.setSystemBootTimeEpochSeconds(null);
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "RESTORE_BASELINE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("부팅 시각");

        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void restoreBaseline_commandSuccess_doesNotCompleteBeforeNewSessionHeartbeat() {
        PcMaster pc = protectedPc("1.1.24");
        pc.setScmateWorkflowStage("RESTORE_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"RESTORE_BASELINE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true, null, null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("RESTORE_REQUESTED");
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void restoreBaseline_commandFailure_marksWorkflowFailedWithVisibleError() {
        PcMaster pc = protectedPc("1.1.24");
        pc.setScmateWorkflowStage("RESTORE_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"RESTORE_BASELINE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, false, null, null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("FAILED");
        assertThat(pc.getScmateError()).isEqualTo("시스템 복구 명령 실행에 실패했습니다.");
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void installRecoveryModule_unavailablePcQueuesInstallerWithoutCredential() {
        PcMaster pc = operatorPc();
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("NOT_INSTALLED");
        pc.setScmateMode("UNKNOWN");
        pc.setAgentVersion("1.1.28");
        pc.setLastAgentSessionId("before-install");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(102L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "INSTALL_RECOVERY_MODULE");

        assertThat(result.get("taskId")).isEqualTo(102L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("INSTALL_REQUESTED");
        assertThat(pc.getScmateWorkflowStartBootTimeEpochSeconds()).isEqualTo(200L);
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"INSTALL_RECOVERY_MODULE\"");
        assertThat(captor.getValue().getPayloadJson()).contains("\"recoveryPoint\":\"BASELINE\"");
        assertThat(captor.getValue().getPayloadJson()).doesNotContain("configured-user", "configured-secret");
    }

    @Test
    void installRecoveryModule_installedPcIsRejected() {
        PcMaster pc = operatorPc();
        pc.setAgentVersion("1.1.28");
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("LICENSE_EXPIRED");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "INSTALL_RECOVERY_MODULE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("설치안됨 상태");
        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void directScmateAction_teacherPcIsRejected() {
        PcMaster pc = operatorPc();
        pc.setRole("TEACHER");
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("NOT_INSTALLED");
        pc.setAgentVersion("1.1.29");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> service.perform(pc.getMacAddress(), "INSTALL_RECOVERY_MODULE"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("학생 PC");
        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void installRecoveryModule_successWaitsForExplicitReboot() {
        PcMaster pc = operatorPc();
        pc.setScmateAvailable(false);
        pc.setScmateWorkflowStage("INSTALL_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"INSTALL_RECOVERY_MODULE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true, null, null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("INSTALL_PENDING_REBOOT");
        assertThat(pc.getScmateError()).isNull();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void cancelledScmateCommand_marksWorkflowCancelledWithoutFalseError() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("INSTALL_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"INSTALL_RECOVERY_MODULE\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandCancelled(command);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("CANCELLED");
        assertThat(pc.getScmateError()).isNull();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void cancelledScmateReboot_marksWorkflowCancelled() {
        PcMaster pc = operatorPc();
        pc.setScmateWorkflowStage("WAITING_REBOOT");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("REBOOT");
        command.setPayloadJson("{\"scmateWorkflowId\":\"workflow-1\",\"scmateAction\":\"REBOOT_TO_OPERATOR\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandCancelled(command);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("CANCELLED");
        assertThat(pc.getScmateError()).isNull();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void enableProtection_disabledPcQueuesAuthenticatedAgentAction() {
        PcMaster pc = operatorPc();
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("PROTECT_DISABLED");
        pc.setScmateMode("UNKNOWN");
        pc.setAgentVersion("1.1.40");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                pc.getMacAddress(), List.of("QUEUED", "DELIVERED", "ACKED"))).thenReturn(List.of());
        when(commandQueueRepository.save(any(CommandQueue.class))).thenAnswer(invocation -> {
            CommandQueue command = invocation.getArgument(0);
            command.setTaskId(103L);
            return command;
        });

        var result = service.perform(pc.getMacAddress(), "ENABLE_PROTECTION");

        assertThat(result.get("taskId")).isEqualTo(103L);
        assertThat(pc.getScmateWorkflowStage()).isEqualTo("PROTECTION_ENABLE_REQUESTED");
        var captor = org.mockito.ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("\"action\":\"ENABLE_PROTECTION\"");
        assertThat(captor.getValue().getPayloadJson()).doesNotContain("configured-secret");
    }

    @Test
    void automaticProtectionRecoveryReturnsToOperatorWorkflowAfterCommandResult() {
        PcMaster pc = operatorPc();
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("PROTECT_DISABLED");
        pc.setScmateMode("UNKNOWN");
        pc.setScmateWorkflowStage("OPERATOR_ENABLE_REQUESTED");
        CommandQueue command = new CommandQueue();
        command.setMacAddress(pc.getMacAddress());
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"ENABLE_PROTECTION\","
                + "\"recoveryPoint\":\"BASELINE\",\"resumeAction\":\"REQUEST_OPERATOR\"}");
        when(pcMasterRepository.findById(pc.getMacAddress())).thenReturn(Optional.of(pc));

        service.onCommandResult(command, true, null, null);

        assertThat(pc.getScmateWorkflowStage()).isEqualTo("OPERATOR_ENABLE_PENDING");
        assertThat(pc.getScmateError()).isNull();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void enableProtection_rejectsActiveOrOldAgent() {
        PcMaster active = operatorPc();
        active.setScmateAvailabilityReason("ACTIVE");
        active.setAgentVersion("1.1.40");
        when(pcMasterRepository.findById(active.getMacAddress())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.perform(active.getMacAddress(), "ENABLE_PROTECTION"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("보호 비활성");

        active.setScmateAvailable(false);
        active.setScmateAvailabilityReason("PROTECT_DISABLED");
        active.setAgentVersion("1.1.39");
        assertThatThrownBy(() -> service.perform(active.getMacAddress(), "ENABLE_PROTECTION"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1.1.40");
    }

    private PcMaster operatorPc() {
        PcMaster pc = new PcMaster();
        pc.setMacAddress("AA:BB:CC:DD:EE:FF");
        pc.setStatus("ONLINE");
        pc.setLastSeen(LocalDateTime.now());
        pc.setScmateAvailable(true);
        pc.setScmateMode("OPERATOR");
        pc.setScmateSchedule("MANUAL");
        pc.setScmateTransitionPending(false);
        pc.setAgentVersion("1.1.39");
        pc.setSystemBootTimeEpochSeconds(200L);
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);
        return pc;
    }

    private PcMaster protectedPc(String agentVersion) {
        PcMaster pc = operatorPc();
        pc.setScmateMode("PROTECTED");
        pc.setScmateSchedule("RESTART");
        pc.setAgentVersion(agentVersion);
        pc.setLastAgentSessionId("active-session");
        return pc;
    }
}
