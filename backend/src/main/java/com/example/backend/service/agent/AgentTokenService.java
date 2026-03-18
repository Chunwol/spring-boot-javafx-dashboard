package com.example.backend.service.agent;

import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentTokenService {

    private final AgentAuthTokenRepository tokenRepository;

    /** plainToken → SHA-256 해시 후 DB 조회, revoked 확인 */
    @Transactional
    public Optional<String> validateToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) return Optional.empty();
        try {
            String hash = sha256(plainToken);
            Optional<AgentAuthToken> opt = tokenRepository.findByTokenHash(hash);
            if (opt.isEmpty()) return Optional.empty();
            AgentAuthToken tok = opt.get();
            if (tok.isRevoked()) return Optional.empty();
            tok.setLastUsedAt(LocalDateTime.now());
            tokenRepository.save(tok);
            return Optional.of(tok.getMacAddress());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void revokeByMac(String macAddress) {
        tokenRepository.findByMacAddress(macAddress).ifPresent(tok -> {
            tok.setRevoked(true);
            tokenRepository.save(tok);
        });
    }

    public static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
