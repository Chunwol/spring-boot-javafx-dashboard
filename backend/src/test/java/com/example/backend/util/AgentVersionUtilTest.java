package com.example.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionUtilTest {

    @Test
    void isAtLeast_comparesSemanticVersionAndAcceptsSuffix() {
        assertThat(AgentVersionUtil.isAtLeast("1.1.24", 1, 1, 24)).isTrue();
        assertThat(AgentVersionUtil.isAtLeast("1.1.24-SNAPSHOT", 1, 1, 24)).isTrue();
        assertThat(AgentVersionUtil.isAtLeast("1.2.0", 1, 1, 24)).isTrue();
        assertThat(AgentVersionUtil.isAtLeast("1.1.23", 1, 1, 24)).isFalse();
        assertThat(AgentVersionUtil.isAtLeast("invalid", 1, 1, 24)).isFalse();
        assertThat(AgentVersionUtil.isAtLeast(null, 1, 1, 24)).isFalse();
    }
}
