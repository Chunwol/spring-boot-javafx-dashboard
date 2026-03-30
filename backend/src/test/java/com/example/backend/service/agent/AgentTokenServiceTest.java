package com.example.backend.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentTokenServiceTest {

    @Test
    void sha256_deterministicOutput() throws Exception {
        String hash1 = AgentTokenService.sha256("hello");
        String hash2 = AgentTokenService.sha256("hello");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void sha256_knownHash() throws Exception {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        String hash = AgentTokenService.sha256("hello");
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sha256_differentInputs_differentHashes() throws Exception {
        String h1 = AgentTokenService.sha256("abc");
        String h2 = AgentTokenService.sha256("def");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void sha256_emptyString() throws Exception {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String hash = AgentTokenService.sha256("");
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
