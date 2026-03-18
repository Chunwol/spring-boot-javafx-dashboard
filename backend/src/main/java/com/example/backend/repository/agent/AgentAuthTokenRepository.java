package com.example.backend.repository.agent;

import com.example.backend.model.agent.AgentAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentAuthTokenRepository extends JpaRepository<AgentAuthToken, Long> {
    Optional<AgentAuthToken> findByMacAddress(String macAddress);
    Optional<AgentAuthToken> findByTokenHash(String tokenHash);
}
