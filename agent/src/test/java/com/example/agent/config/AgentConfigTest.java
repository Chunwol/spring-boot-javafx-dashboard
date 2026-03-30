package com.example.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentConfigTest {

    @Test
    void defaults() {
        AgentConfig config = new AgentConfig();
        assertThat(config.getServerUrl()).isEqualTo("http://localhost:8888");
        assertThat(config.getInstallKey()).isEmpty();
        assertThat(config.getAgentToken()).isEmpty();
        assertThat(config.getHeartbeatIntervalSec()).isEqualTo(120);
        assertThat(config.getJitterSec()).isEqualTo(30);
    }

    @Test
    void settersAndGetters() {
        AgentConfig config = new AgentConfig();
        config.setServerUrl("https://server:9999");
        config.setInstallKey("key123");
        config.setAgentToken("token-abc");
        config.setHeartbeatIntervalSec(60);
        config.setJitterSec(10);

        assertThat(config.getServerUrl()).isEqualTo("https://server:9999");
        assertThat(config.getInstallKey()).isEqualTo("key123");
        assertThat(config.getAgentToken()).isEqualTo("token-abc");
        assertThat(config.getHeartbeatIntervalSec()).isEqualTo(60);
        assertThat(config.getJitterSec()).isEqualTo(10);
    }
}
