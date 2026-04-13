package com.example.backend.controller.agent;

import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.service.agent.AgentRegistrationService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentRegistrationService agentRegistrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8888}")
    private int serverPort;

    @Value("${agent.install-key:CHANGE_ME_INSTALL_KEY}")
    private String installKey;

    public AgentController(AgentRegistrationService agentRegistrationService) {
        this.agentRegistrationService = agentRegistrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerAgent(
            @RequestHeader(value = "X-Agent-Install-Key", required = false) String installKey,
            @Valid @RequestBody AgentRegisterRequest request) {
        AgentRegisterResponse response = agentRegistrationService.registerAgent(installKey, request);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * 에이전트 config.json 다운로드
     * 현재 연결된 서버 정보를 포함하여 JSON 생성
     */
    @GetMapping("/config/download")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> downloadConfig(
            @RequestHeader(value = "Host", required = false) String hostHeader) throws JacksonException {

        // 요청한 호스트 정보 추출 (Host 헤더에서)
        String serverUrl = "http://localhost:8888"; // 기본값
        if (hostHeader != null && !hostHeader.isBlank()) {
            String host = hostHeader.split(":")[0]; // hosts split if port included
            serverUrl = "http://" + host + ":" + serverPort;
        }

        // config.json 내용 생성
        Map<String, Object> config = new HashMap<>();
        config.put("serverUrl", serverUrl);
        config.put("installKey", installKey);
        config.put("agentToken", "");
        config.put("heartbeatIntervalSec", 120);
        config.put("jitterSec", 30);
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
