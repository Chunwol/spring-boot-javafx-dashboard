package com.example.backend.service.agent;

import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTokenServiceTest {

    @Mock
    AgentAuthTokenRepository tokenRepository;

    @InjectMocks
    AgentTokenService agentTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(agentTokenService, "tokenCacheTtlSeconds", 300L);
    }

    @Test
    void validateToken_usesInMemoryCacheAfterFirstLookup() throws Exception {
        String plainToken = "agt_test_token";
        String tokenHash = AgentTokenService.sha256(plainToken);

        AgentAuthToken token = new AgentAuthToken();
        token.setMacAddress("AA:BB:CC:DD:EE:10");
        token.setTokenHash(tokenHash);
        token.setIssuedAt(LocalDateTime.now());
        token.setLastUsedAt(LocalDateTime.now());
        token.setRevoked(false);

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        Optional<String> first = agentTokenService.validateToken(plainToken);
        Optional<String> second = agentTokenService.validateToken(plainToken);

        assertThat(first).contains("AA:BB:CC:DD:EE:10");
        assertThat(second).contains("AA:BB:CC:DD:EE:10");
        verify(tokenRepository, times(1)).findByTokenHash(tokenHash);
    }

    @Test
    void revokeByMac_evictsCachedValidationEntry() throws Exception {
        String plainToken = "agt_test_token_revoke";
        String tokenHash = AgentTokenService.sha256(plainToken);

        AgentAuthToken token = new AgentAuthToken();
        token.setMacAddress("AA:BB:CC:DD:EE:11");
        token.setTokenHash(tokenHash);
        token.setIssuedAt(LocalDateTime.now());
        token.setLastUsedAt(LocalDateTime.now());
        token.setRevoked(false);

        AgentAuthToken persisted = new AgentAuthToken();
        persisted.setMacAddress("AA:BB:CC:DD:EE:11");
        persisted.setRevoked(false);

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));
        when(tokenRepository.findAllByMacAddress("AA:BB:CC:DD:EE:11")).thenReturn(List.of(persisted));

        assertThat(agentTokenService.validateToken(plainToken)).contains("AA:BB:CC:DD:EE:11");
        agentTokenService.revokeByMac("AA:BB:CC:DD:EE:11");

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());
        assertThat(agentTokenService.validateToken(plainToken)).isEmpty();
        verify(tokenRepository, times(2)).findByTokenHash(tokenHash);
    }
}
