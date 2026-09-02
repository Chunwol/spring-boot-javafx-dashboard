package com.example.backend.remotecontrol;

import com.example.backend.common.exception.ApplicationException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.command.CommandLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteControlSessionServiceTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";
    private static final Instant NOW = Instant.parse("2026-08-07T04:00:00Z");

    private CommandLifecycleService commands;
    private CommandQueueRepository commandRepository;
    private PcMasterRepository pcRepository;
    private RemoteControlRelayProperties properties;
    private RemoteControlRelayRegistry relayRegistry;
    private Clock clock;
    private RemoteControlSessionService service;

    @BeforeEach
    void setUp() {
        commands = mock(CommandLifecycleService.class);
        commandRepository = mock(CommandQueueRepository.class);
        pcRepository = mock(PcMasterRepository.class);
        properties = new RemoteControlRelayProperties();
        relayRegistry = new RemoteControlRelayRegistry();
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        service = new RemoteControlSessionService(
                commands, commandRepository, pcRepository, new ObjectMapper(), properties, clock, relayRegistry);
        when(pcRepository.findById(MAC)).thenReturn(Optional.of(onlinePc("192.0.2.10")));
    }

    @Test
    void bindsCompletedTaskToPcAndConsumesTicketOnlyOnce() {
        CommandQueue task = completedStartTask(42L, MAC, 5900);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(task));

        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");
        RemoteControlTicketResponse ticket = service.issueTicket(session.sessionId(), "admin");
        String rawTicket = ticket.webSocketPath().substring(ticket.webSocketPath().indexOf("ticket=") + 7);
        RemoteControlRelayGrant grant = service.consumeTicket(session.sessionId(), rawTicket);

        assertThat(session.status()).isEqualTo("READY");
        assertThat(ticket.oneTimePassword()).isEqualTo("AbCd2345");
        assertThat(grant.macAddress()).isEqualTo(MAC);
        assertThat(grant.targetAddress().getHostAddress()).isEqualTo("192.0.2.10");
        assertThat(grant.targetPort()).isEqualTo(5900);
        assertThatThrownBy(() -> service.consumeTicket(session.sessionId(), rawTicket))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).getHttpStatus())
                .isEqualTo(401);
    }

    @Test
    void queuesExistingRemoteStartCommandWhenTaskIdIsAbsent() {
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenReturn(Map.of("taskIds", List.of(99L)));

        RemoteControlSessionView session = service.createSession(MAC, null, "admin");

        assertThat(session.status()).isEqualTo("PENDING");
        assertThat(session.taskId()).isEqualTo(99L);
        ArgumentCaptor<CommandRequestDto> request = ArgumentCaptor.forClass(CommandRequestDto.class);
        verify(commands).enqueue(request.capture());
        assertThat(request.getValue().getScope()).isEqualTo("SINGLE");
        assertThat(request.getValue().getMacAddress()).isEqualTo(MAC);
        assertThat(request.getValue().getType()).isEqualTo("REMOTE_CONTROL_START");
        assertThat(request.getValue().getPayloadJson()).contains(session.sessionId().toString());
    }

    @Test
    void rejectsTaskForDifferentPcOrUnexpectedPort() {
        CommandQueue wrongMac = completedStartTask(42L, "11:22:33:44:55:66", 5900);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(wrongMac));
        assertThatThrownBy(() -> service.createSession(MAC, 42L, "admin"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not belong");

        CommandQueue wrongPort = completedStartTask(43L, MAC, 22);
        when(commandRepository.findById(43L)).thenReturn(Optional.of(wrongPort));
        assertThatThrownBy(() -> service.createSession(MAC, 43L, "admin"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("port is not allowed");
    }

    @Test
    void rejectsUnsafeOrOutOfPolicyDestination() {
        assertThatThrownBy(() -> service.validateDestination("127.0.0.1"))
                .isInstanceOf(InvalidRequestException.class);

        properties.setAllowedCidrs(List.of("10.0.0.0/8"));
        assertThatThrownBy(() -> service.validateDestination("192.0.2.10"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("outside allowed CIDRs");
        assertThat(service.validateDestination("10.20.30.40").getHostAddress())
                .isEqualTo("10.20.30.40");
    }

    @Test
    void doesNotExposeSessionAcrossAdministrators() {
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenReturn(Map.of("taskIds", List.of(99L)));
        RemoteControlSessionView session = service.createSession(MAC, null, "admin-a");

        assertThatThrownBy(() -> service.getSession(session.sessionId(), "admin-b"))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).getHttpStatus())
                .isEqualTo(403);
    }

    @Test
    void expiresUnusedReadySessionAndQueuesRemoteStop() {
        properties.setReadyTtl(java.time.Duration.ZERO);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenReturn(Map.of("taskIds", List.of(100L)));
        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");

        assertThatThrownBy(() -> service.issueTicket(session.sessionId(), "admin"))
                .hasMessageContaining("expired");
        ArgumentCaptor<CommandRequestDto> request = ArgumentCaptor.forClass(CommandRequestDto.class);
        verify(commands).enqueue(request.capture());
        assertThat(request.getValue().getType()).isEqualTo("REMOTE_CONTROL_STOP");
        assertThat(request.getValue().getPayloadJson()).contains(session.sessionId().toString());
        assertThat(service.getSession(session.sessionId(), "admin").status()).isEqualTo("CLOSED");
    }

    @Test
    void failedRelayConnectionClosesSessionAndQueuesStop() {
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenReturn(Map.of("taskIds", List.of(100L)));
        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");
        RemoteControlTicketResponse ticket = service.issueTicket(session.sessionId(), "admin");
        String rawTicket = ticket.webSocketPath().substring(ticket.webSocketPath().indexOf("ticket=") + 7);
        service.consumeTicket(session.sessionId(), rawTicket);

        service.connectionFailed(session.sessionId(), "Target VNC server refused the relay connection");

        RemoteControlSessionView failed = service.getSession(session.sessionId(), "admin");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).contains("refused");
        ArgumentCaptor<CommandRequestDto> request = ArgumentCaptor.forClass(CommandRequestDto.class);
        verify(commands).enqueue(request.capture());
        assertThat(request.getValue().getType()).isEqualTo("REMOTE_CONTROL_STOP");
        assertThat(request.getValue().getPayloadJson()).contains(session.sessionId().toString());
    }

    @Test
    void retriesRemoteStopWhenTheFirstQueueAttemptFails() {
        properties.setReadyTtl(java.time.Duration.ZERO);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenThrow(new IllegalStateException("temporary database outage"))
                .thenReturn(Map.of("taskIds", List.of(100L)));
        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");

        assertThatThrownBy(() -> service.issueTicket(session.sessionId(), "admin"))
                .hasMessageContaining("expired");
        service.cleanupExpiredSessions();

        verify(commands, times(2)).enqueue(any(CommandRequestDto.class));
        assertThat(service.getSession(session.sessionId(), "admin").status()).isEqualTo("CLOSED");
    }

    @Test
    void retriesRemoteStopAfterTheLastViewerGracePeriod() {
        properties.setViewerGraceTtl(java.time.Duration.ZERO);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class)))
                .thenThrow(new IllegalStateException("temporary database outage"))
                .thenReturn(Map.of("taskIds", List.of(100L)));
        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");

        service.closeSession(session.sessionId(), "admin");
        service.cleanupExpiredSessions();
        service.cleanupExpiredSessions();

        verify(commands, times(2)).enqueue(any(CommandRequestDto.class));
        assertThat(service.getSession(session.sessionId(), "admin").status()).isEqualTo("CLOSED");
    }

    @Test
    void sharesOnePcStreamAcrossIndependentBrowserViewers() {
        properties.setViewerGraceTtl(java.time.Duration.ZERO);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class))).thenReturn(Map.of("taskIds", List.of(100L)));

        RemoteControlSessionView first = service.createSession(MAC, 42L, "admin-a");
        connect(first, "admin-a");
        RemoteControlSessionView second = service.createSession(MAC, null, "admin-b");
        connect(second, "admin-b");
        Runnable firstRelay = mock(Runnable.class);
        Runnable secondRelay = mock(Runnable.class);
        relayRegistry.register(first.sessionId(), firstRelay);
        relayRegistry.register(second.sessionId(), secondRelay);

        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(service.getSession(first.sessionId(), "admin-a").status()).isEqualTo("CONNECTED");
        assertThat(service.getSession(second.sessionId(), "admin-b").status()).isEqualTo("CONNECTED");
        verify(commands, never()).enqueue(any(CommandRequestDto.class));

        service.closeSession(first.sessionId(), "admin-a");

        verify(firstRelay).run();
        verify(secondRelay, never()).run();
        assertThat(service.getSession(first.sessionId(), "admin-a").status()).isEqualTo("CLOSED");
        assertThat(service.getSession(second.sessionId(), "admin-b").status()).isEqualTo("CONNECTED");
        verify(commands, never()).enqueue(any(CommandRequestDto.class));

        service.closeSession(second.sessionId(), "admin-b");
        service.cleanupExpiredSessions();

        ArgumentCaptor<CommandRequestDto> request = ArgumentCaptor.forClass(CommandRequestDto.class);
        verify(commands).enqueue(request.capture());
        assertThat(request.getValue().getType()).isEqualTo("REMOTE_CONTROL_STOP");
        assertThat(request.getValue().getPayloadJson()).contains(first.sessionId().toString());
    }

    @Test
    void listsEachActiveViewerWithAccountAndSourceLocation() {
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));

        RemoteControlSessionView first = service.createSession(
                MAC, 42L, "admin-a", "192.0.2.50", "Mozilla/5.0 Chrome/140 Windows");
        RemoteControlSessionView second = service.createSession(
                MAC, null, "admin-b", "192.0.2.51", "Mozilla/5.0 Edg/140 Windows");

        assertThat(service.listActiveSessions())
                .extracting(ActiveRemoteControlSessionView::username)
                .containsExactlyInAnyOrder("admin-a", "admin-b");
        assertThat(service.listActiveSessions())
                .extracting(ActiveRemoteControlSessionView::sourceIp)
                .containsExactlyInAnyOrder("192.0.2.50", "192.0.2.51");

        service.closeOwnerSessions("admin-a");

        assertThat(service.getSession(first.sessionId(), "admin-a").status()).isEqualTo("CLOSED");
        assertThat(service.getSession(second.sessionId(), "admin-b").status()).isEqualTo("READY");
        assertThat(service.listActiveSessions())
                .extracting(ActiveRemoteControlSessionView::username)
                .containsExactly("admin-b");
    }

    @Test
    void capsTheNumberOfViewersPerPcWithoutRestartingTheStream() {
        properties.setMaxViewersPerPc(2);
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));

        RemoteControlSessionView first = service.createSession(MAC, 42L, "admin-a");
        RemoteControlSessionView second = service.createSession(MAC, null, "admin-b");

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThatThrownBy(() -> service.createSession(MAC, null, "admin-c"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Maximum viewers");
        verify(commands, never()).enqueue(any(CommandRequestDto.class));
    }

    @Test
    void reconnectsWithinTheGracePeriodWithoutRestartingVnc() {
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        RemoteControlSessionView first = service.createSession(MAC, 42L, "admin");
        connect(first, "admin");

        service.relayClosed(first.sessionId());
        RemoteControlSessionView replacement = service.createSession(MAC, null, "admin");

        assertThat(service.getSession(first.sessionId(), "admin").status()).isEqualTo("CLOSED");
        assertThat(replacement.taskId()).isEqualTo(first.taskId());
        assertThat(replacement.status()).isEqualTo("READY");
        verify(commands, never()).enqueue(any(CommandRequestDto.class));
    }

    @Test
    void acceptsFiftyMonitoringSessionsWithinTheConfiguredCapacity() {
        properties.setMaxSessions(64);
        AtomicLong taskIds = new AtomicLong(1L);
        when(pcRepository.findById(any(String.class))).thenAnswer(invocation -> {
            String macAddress = invocation.getArgument(0);
            return Optional.of(onlinePc(macAddress, "192.0.2.10"));
        });
        when(commands.enqueue(any(CommandRequestDto.class))).thenAnswer(invocation ->
                Map.of("taskIds", List.of(taskIds.getAndIncrement())));

        for (int index = 0; index < 50; index++) {
            String macAddress = String.format("02:00:00:00:00:%02X", index);
            assertThat(service.createSession(macAddress, null, "admin").status()).isEqualTo("PENDING");
        }
    }

    @Test
    void closingSessionClosesTheRegisteredRelayImmediately() {
        when(commandRepository.findById(42L)).thenReturn(Optional.of(completedStartTask(42L, MAC, 5900)));
        when(commands.enqueue(any(CommandRequestDto.class))).thenReturn(Map.of("taskIds", List.of(100L)));
        RemoteControlSessionView session = service.createSession(MAC, 42L, "admin");
        Runnable closer = mock(Runnable.class);
        relayRegistry.register(session.sessionId(), closer);

        service.closeSession(session.sessionId(), "admin");

        verify(closer).run();
        assertThat(service.getSession(session.sessionId(), "admin").status()).isEqualTo("CLOSED");
    }

    private PcMaster onlinePc(String ipAddress) {
        return onlinePc(MAC, ipAddress);
    }

    private PcMaster onlinePc(String macAddress, String ipAddress) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setIpAddress(ipAddress);
        pc.setStatus("ONLINE");
        pc.setLastSeen(LocalDateTime.now(clock).minusSeconds(5));
        return pc;
    }

    private CommandQueue completedStartTask(long taskId, String macAddress, int port) {
        CommandQueue task = new CommandQueue();
        task.setTaskId(taskId);
        task.setMacAddress(macAddress);
        task.setCommandType("REMOTE_CONTROL_START");
        task.setStatus("COMPLETED");
        task.setExitCode(0);
        task.setCompletedAt(LocalDateTime.now(clock).minusSeconds(5));
        task.setStdout("{\"status\":\"started\",\"port\":" + port
                + ",\"oneTimePassword\":\"AbCd2345\",\"taskId\":" + taskId + "}");
        return task;
    }

    private void connect(RemoteControlSessionView session, String owner) {
        RemoteControlTicketResponse ticket = service.issueTicket(session.sessionId(), owner);
        String rawTicket = ticket.webSocketPath().substring(ticket.webSocketPath().indexOf("ticket=") + 7);
        service.consumeTicket(session.sessionId(), rawTicket);
        service.markConnected(session.sessionId());
    }
}
