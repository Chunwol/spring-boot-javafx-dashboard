package com.example.backend.controller.scriptlibrary;

import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.scriptlibrary.ScriptLibraryExecutionRequest;
import com.example.backend.dto.scriptlibrary.ScriptLibraryRerunRequest;
import com.example.backend.service.scriptlibrary.ScriptLibraryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/script-library")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('PERM_SCRIPT_LIBRARY')")
@Tag(name = "Script Library", description = "버전 고정 PowerShell 보관함과 최근 실행")
public class ScriptLibraryController {
    private final ScriptLibraryService service;

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return service.listTemplates();
    }

    @PostMapping(value = "/templates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(
            @RequestParam String name,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "사용자") String category,
            @RequestParam(defaultValue = "MEDIUM") String riskLevel,
            @RequestParam(defaultValue = "false") boolean requiresReboot,
            @RequestParam(defaultValue = "false") boolean requiresOperatorMode,
            @RequestParam(defaultValue = "WINDOWS_POWERSHELL_5_1") String powershellRequirement,
            @RequestParam(defaultValue = "") String successMarker,
            @RequestParam(required = false) Long inverseTemplateId,
            @RequestPart("file") MultipartFile file) throws Exception {
        requireScriptSize(file);
        return service.createTemplate(name, description, category, riskLevel, requiresReboot,
                requiresOperatorMode, powershellRequirement, successMarker, inverseTemplateId,
                file.getOriginalFilename(), file.getBytes());
    }

    @PutMapping(value = "/templates/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> update(
            @PathVariable Long id,
            @RequestParam String name,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "사용자") String category,
            @RequestParam(defaultValue = "MEDIUM") String riskLevel,
            @RequestParam(defaultValue = "false") boolean requiresReboot,
            @RequestParam(defaultValue = "false") boolean requiresOperatorMode,
            @RequestParam(defaultValue = "WINDOWS_POWERSHELL_5_1") String powershellRequirement,
            @RequestParam(defaultValue = "") String successMarker,
            @RequestParam(required = false) Long inverseTemplateId,
            @RequestPart(value = "file", required = false) MultipartFile file) throws Exception {
        if (file != null) requireScriptSize(file);
        return service.updateTemplate(id, name, description, category, riskLevel, requiresReboot,
                requiresOperatorMode, powershellRequirement, successMarker, inverseTemplateId,
                file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes());
    }

    @DeleteMapping("/templates/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return service.deleteTemplate(id);
    }

    @PostMapping("/templates/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable Long id,
                                                       @Valid @RequestBody ScriptLibraryExecutionRequest request) {
        return ResponseEntity.accepted().body(service.execute(id, request));
    }

    @GetMapping("/executions/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "10") int limit) {
        return service.recentExecutions(limit);
    }

    @GetMapping("/executions/{id}")
    public Map<String, Object> execution(@PathVariable Long id) {
        return service.getExecution(id);
    }

    @PostMapping("/executions/{id}/rerun")
    public ResponseEntity<Map<String, Object>> rerun(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ScriptLibraryRerunRequest request) {
        ScriptLibraryRerunRequest actual = request == null
                ? new ScriptLibraryRerunRequest(null, null, null, null, false)
                : request;
        return ResponseEntity.accepted().body(service.rerun(id, actual));
    }

    @PostMapping("/executions/{id}/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailed(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean acknowledged = body != null && Boolean.TRUE.equals(body.get("acknowledged"));
        return ResponseEntity.accepted().body(service.retryFailed(id, acknowledged));
    }

    private void requireScriptSize(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new InvalidRequestException("PS1 파일이 필요합니다.");
        if (file.getSize() > 1024L * 1024L) throw new InvalidRequestException("PS1 파일은 1MB를 초과할 수 없습니다.");
    }
}
