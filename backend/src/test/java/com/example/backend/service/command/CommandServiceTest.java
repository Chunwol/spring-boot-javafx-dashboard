package com.example.backend.service.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandServiceTest {

    @Mock CommandQueueRepository commandQueueRepository;
    @Mock PcMasterRepository pcMasterRepository;
    @InjectMocks CommandService commandService;

    @Test
    void enqueueCommand_savesWithQueuedStatus() {
        CommandRequestDto req = new CommandRequestDto();
        req.setMacAddress("AA:BB:CC:DD:EE:FF");
        req.setType("SHUTDOWN");
        req.setPayloadJson("{}");

        commandService.enqueueCommand(req);

        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        CommandQueue saved = captor.getValue();
        assertThat(saved.getMacAddress()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(saved.getCommandType()).isEqualTo("SHUTDOWN");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void getPendingCommands_delegatesToRepository() {
        String mac = "AA:BB:CC:DD:EE:FF";
        List<CommandQueue> expected = List.of(new CommandQueue());
        when(commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                eq(mac), anyList())).thenReturn(expected);

        List<CommandQueue> result = commandService.getPendingCommands(mac);

        assertThat(result).hasSize(1);
        verify(commandQueueRepository).findByMacAddressAndStatusInOrderByCreatedAtAsc(
                eq(mac), eq(List.of("QUEUED", "DELIVERED", "ACKED")));
    }

    @Test
    void markCommandDelivered_updatesStatus() {
        CommandQueue cmd = new CommandQueue();
        cmd.setTaskId(1L);
        cmd.setStatus("QUEUED");
        when(commandQueueRepository.findById(1L)).thenReturn(Optional.of(cmd));

        commandService.markCommandDelivered(1L);

        assertThat(cmd.getStatus()).isEqualTo("DELIVERED");
        assertThat(cmd.getDeliveredAt()).isNotNull();
        verify(commandQueueRepository).save(cmd);
    }

    @Test
    void markCommandDelivered_notFound_noAction() {
        when(commandQueueRepository.findById(999L)).thenReturn(Optional.empty());

        commandService.markCommandDelivered(999L);

        verify(commandQueueRepository, never()).save(any());
    }

    @Test
    void sendGroupWol_noCandidates_returnsFalse() {
        when(pcMasterRepository.findByGroupIdAndStatus(1L, "ONLINE")).thenReturn(List.of());

        boolean result = commandService.sendGroupWol(1L);

        assertThat(result).isFalse();
        verify(commandQueueRepository, never()).save(any());
    }

    @Test
    void sendGroupWol_withCandidates_enqueuesWolCommand() {
        PcMaster pc = new PcMaster();
        pc.setMacAddress("AA:BB:CC:DD:EE:FF");
        when(pcMasterRepository.findByGroupIdAndStatus(1L, "ONLINE")).thenReturn(List.of(pc));

        boolean result = commandService.sendGroupWol(1L);

        assertThat(result).isTrue();
        ArgumentCaptor<CommandQueue> captor = ArgumentCaptor.forClass(CommandQueue.class);
        verify(commandQueueRepository).save(captor.capture());
        CommandQueue saved = captor.getValue();
        assertThat(saved.getMacAddress()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(saved.getCommandType()).isEqualTo("WOL_RELAY");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
    }
}
