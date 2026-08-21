package com.example.agent.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatSenderRuntimeIdentityTest {

    @Test
    void heartbeatIdentityKeepsSessionIdAndStableOsBootTimeAsSeparateFields() {
        Map<String, Object> payload = new LinkedHashMap<>();

        HeartbeatSender.putRuntimeIdentity(payload, "session-1", 1_786_532_400L);

        assertThat(payload).containsEntry("agentSessionId", "session-1");
        assertThat(payload).containsEntry("systemBootTimeEpochSeconds", 1_786_532_400L);
    }

    @Test
    void bootTimeUsesOsUptimeInsteadOfAgentProcessStart() {
        long now = 1_786_600_000L;
        long fourHoursMillis = 4L * 60L * 60L * 1000L;

        assertThat(HeartbeatSender.bootTimeFromUptime(now, fourHoursMillis))
                .isEqualTo(now - 4L * 60L * 60L);
    }
}
