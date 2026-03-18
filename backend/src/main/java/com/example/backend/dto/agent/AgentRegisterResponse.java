package com.example.backend.dto.agent;

public class AgentRegisterResponse {
    private String agentToken;
    private String message;

    public AgentRegisterResponse() {
    }

    public AgentRegisterResponse(String agentToken, String message) {
        this.agentToken = agentToken;
        this.message = message;
    }

    public String getAgentToken() {
        return agentToken;
    }

    public void setAgentToken(String agentToken) {
        this.agentToken = agentToken;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
