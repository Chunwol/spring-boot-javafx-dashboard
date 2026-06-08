package com.example.backend.controller.agent;

import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.service.agent.AgentRegistrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 에이전트 등록 및 초기 설정 API
 *
 * <p>PC에 설치된 에이전트가 최초 실행 시 서버에 등록(토큰 발급)하고,
 * 운영에 필요한 config.json을 다운로드하는 엔드포인트를 제공한다.</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "에이전트 등록 및 초기 설정 API")
public class AgentController {

    private final AgentRegistrationService agentRegistrationService;
    private final ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8888}")
    private int serverPort;

    @Value("${agent.install-key:INST-CHANGE-ME-BEFORE-DEPLOY}")
    private String installKey;

    /**
     * 에이전트 최초 등록.
     * 설치키(X-Agent-Install-Key 헤더)와 PC 정보를 받아 에이전트 토큰을 발급한다.
     * 등록이 비활성화된 경우 423 Locked를 반환한다.
     */
    @PostMapping("/register")
    @Operation(
        summary = "에이전트 등록",
        description = "PC 에이전트가 최초 설치 시 설치키와 함께 MAC/IP/hostname 정보를 전송하여 인증 토큰을 발급받습니다."
    )
    @ApiResponse(responseCode = "201", description = "등록 성공 - 에이전트 토큰 반환")
    @ApiResponse(responseCode = "400", description = "요청 데이터 유효성 오류 (macAddress, hostname, ipAddress 형식)")
    @ApiResponse(responseCode = "401", description = "설치키 인증 실패")
    @ApiResponse(responseCode = "423", description = "등록 비활성화 상태 - 관리자가 등록을 차단 중")
    public ResponseEntity<?> registerAgent(
            @Parameter(description = "에이전트 설치키 (관리자에게 문의)") @RequestHeader(value = "X-Agent-Install-Key", required = false) String installKey,
            @Valid @RequestBody AgentRegisterRequest request) {
        try {
            AgentRegisterResponse response = agentRegistrationService.registerAgent(installKey, request);
            return ResponseEntity.status(201).body(response);
        } catch (AgentRegistrationService.RegistrationDisabledException e) {
            return ResponseEntity.status(423)
                    .body(Map.of("error", "REGISTRATION_DISABLED", "message", e.getMessage()));
        }
    }

    /**
     * 에이전트 등록 허용 여부 조회. (ADMIN 전용)
     */
    @GetMapping("/registration-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "에이전트 등록 허용 상태 조회", description = "현재 에이전트 등록이 허용 상태인지 여부를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "등록 상태 조회 성공")
    public ResponseEntity<Map<String, Object>> getRegistrationStatus() {
        boolean enabled = agentRegistrationService.isRegistrationEnabled();
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    /**
     * 에이전트 등록 허용/차단 전환. (ADMIN 전용)
     * 윈도우 이미지 배포 시 동일 MAC으로 100대가 동시 등록되는 것을 막는 용도로 사용한다.
     */
    @PutMapping("/registration-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "에이전트 등록 허용/차단",
        description = "에이전트 등록을 활성화하거나 비활성화합니다. " +
                      "윈도우 이미지 배포 중에는 비활성화하여 동일 MAC 중복 등록을 방지하세요."
    )
    @ApiResponse(responseCode = "204", description = "설정 변경 성공")
    public ResponseEntity<Void> setRegistrationStatus(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().build();
        }
        agentRegistrationService.setRegistrationEnabled(enabled);
        return ResponseEntity.noContent().build();
    }

    /**
     * 에이전트 config.json 다운로드.
     * 현재 서버 URL, 설치키, 하트비트 간격 등 에이전트 운영에 필요한 설정을 JSON으로 제공한다.
     */
    @GetMapping("/config/download")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "에이전트 config.json 다운로드",
        description = "에이전트 설치에 필요한 서버 URL·설치키·하트비트 설정을 포함한 config.json을 다운로드합니다."
    )
    @ApiResponse(responseCode = "200", description = "config.json 파일")
    @ApiResponse(responseCode = "401", description = "관리자 인증 필요")
    public ResponseEntity<Resource> downloadConfig(
            HttpServletRequest request) throws JsonProcessingException {
        Map<String, Object> config = new HashMap<>();
        config.put("serverUrl", resolveExternalServerUrl(request));
        config.put("installKey", installKey);
        config.put("agentToken", "");
        config.put("heartbeatIntervalSec", 12);
        config.put("jitterSec", 3);
        config.put("githubOwner", "");
        config.put("githubRepo", "");

        byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(config);

        Resource resource = new ByteArrayResource(jsonBytes);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=config.json")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(jsonBytes.length)
                .body(resource);
    }

    private String resolveExternalServerUrl(HttpServletRequest request) {
        String scheme = firstHeader(request, "X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }

        String forwardedHost = firstHeader(request, "X-Forwarded-Host");
        String host = forwardedHost;
        String explicitPort = null;
        if (host == null || host.isBlank()) {
            host = request.getServerName();
        } else if (host.contains(":")) {
            int idx = host.lastIndexOf(':');
            explicitPort = host.substring(idx + 1);
            host = host.substring(0, idx);
        }

        String forwardedPort = firstHeader(request, "X-Forwarded-Port");
        int port = parsePort(forwardedPort,
                parsePort(explicitPort, request.getServerPort() > 0 ? request.getServerPort() : serverPort));

        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        String base = scheme + "://" + host + (defaultPort ? "" : ":" + port);
        return (contextPath == null || contextPath.isBlank()) ? base : base + contextPath;
    }

    private String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",")[0].trim();
    }

    private int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
