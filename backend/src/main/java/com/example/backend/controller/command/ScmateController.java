package com.example.backend.controller.command;

import com.example.backend.common.exception.ApplicationException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.command.ScmateActionRequestDto;
import com.example.backend.service.command.ScmateWorkflowService;
import com.example.backend.security.FeaturePermissionGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scmate")
@RequiredArgsConstructor
public class ScmateController {

    private final ScmateWorkflowService scmateWorkflowService;
    private final FeaturePermissionGuard featurePermissionGuard;

    @PostMapping("/actions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> perform(@Valid @RequestBody ScmateActionRequestDto request) {
        featurePermissionGuard.requireScmateAction(request.action());
        return ResponseEntity.accepted().body(
                scmateWorkflowService.perform(request.macAddress(), request.action(), request.recoveryPoint()));
    }

    @PostMapping("/actions/bulk")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> performBulk(
            @Valid @RequestBody List<@Valid ScmateActionRequestDto> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidRequestException("SCMATE bulk 요청 대상이 없습니다.");
        }
        requests.forEach(request -> featurePermissionGuard.requireScmateAction(request.action()));
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        for (ScmateActionRequestDto request : requests) {
            try {
                Map<String, Object> row = new LinkedHashMap<>(scmateWorkflowService.perform(
                        request.macAddress(), request.action(), request.recoveryPoint()));
                row.put("accepted", true);
                results.add(row);
                accepted++;
            } catch (ApplicationException error) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("macAddress", request.macAddress());
                row.put("action", request.action());
                row.put("recoveryPoint", request.recoveryPoint());
                row.put("accepted", false);
                row.put("errorCode", error.getCode());
                row.put("error", error.getMessage());
                results.add(row);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requested", requests.size());
        response.put("queued", accepted);
        response.put("failed", requests.size() - accepted);
        response.put("results", results);
        return ResponseEntity.accepted().body(response);
    }
}
