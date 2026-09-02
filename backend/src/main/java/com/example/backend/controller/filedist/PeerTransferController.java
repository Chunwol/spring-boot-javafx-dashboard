package com.example.backend.controller.filedist;

import com.example.backend.dto.filedist.PeerTransferCreateRequest;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.security.FileDistributionRequestSecurity;
import com.example.backend.service.filedist.PeerTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/file-distribution/peer-jobs")
@Tag(name = "Peer PC Transfer", description = "기존 에이전트 RUN_PS1을 이용한 PC 간 직접 파일 전송")
public class PeerTransferController {

    private final PeerTransferService peerTransferService;
    private final FileDistributionRequestSecurity requestSecurity;

    public PeerTransferController(PeerTransferService peerTransferService,
                                  FileDistributionRequestSecurity requestSecurity) {
        this.peerTransferService = peerTransferService;
        this.requestSecurity = requestSecurity;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "PC 간 직접 파일 전송 시작")
    public Map<String, Object> create(@Valid @RequestBody PeerTransferCreateRequest body,
                                      HttpServletRequest request) {
        String targets = body.targetMacs().stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.joining(","));
        requestSecurity.validateOrThrow(request,
                "createPeerTransfer|" + body.sourceMac().trim().toUpperCase() + "|" + targets + "|"
                        + body.sourcePath().trim() + "|" + body.targetPath().trim() + "|" + body.overwriteExisting());
        FileDistributionJob job = peerTransferService.create(body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getId());
        result.put("status", job.getStatus());
        result.put("sourceMac", job.getSourceAgentId());
        result.put("sourcePath", job.getSourcePath());
        result.put("targetPath", job.getTargetPath());
        result.put("targetCount", body.targetMacs().size());
        result.put("updatePackageType", PeerTransferService.PACKAGE_TYPE);
        return result;
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "PC 간 직접 파일 전송 취소")
    public Map<String, Object> cancel(@PathVariable Long jobId, HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "cancelPeerTransfer|" + jobId);
        return peerTransferService.cancel(jobId);
    }

    @PostMapping("/{jobId}/retry-failed")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "PC 간 직접 파일 전송 실패 대상 재시도")
    public Map<String, Object> retryFailed(@PathVariable Long jobId, HttpServletRequest request) {
        requestSecurity.validateOrThrow(request, "retryPeerTransfer|" + jobId);
        return peerTransferService.retryFailed(jobId);
    }
}
