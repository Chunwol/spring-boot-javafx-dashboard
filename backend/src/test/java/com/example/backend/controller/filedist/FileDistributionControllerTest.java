package com.example.backend.controller.filedist;

import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.security.FileDistributionRequestSecurity;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileDistributionControllerTest {

    @Mock
    private FileDistributionOrchestrator orchestrator;

    @Mock
    private FileDistributionRequestSecurity requestSecurity;

    @Test
    void createJobPassesNormalizedExplicitTargetsWithoutWideningScope() {
        FileDistributionJob job = mock(FileDistributionJob.class);
        when(job.getId()).thenReturn(42L);
        when(job.getStatus()).thenReturn("IN_PROGRESS");
        when(orchestrator.createJob(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), eq(null),
                any(), any(), any(), any(), any(), any())).thenReturn(job);

        FileDistributionController controller = new FileDistributionController(orchestrator, requestSecurity);
        Map<String, Object> body = validBody();
        body.put("targetMacs", List.of("aa-bb-cc-dd-ee-01", "AA:BB:CC:DD:EE:02", "aa-bb-cc-dd-ee-01"));

        Map<String, Object> response = controller.createJob(body, new MockHttpServletRequest());

        assertThat(response.get("jobId")).isEqualTo(42L);
        verify(orchestrator).createJob(
                eq("upload-12345678"), eq("agent.jar"), eq(1), eq(5), eq(false), eq(null),
                eq(7L), eq("1.2.3"), eq("SERVER_ONLY"), eq(1.5), eq("JAR"),
                eq(List.of("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")));
        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        verify(requestSecurity).validateOrThrow(any(MockHttpServletRequest.class), canonical.capture());
        assertThat(canonical.getValue()).endsWith("|targetMacs=AA:BB:CC:DD:EE:01,AA:BB:CC:DD:EE:02");
    }

    @Test
    void createJobRejectsEmptyOrAmbiguousExplicitTargetScope() {
        FileDistributionController controller = new FileDistributionController(orchestrator, requestSecurity);
        Map<String, Object> empty = validBody();
        empty.put("targetMacs", List.of());
        assertThatThrownBy(() -> controller.createJob(empty, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");

        Map<String, Object> ambiguous = validBody();
        ambiguous.put("groupId", 218);
        ambiguous.put("targetMacs", List.of("AA:BB:CC:DD:EE:01"));
        assertThatThrownBy(() -> controller.createJob(ambiguous, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be used together");
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploadId", "upload-12345678");
        body.put("originalFilename", "agent.jar");
        body.put("totalChunks", 1);
        body.put("waveSize", 5);
        body.put("canaryOnly", false);
        body.put("artifactId", 7);
        body.put("targetVersion", "1.2.3");
        body.put("sourceMode", "SERVER_ONLY");
        body.put("rampUpFactor", 1.5);
        body.put("updatePackageType", "JAR");
        return body;
    }
}
