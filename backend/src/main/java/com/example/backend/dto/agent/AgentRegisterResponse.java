package com.example.backend.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AgentRegisterResponse {
    private String agentToken;
    private String message;

    public static AgentRegisterResponse of(String agentToken, String message) {
        return new AgentRegisterResponse(agentToken, message);
    }
}
