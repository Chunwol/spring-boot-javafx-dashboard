package com.example.backend.service.heartbeat;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import com.example.backend.service.audit.AuditService;
import com.example.backend.service.classroom.ClassroomResolutionService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

    @Mock PcMasterRepository pcMasterRepository;
    @Mock ClassroomResolutionService classroomResolutionService;
    @Mock TrafficLogsRepository trafficLogsRepository;
    @Mock CommandQueueRepository commandQueueRepository;
    @Mock AuditService auditService;

    @InjectMocks HeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(heartbeatService, "heartbeatPersistIntervalSeconds", 30);
        ReflectionTestUtils.setField(heartbeatService, "trafficLogMinIntervalSeconds", 30);
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
        when(commandQueueRepository.findFirstByMacAddressAndStatusOrderByTaskIdAsc(pc.getMacAddress(), "QUEUED"))
                .thenReturn(null);

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
        when(commandQueueRepository.findFirstByMacAddressAndStatusOrderByTaskIdAsc(pc.getMacAddress(), "QUEUED"))
                .thenReturn(null);

        heartbeatService.processHeartbeat(heartbeat);

        verify(commandQueueRepository, never()).findByMacAddressAndStatusInOrderByCreatedAtAsc(
                eq(pc.getMacAddress()), any());
    }
}