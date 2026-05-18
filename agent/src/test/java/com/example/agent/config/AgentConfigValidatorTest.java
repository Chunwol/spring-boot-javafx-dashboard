package com.example.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigValidatorTest {

    private final AgentConfigValidator validator = new AgentConfigValidator();

    @Test
    void validate_requiresInstallKeyWhenTokenMissing() {
        AgentConfig config = new AgentConfig();
        config.setServerUrl("http://localhost:8888");
        config.setAgentToken("");
        config.setInstallKey("");
        config.setHeartbeatIntervalSec(30);
        config.setJitterSec(5);

        List<String> errors = validator.validate(config);

        assertThat(errors).contains("installKey is required when agentToken is empty");
    }

    @Test
    void validate_allowsMissingInstallKeyWhenTokenExists() {
        AgentConfig config = new AgentConfig();
        config.setServerUrl("http://localhost:8888");
        config.setAgentToken("agt_token_123");
        config.setInstallKey("");
        config.setHeartbeatIntervalSec(30);
        config.setJitterSec(5);

        List<String> errors = validator.validate(config);

        assertThat(errors).doesNotContain("installKey is required when agentToken is empty");
    }

    @Test
    void validate_detectsInvalidJitterRule() {
        AgentConfig config = new AgentConfig();
        config.setServerUrl("http://localhost:8888");
        config.setAgentToken("agt_token_123");
        config.setHeartbeatIntervalSec(10);
        config.setJitterSec(10);

        List<String> errors = validator.validate(config);

        assertThat(errors)
                .anyMatch(msg -> msg.contains("must be less than heartbeatIntervalSec"));
    }
}
