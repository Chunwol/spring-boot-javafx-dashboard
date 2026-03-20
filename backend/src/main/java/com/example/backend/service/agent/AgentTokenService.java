package com.example.backend.service.agent;

import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AgentTokenService {

    private final AgentAuthTokenRepository tokenRepository;

    /**
     * lastUsedAt 지연 업데이트 버퍼 (mac → 마지막 사용 시각).
     * 하트비트마다 DB에 직접 쓰는 대신 메모리에 기록하고,
     * 10분 주기로 배치 저장한다.
     */
    private final Map<String, LocalDateTime> lastUsedBuffer = new ConcurrentHashMap<>();

    /** plainToken → SHA-256 해시 후 DB 조회, revoked 확인 */
    @Transactional(readOnly = true)
    public Optional<String> validateToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) return Optional.empty();
        try {
            String hash = sha256(plainToken);
            Optional<AgentAuthToken> opt = tokenRepository.findByTokenHash(hash);
            if (opt.isEmpty()) return Optional.empty();
            AgentAuthToken tok = opt.get();
            if (tok.isRevoked()) return Optional.empty();
            // lastUsedAt을 메모리 버퍼에만 기록 (DB 쓰기 생략)
            lastUsedBuffer.put(tok.getMacAddress(), LocalDateTime.now());
            return Optional.of(tok.getMacAddress());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 10분마다 lastUsedAt을 일괄 DB 업데이트 */
    @Scheduled(fixedRate = 600_000)
    @Transactional
    public void flushLastUsed() {
        if (lastUsedBuffer.isEmpty()) return;
        // remove(mac, usedAt)로 비교 삭제하면 flush 중 새로 들어온 더 최신 값은 보존된다.
        lastUsedBuffer.forEach((mac, usedAt) -> {
            boolean removed = lastUsedBuffer.remove(mac, usedAt);
            if (!removed) return;
            tokenRepository.findByMacAddress(mac).ifPresent(tok -> {
                tok.setLastUsedAt(usedAt);
                tokenRepository.save(tok);
            });
        });
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
