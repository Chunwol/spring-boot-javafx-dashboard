package com.example.backend.service.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.common.exception.ConflictException;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.example.backend.service.heartbeat.HeartbeatService;
import com.example.backend.service.scheduler.SchedulerService;
import com.example.backend.service.status.OperationalMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandLifecycleServiceTest {

    @Mock CommandQueueRepository commandQueueRepository;
    @Mock PcMasterRepository pcMasterRepository;
    @Mock ClassroomGroupRepository classroomGroupRepository;
    @Mock ClassroomIpRangeRepository classroomIpRangeRepository;
    @Mock AuditService auditService;
    @Mock ObjectMapper objectMapper;
    @Mock SchedulerService schedulerService;
    @Mock FileDistributionOrchestrator fileDistributionOrchestrator;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock OperationalMetricsService operationalMetricsService;
    @Mock HeartbeatService heartbeatService;
    @Mock CommandPollRegistry commandPollRegistry;
    @Mock RouterWolService routerWolService;
    @Mock ScmateWorkflowService scmateWorkflowService;

    @InjectMocks CommandLifecycleService commandLifecycleService;

    @Test
    void cancelQueuedCommand_marksAnyQueuedCommandCancelledBeforeDelivery() {
        CommandQueue command = new CommandQueue();
        command.setTaskId(77L);
        command.setMacAddress("AA:BB:CC:DD:EE:77");
        command.setCommandType("RUN_PS1");
        command.setStatus("QUEUED");
        when(commandQueueRepository.findById(77L)).thenReturn(java.util.Optional.of(command));
        when(commandQueueRepository.cancelQueuedIfPending(
                eq(77L), any(LocalDateTime.class), any(String.class))).thenReturn(1);

        Map<String, Object> result = commandLifecycleService.cancelQueuedCommand(77L);

        assertThat(result).containsEntry("taskId", 77L).containsEntry("status", "CANCELLED");
        assertThat(command.getStatus()).isEqualTo("CANCELLED");
        assertThat(command.getCompletedAt()).isNotNull();
        assertThat(command.getStderr()).contains("before delivery");
        verify(commandQueueRepository).cancelQueuedIfPending(
                eq(77L), any(LocalDateTime.class), any(String.class));
        verify(scmateWorkflowService).onCommandCancelled(command);
    }

    @Test
    void cancelQueuedCommand_rejectsAlreadyDeliveredCommand() {
        CommandQueue command = new CommandQueue();
        command.setTaskId(78L);
        command.setStatus("DELIVERED");
        when(commandQueueRepository.findById(78L)).thenReturn(java.util.Optional.of(command));

        assertThatThrownBy(() -> commandLifecycleService.cancelQueuedCommand(78L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only QUEUED commands");
        verify(commandQueueRepository, never()).cancelQueuedIfPending(
                any(), any(LocalDateTime.class), any(String.class));
    }

    @Test
    void enqueueGroupShutdown_recentEquivalentCommand_skipsDuplicate() {
        PcMaster pc = pc("AA:BB:CC:DD:EE:01", "ONLINE");
        when(pcMasterRepository.findByGroupId(29L)).thenReturn(List.of(pc));
        when(commandQueueRepository.countRecentEquivalentPowerCommand(
                eq(pc.getMacAddress()), eq("SHUTDOWN"), isNull(), any(LocalDateTime.class))).thenReturn(1L);

        Map<String, Object> result = commandLifecycleService.enqueue(groupCommand("SHUTDOWN"));

        assertThat(result.get("queued")).isEqualTo(0);
        assertThat(result.get("duplicateSkipped")).isEqualTo(1);
        assertThat(result.get("offlineSkipped")).isEqualTo(0);
        verify(commandQueueRepository, never()).saveAll(any());
    }

    @Test
    void enqueueGroupShutdown_offlinePc_doesNotQueueCommand() {
        PcMaster pc = pc("AA:BB:CC:DD:EE:02", "OFFLINE");
        when(pcMasterRepository.findByGroupId(29L)).thenReturn(List.of(pc));

        Map<String, Object> result = commandLifecycleService.enqueue(groupCommand("SHUTDOWN"));

        assertThat(result.get("queued")).isEqualTo(0);
        assertThat(result.get("duplicateSkipped")).isEqualTo(0);
        assertThat(result.get("offlineSkipped")).isEqualTo(1);
        verify(commandQueueRepository, never()).saveAll(any());
    }

    @Test
    void enqueueMultiShutdown_savesAllEligibleTargetsInOneBatch() {
        PcMaster first = pc("AA:BB:CC:DD:EE:11", "ONLINE");
        PcMaster second = pc("AA:BB:CC:DD:EE:12", "ONLINE");
        PcMaster offline = pc("AA:BB:CC:DD:EE:13", "OFFLINE");
        when(pcMasterRepository.findAll()).thenReturn(List.of(first, second, offline));
        when(commandQueueRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CommandQueue> commands = invocation.getArgument(0);
            for (int index = 0; index < commands.size(); index++) {
                commands.get(index).setTaskId((long) index + 1);
            }
            return commands;
        });

        CommandRequestDto request = new CommandRequestDto();
        request.setScope("MULTI");
        request.setType("SHUTDOWN");
        request.setTargetMacs(List.of(
                first.getMacAddress(), second.getMacAddress(), offline.getMacAddress(),
                "AA:BB:CC:DD:EE:99"));

        Map<String, Object> result = commandLifecycleService.enqueue(request);

        assertThat(result.get("requested")).isEqualTo(4);
        assertThat(result.get("queued")).isEqualTo(2);
        assertThat(result.get("offlineSkipped")).isEqualTo(1);
        assertThat(result.get("notFoundSkipped")).isEqualTo(1);
        verify(commandQueueRepository).saveAll(any());
    }

    @Test
    void enqueueRecordsAuthenticatedAccountAndRequestIp() {
        PcMaster pc = pc("AA:BB:CC:DD:EE:14", "ONLINE");
        when(pcMasterRepository.findByGroupId(29L)).thenReturn(List.of(pc));
        when(commandQueueRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CommandQueue> commands = invocation.getArgument(0);
            commands.getFirst().setTaskId(1L);
            return commands;
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.44");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, List.of()));
        try {
            commandLifecycleService.enqueue(groupCommand("SHUTDOWN"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<CommandQueue>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(commandQueueRepository).saveAll(captor.capture());
        assertThat(captor.getValue().getFirst().getRequestedBy()).isEqualTo("operator");
        assertThat(captor.getValue().getFirst().getRequestedIp()).isEqualTo("192.0.2.44");
    }

    @Test
    void enqueueToIpShutdown_usesTheSameOfflineGuardAsOtherScopes() {
        PcMaster offline = pc("AA:BB:CC:DD:EE:21", "OFFLINE");
        offline.setIpAddress("192.0.2.21");
        when(pcMasterRepository.findByIpAddress("192.0.2.21")).thenReturn(java.util.Optional.of(offline));
        when(pcMasterRepository.findById(offline.getMacAddress())).thenReturn(java.util.Optional.of(offline));

        Map<String, Object> result = commandLifecycleService.enqueueToIp(
                "192.0.2.21", "SHUTDOWN", null, null, null);

        assertThat(result.get("queued")).isEqualTo(0);
        assertThat(result.get("offlineSkipped")).isEqualTo(1);
        verify(commandQueueRepository, never()).saveAll(any());
    }

    @Test
    void findCommandsByIds_returnsResultsInRequestedOrderWithOneBatchLookup() {
        CommandQueue first = new CommandQueue();
        first.setTaskId(10L);
        first.setMacAddress("AA:BB:CC:DD:EE:10");
        first.setStatus("COMPLETED");
        CommandQueue second = new CommandQueue();
        second.setTaskId(20L);
        second.setMacAddress("AA:BB:CC:DD:EE:20");
        second.setStatus("ACKED");
        when(commandQueueRepository.findAllById(List.of(20L, 10L))).thenReturn(List.of(first, second));
        when(pcMasterRepository.findAllById(any())).thenReturn(List.of());
        when(classroomGroupRepository.findAllById(any())).thenReturn(List.of());

        List<Map<String, Object>> result = commandLifecycleService.findCommandsByIds(List.of(20L, 10L, 20L));

        assertThat(result).extracting(row -> row.get("taskId")).containsExactly(20L, 10L);
        verify(commandQueueRepository).findAllById(List.of(20L, 10L));
    }

    @Test
    void restoreResult_waitsForVerifiedAgentRestartInsteadOfCompletingImmediately() {
        ReflectionTestUtils.setField(commandLifecycleService, "objectMapper", new ObjectMapper());
        CommandQueue command = new CommandQueue();
        command.setTaskId(30L);
        command.setMacAddress("AA:BB:CC:DD:EE:30");
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"RESTORE_BASELINE\"}");
        command.setStatus("ACKED");
        command.setAckedAt(LocalDateTime.now());
        when(commandQueueRepository.findById(30L)).thenReturn(java.util.Optional.of(command));

        Map<String, Object> result = commandLifecycleService.result(
                30L, command.getMacAddress(), Map.of("exitCode", 0, "stdout", "requested", "stderr", ""));

        assertThat(result).containsEntry("awaitingAgentRestart", true)
                .containsEntry("commandStatus", "ACKED");
        assertThat(command.getStatus()).isEqualTo("ACKED");
        assertThat(command.getCompletedAt()).isNull();
        assertThat(command.getIsExecuted()).isFalse();
        verify(commandQueueRepository, never()).markResultIfInProgress(
                any(), any(), any(), any(), any(), any());
        verify(scmateWorkflowService, never()).onCommandResult(any(), eq(true), any(), any());
    }

    @Test
    void scmateResult_forwardsStdoutForDetailedPointVerification() {
        CommandQueue command = new CommandQueue();
        command.setTaskId(31L);
        command.setMacAddress("AA:BB:CC:DD:EE:31");
        command.setCommandType("SCMATE_CONTROL");
        command.setPayloadJson("{\"action\":\"UPDATE_BASELINE\",\"recoveryPoint\":\"SNAP1\"}");
        command.setStatus("ACKED");
        String stdout = "{\"snap1CreatedAt\":\"2026-08-13T10:00:00\"}";
        when(commandQueueRepository.findById(31L)).thenReturn(java.util.Optional.of(command));
        when(commandQueueRepository.markResultIfInProgress(
                eq(31L), eq("COMPLETED"), eq(0), eq(stdout), eq(""), any(LocalDateTime.class)))
                .thenReturn(1);

        commandLifecycleService.result(31L, command.getMacAddress(),
                Map.of("exitCode", 0, "stdout", stdout, "stderr", ""));

        verify(scmateWorkflowService).onCommandResult(command, true, stdout, "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyWaitingAgents_expiredWaiterRequeuesUnackedCommand() {
        String mac = "AA:BB:CC:DD:EE:77";
        var waiter = (org.springframework.web.context.request.async.DeferredResult<
                org.springframework.http.ResponseEntity<?>>) org.mockito.Mockito.mock(
                org.springframework.web.context.request.async.DeferredResult.class);
        when(commandPollRegistry.take("AABBCCDDEE77")).thenReturn(waiter);
        when(heartbeatService.dispatchNextCommand(mac)).thenReturn(Map.of("taskId", 77L));
        when(waiter.setResult(any())).thenReturn(false);

        commandLifecycleService.notifyWaitingAgentsAfterCommit(List.of(mac));

        verify(heartbeatService).requeueDeliveredIfUnacked(77L);
    }

    @Test
    void enqueueSingleWol_withoutOnlineAgent_usesRouterFallback() {
        PcMaster target = pc("AA:BB:CC:DD:EE:31", "OFFLINE");
        target.setIpAddress("223.194.168.31");
        when(pcMasterRepository.findById(target.getMacAddress())).thenReturn(java.util.Optional.of(target));
        when(pcMasterRepository.findByStatus("ONLINE")).thenReturn(List.of());
        when(classroomIpRangeRepository.findByGroupId(29L)).thenReturn(List.of());
        when(routerWolService.wake(eq(target.getIpAddress()), any()))
                .thenReturn(RouterWolService.Result.sent(
                        "223.194.168.0/24", "223.194.168.251:8080", 1, 1));

        CommandRequestDto request = new CommandRequestDto();
        request.setScope("SINGLE");
        request.setType("WOL");
        request.setMacAddress(target.getMacAddress());

        Map<String, Object> result = commandLifecycleService.enqueue(request);

        assertThat(result.get("queued")).isEqualTo(1);
        assertThat(result.get("acceptedTargets")).isEqualTo(1);
        assertThat(result.get("routerSignals")).isEqualTo(1);
        assertThat(result.get("routerMessage")).isEqualTo("router-wol-sent");
        assertThat(result.get("taskIds")).isEqualTo(List.of());
        verify(commandQueueRepository, never()).save(any(CommandQueue.class));
    }

    @Test
    void enqueueSingleWol_whenRouterIsOffline_returnsNormalFailureResponse() {
        PcMaster target = pc("AA:BB:CC:DD:EE:32", "OFFLINE");
        target.setIpAddress("223.194.168.32");
        when(pcMasterRepository.findById(target.getMacAddress())).thenReturn(java.util.Optional.of(target));
        when(pcMasterRepository.findByStatus("ONLINE")).thenReturn(List.of());
        when(classroomIpRangeRepository.findByGroupId(29L)).thenReturn(List.of());
        when(routerWolService.wake(eq(target.getIpAddress()), any()))
                .thenReturn(RouterWolService.Result.failed(
                        "router-wol-gateway-unreachable", "223.194.168.0/24", 1, 2));

        CommandRequestDto request = new CommandRequestDto();
        request.setScope("SINGLE");
        request.setType("WOL");
        request.setMacAddress(target.getMacAddress());

        Map<String, Object> result = commandLifecycleService.enqueue(request);

        assertThat(result.get("queued")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("no-online-agent");
        assertThat(result.get("routerMessage")).isEqualTo("router-wol-gateway-unreachable");
        assertThat(result.get("routerAttempts")).isEqualTo(2);
        assertThat(result.get("failedTargets")).isEqualTo(1);
    }

    @Test
    void enqueueGroupWol_routesOfflineTargetsThroughRouterWhenNoAgentIsOnline() {
        PcMaster first = pc("AA:BB:CC:DD:EE:41", "OFFLINE");
        first.setIpAddress("223.194.167.41");
        PcMaster second = pc("AA:BB:CC:DD:EE:42", "OFFLINE");
        second.setIpAddress("223.194.167.42");
        when(pcMasterRepository.findByStatus("ONLINE")).thenReturn(List.of());
        when(pcMasterRepository.findByGroupId(29L)).thenReturn(List.of(first, second));
        when(classroomIpRangeRepository.findByGroupId(29L)).thenReturn(List.of());
        when(routerWolService.wake(eq(first.getIpAddress()), any()))
                .thenReturn(RouterWolService.Result.sent(
                        "223.194.167.0/24", "223.194.167.253:8080", 2, 1));

        Map<String, Object> result = commandLifecycleService.enqueue(groupCommand("WOL"));

        assertThat(result.get("queued")).isEqualTo(1);
        assertThat(result.get("acceptedTargets")).isEqualTo(2);
        assertThat(result.get("failedTargets")).isEqualTo(0);
        assertThat(result.get("routerSignals")).isEqualTo(1);
        assertThat(result.get("routerGateways")).isEqualTo(List.of("223.194.167.253:8080"));
    }

    private CommandRequestDto groupCommand(String type) {
        CommandRequestDto request = new CommandRequestDto();
        request.setScope("GROUP");
        request.setGroupId(29L);
        request.setRole("ALL");
        request.setType(type);
        return request;
    }

    private PcMaster pc(String macAddress, String status) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setGroupId(29L);
        pc.setStatus(status);
        return pc;
    }
}
