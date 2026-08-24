package com.example.backend.service.filedist;

import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.filedist.PeerTransferCreateRequest;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.filedist.FileDistributionAgentStatusRepository;
import com.example.backend.repository.filedist.FileDistributionEventRepository;
import com.example.backend.repository.filedist.FileDistributionJobRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.command.CommandLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerTransferServiceTest {

    @Mock FileDistributionJobRepository jobRepository;
    @Mock FileDistributionAgentStatusRepository targetRepository;
    @Mock FileDistributionEventRepository eventRepository;
    @Mock PcMasterRepository pcRepository;
    @Mock CommandQueueRepository commandRepository;
    @Mock CommandLifecycleService commandLifecycleService;
    @Mock PeerTransferScriptArtifactService scriptArtifactService;
    @Mock TaskExecutor taskExecutor;

    @Test
    void createsPcCopyJobForExactOnlineSourceAndTargets() {
        PcMaster source = pc("AA:BB:CC:DD:EE:01", "10.0.0.1");
        PcMaster target = pc("AA:BB:CC:DD:EE:02", "10.0.0.2");
        when(pcRepository.findAll()).thenReturn(List.of(source, target));
        when(jobRepository.existsBySourceAgentIdIgnoreCaseAndStatusIn(any(), any())).thenReturn(false);
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            FileDistributionJob job = invocation.getArgument(0);
            job.setId(17L);
            return job;
        });
        PeerTransferService service = service();

        FileDistributionJob job = service.create(new PeerTransferCreateRequest(
                source.getMacAddress(), List.of(target.getMacAddress()),
                "C:\\Users\\USER\\Desktop\\ROCKY", "C:\\Users\\USER\\Desktop\\ROCKY", false));

        assertThat(job.getUpdatePackageType()).isEqualTo("PC_COPY");
        assertThat(job.getSourceMode()).isEqualTo("PC_DIRECT");
        assertThat(job.getSourceAgentId()).isEqualTo(source.getMacAddress());
        verify(targetRepository).saveAll(any());
        verify(taskExecutor).execute(any());
    }

    @Test
    void rejectsRelativeAndSourceAsTargetPathsBeforeQueueingCommands() {
        PeerTransferService service = service();
        assertThatThrownBy(() -> service.create(new PeerTransferCreateRequest(
                "AA:BB:CC:DD:EE:01", List.of("AA:BB:CC:DD:EE:02"),
                "Desktop\\ROCKY", "C:\\ROCKY", false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("absolute Windows path");
    }

    private PeerTransferService service() {
        return new PeerTransferService(jobRepository, targetRepository, eventRepository, pcRepository,
                commandRepository, commandLifecycleService, scriptArtifactService, taskExecutor, new ObjectMapper());
    }

    private PcMaster pc(String mac, String ip) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(mac);
        pc.setIpAddress(ip);
        pc.setStatus("ONLINE");
        return pc;
    }
}
