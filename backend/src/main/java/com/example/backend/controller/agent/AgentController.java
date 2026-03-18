package com.example.backend.controller.agent;

import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.service.agent.AgentRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentRegistrationService agentRegistrationService;

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
}
