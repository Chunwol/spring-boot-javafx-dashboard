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
import jakarta.validation.Valid;
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
@Tag(name = "Agent", description = "에이전트 등록 및 초기 설정 API")
public class AgentController {

    private final AgentRegistrationService agentRegistrationService;
    private final ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8888}")
    private int serverPort;

    public AgentController(AgentRegistrationService agentRegistrationService, ObjectMapper objectMapper) {
        this.agentRegistrationService = agentRegistrationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 에이전트 최초 등록.
     * 설치키(X-Agent-Install-Key 헤더)와 PC 정보를 받아 에이전트 토큰을 발급한다.
     */
    @PostMapping("/register")
    @Operation(
        summary = "에이전트 등록",
        description = "PC 에이전트가 최초 설치 시 설치키와 함께 MAC/IP/hostname 정보를 전송하여 인증 토큰을 발급받습니다."
    )
    @ApiResponse(responseCode = "201", description = "등록 성공 - 에이전트 토큰 반환")
    @ApiResponse(responseCode = "400", description = "요청 데이터 유효성 오류 (macAddress, hostname, ipAddress 형식)")
    @ApiResponse(responseCode = "401", description = "설치키 인증 실패")
    public ResponseEntity<?> registerAgent(
            @Parameter(description = "에이전트 설치키 (관리자에게 문의)") @RequestHeader(value = "X-Agent-Install-Key", required = false) String installKey,
            @Valid @RequestBody AgentRegisterRequest request) {
        AgentRegisterResponse response = agentRegistrationService.registerAgent(installKey, request);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * 에이전트 config.json 다운로드.
     * 현재 서버 URL, 하트비트 간격 등 에이전트 운영에 필요한 설정을 JSON으로 제공한다.
     * 설치키는 보안상 포함하지 않으며 등록 시 관리자가 직접 입력한다.
     */
    @GetMapping("/config/download")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "에이전트 config.json 다운로드",
        description = "에이전트 설치에 필요한 서버 URL·하트비트 설정을 포함한 config.json을 다운로드합니다. 설치키는 보안상 포함되지 않습니다."
    )
    @ApiResponse(responseCode = "200", description = "config.json 파일")
    @ApiResponse(responseCode = "401", description = "관리자 인증 필요")
    public ResponseEntity<Resource> downloadConfig(
            @RequestHeader(value = "Host", required = false) String hostHeader) throws JsonProcessingException {

        String serverUrl = "http://localhost:8888";
        if (hostHeader != null && !hostHeader.isBlank()) {
            String host = hostHeader.split(":")[0];
            serverUrl = "http://" + host + ":" + serverPort;
        }

        // installKey는 config.json에 포함하지 않음 — 등록 시 관리자가 직접 입력
        Map<String, Object> config = new HashMap<>();
        config.put("serverUrl", serverUrl);
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
}
