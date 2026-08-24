package com.example.backend.controller.filedist;

import com.example.backend.dto.filedist.PeerTransferCreateRequest;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.security.FileDistributionRequestSecurity;
import com.example.backend.service.filedist.PeerTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerTransferControllerTest {

    @Mock
    private PeerTransferService service;

    @Mock
    private FileDistributionRequestSecurity requestSecurity;

    @Test
    void createsExplicitPeerTransferWithoutAgentUpdate() {
        FileDistributionJob job = new FileDistributionJob();
        job.setId(91L);
        job.setStatus("QUEUED");
        job.setSourceAgentId("AA:BB:CC:DD:EE:01");
        job.setSourcePath("C:\\Rocky10");
        job.setTargetPath("C:\\Rocky10");
        when(service.create(any())).thenReturn(job);
        PeerTransferController controller = new PeerTransferController(service, requestSecurity);
        PeerTransferCreateRequest request = new PeerTransferCreateRequest(
                "aa-bb-cc-dd-ee-01",
                List.of("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"),
                "C:\\Rocky10",
                "C:\\Rocky10",
                true
        );

        Map<String, Object> result = controller.create(request, new MockHttpServletRequest());

        assertThat(result).containsEntry("jobId", 91L)
                .containsEntry("updatePackageType", "PC_COPY")
                .containsEntry("targetCount", 2);
        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        verify(requestSecurity).validateOrThrow(any(MockHttpServletRequest.class), canonical.capture());
        assertThat(canonical.getValue()).contains("createPeerTransfer|")
                .contains("C:\\Rocky10")
                .doesNotContain("AGENT_UPDATE");
    }
}
