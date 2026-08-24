package com.example.backend.service.scriptlibrary;

import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.scriptlibrary.ScriptLibraryExecutionRequest;
import com.example.backend.model.scriptlibrary.ScriptLibraryTemplate;
import com.example.backend.model.scriptlibrary.ScriptLibraryVersion;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryExecutionRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryExecutionTargetRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryTemplateRepository;
import com.example.backend.repository.scriptlibrary.ScriptLibraryVersionRepository;
import com.example.backend.service.command.CommandLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptLibraryServiceTest {
    @Mock ScriptLibraryTemplateRepository templateRepository;
    @Mock ScriptLibraryVersionRepository versionRepository;
    @Mock ScriptLibraryExecutionRepository executionRepository;
    @Mock ScriptLibraryExecutionTargetRepository targetRepository;
    @Mock ScriptLibraryArtifactService artifactService;
    @Mock PcMasterRepository pcRepository;
    @Mock CommandQueueRepository commandRepository;
    @Mock CommandLifecycleService commandLifecycleService;

    @Test
    void highRiskScriptRequiresExplicitAcknowledgement() {
        ScriptLibraryTemplate template = template("HIGH", "WINDOWS_POWERSHELL_5_1");
        stubCurrentVersion(template);

        assertThatThrownBy(() -> service().execute(7L, new ScriptLibraryExecutionRequest(
                List.of("AA:BB:CC:DD:EE:01"), "FOREGROUND", "HIDDEN", null, false)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("확인");

        verify(commandLifecycleService, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void powershellSevenScriptIsStoredButBlockedFromCurrentRunPs1Engine() {
        ScriptLibraryTemplate template = template("LOW", "POWERSHELL_7");
        stubCurrentVersion(template);

        assertThatThrownBy(() -> service().execute(7L, new ScriptLibraryExecutionRequest(
                List.of("AA:BB:CC:DD:EE:01"), "FOREGROUND", "HIDDEN", null, true)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Windows PowerShell 5.1");

        verify(commandLifecycleService, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    private ScriptLibraryService service() {
        return new ScriptLibraryService(templateRepository, versionRepository, executionRepository,
                targetRepository, artifactService, pcRepository, commandRepository,
                commandLifecycleService, new ObjectMapper());
    }

    private ScriptLibraryTemplate template(String risk, String requirement) {
        ScriptLibraryTemplate template = new ScriptLibraryTemplate();
        template.setId(7L);
        template.setName("테스트");
        template.setRiskLevel(risk);
        template.setPowershellRequirement(requirement);
        template.setRequiresReboot(false);
        template.setRequiresOperatorMode(false);
        template.setActive(true);
        template.setCurrentVersion(1);
        when(templateRepository.findById(7L)).thenReturn(Optional.of(template));
        return template;
    }

    private void stubCurrentVersion(ScriptLibraryTemplate template) {
        ScriptLibraryVersion version = new ScriptLibraryVersion();
        version.setId(11L);
        version.setTemplateId(template.getId());
        version.setVersionNo(1);
        version.setArtifactId(21L);
        version.setSha256("a".repeat(64));
        when(versionRepository.findByTemplateIdAndVersionNo(7L, 1)).thenReturn(Optional.of(version));
    }
}
