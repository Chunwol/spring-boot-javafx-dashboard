package com.example.backend.service.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommandService {

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;

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
        return commandQueueRepository.findByMacAddressAndIsExecutedFalse(macAddress);
    }

    @Transactional
    public void markCommandDelivered(Long commandId) {
        CommandQueue command = commandQueueRepository.findById(commandId).orElse(null);
        if (command != null) {
            command.setStatus("DELIVERED");
            command.setDeliveredAt(LocalDateTime.now());
            commandQueueRepository.save(command);
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
        command.setCommandType("WOL");
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        commandQueueRepository.save(command);
        return true;
    }
}
