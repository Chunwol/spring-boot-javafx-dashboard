package com.example.backend.controller.command;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.dto.command.ScmateActionRequestDto;
import com.example.backend.service.command.ScmateWorkflowService;
import com.example.backend.security.FeaturePermissionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmateControllerTest {

    @Mock ScmateWorkflowService scmateWorkflowService;
    @Mock FeaturePermissionGuard featurePermissionGuard;
    @InjectMocks ScmateController controller;

    @Test
    @SuppressWarnings("unchecked")
    void performBulk_preservesFailureReasonForEachPc() {
        ScmateActionRequestDto accepted = new ScmateActionRequestDto(
                "AA:BB:CC:DD:EE:01", "UPDATE_BASELINE", "SNAP1");
        ScmateActionRequestDto rejected = new ScmateActionRequestDto(
                "AA:BB:CC:DD:EE:02", "UPDATE_BASELINE", "SNAP1");
        when(scmateWorkflowService.perform(accepted.macAddress(), accepted.action(), accepted.recoveryPoint()))
                .thenReturn(Map.of("taskId", 1L, "macAddress", accepted.macAddress()));
        when(scmateWorkflowService.perform(rejected.macAddress(), rejected.action(), rejected.recoveryPoint()))
                .thenThrow(new ConflictException("시점 저장 전 재부팅이 확인되지 않았습니다."));

        Map<String, Object> body = controller.performBulk(List.of(accepted, rejected)).getBody();

        assertThat(body).containsEntry("requested", 2).containsEntry("queued", 1).containsEntry("failed", 1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
        assertThat(results.get(0)).containsEntry("macAddress", accepted.macAddress())
                .containsEntry("accepted", true);
        assertThat(results.get(1)).containsEntry("macAddress", rejected.macAddress())
                .containsEntry("accepted", false)
                .containsEntry("errorCode", "CONFLICT")
                .containsEntry("error", "시점 저장 전 재부팅이 확인되지 않았습니다.");
    }
}
