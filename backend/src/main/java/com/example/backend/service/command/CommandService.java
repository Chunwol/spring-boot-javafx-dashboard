package com.example.backend.service.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.status.OperationalMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommandService {

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final OperationalMetricsService operationalMetricsService;

    @Transactional
    public void enqueueCommand(CommandRequestDto request) {
        CommandQueue command = new CommandQueue();
        command.setMacAddress(request.getMacAddress());
        command.setCommandType(request.getType());
        command.setScriptUrl(request.getScriptUrl());
        command.setPayloadJson(request.getPayloadJson());
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        commandQueueRepository.save(command);
    }

    public List<CommandQueue> getPendingCommands(String macAddress) {
        return commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                macAddress,
                List.of("QUEUED", "DELIVERED", "ACKED"));
    }

    @Transactional
    public void markCommandDelivered(Long commandId) {
        CommandQueue command = commandQueueRepository.findById(commandId).orElse(null);
        if (command != null) {
            LocalDateTime deliveredAt = LocalDateTime.now();
            command.setStatus("DELIVERED");
            command.setDeliveredAt(deliveredAt);
            commandQueueRepository.save(command);
            if (command.getCreatedAt() != null) {
                long dispatchLatencyMs = Duration.between(command.getCreatedAt(), deliveredAt).toMillis();
                operationalMetricsService.recordCommandDispatchLatencyMillis(dispatchLatencyMs);
            }
        }
    }

    @Transactional
    public boolean sendGroupWol(Long groupId) {
        var candidates = pcMasterRepository.findByGroupIdAndStatus(groupId, "ONLINE");
        if (candidates.isEmpty()) {
            return false;
        }
        CommandQueue command = new CommandQueue();
        command.setMacAddress(candidates.get(0).getMacAddress());
        command.setCommandType("WOL_RELAY");
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        commandQueueRepository.save(command);
        return true;
    }
}
