package com.example.backend.service.agent;

import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 에이전트 인증 토큰 검증 및 lastUsedAt 지연 업데이트 서비스.
 * <p>
 * 하트비트마다 DB에 직접 쓰면 부하가 집중되므로, lastUsedAt을 메모리 버퍼에 기록하고
 * 10분 주기로 일괄 업데이트(flush)한다.
 * ConcurrentHashMap.remove(key, value)를 사용하여 flush 도중 들어온
 * 더 최신 값이 손실되지 않도록 보장한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AgentTokenService {

    private static final Logger log = LoggerFactory.getLogger(AgentTokenService.class);

    private final AgentAuthTokenRepository tokenRepository;

    @Value("${app.agent.token-cache-ttl-seconds:300}")
    private long tokenCacheTtlSeconds;

    /** lastUsedAt 지연 업데이트 버퍼 (mac → 마지막 사용 시각). */
    private final Map<String, LocalDateTime> lastUsedBuffer = new ConcurrentHashMap<>();

    /** 유효한 agent token 검증 결과 캐시 (tokenHash → macAddress, expiresAt). */
    private final Map<String, TokenValidationCacheEntry> tokenValidationCache = new ConcurrentHashMap<>();

    /**
     * flush 중복 실행 방지 플래그.
     * @Scheduled + @Transactional의 race condition을 방지하기 위해 메서드 레벨에서만 사용한다.
     */
    private final AtomicBoolean flushRunning = new AtomicBoolean(false);

    /**
     * plainToken을 SHA-256 해시 후 DB 조회하여 MAC 주소를 반환한다.
     * revoked 토큰은 거부한다.
     *
     * @param plainToken 에이전트가 전달한 평문 토큰
     * @return 유효하면 MAC 주소, 아니면 empty
     */
    @Transactional(readOnly = true)
    public Optional<String> validateToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) return Optional.empty();
        try {
            String hash = sha256(plainToken);
            LocalDateTime now = LocalDateTime.now();

            TokenValidationCacheEntry cached = tokenValidationCache.get(hash);
            if (cached != null) {
                if (cached.expiresAt().isAfter(now)) {
                    lastUsedBuffer.put(cached.macAddress(), now);
                    return Optional.of(cached.macAddress());
                }
                tokenValidationCache.remove(hash, cached);
            }

            Optional<AgentAuthToken> opt = tokenRepository.findByTokenHash(hash);
            if (opt.isEmpty()) return Optional.empty();
            AgentAuthToken tok = opt.get();
            if (tok.isRevoked()) return Optional.empty();
            // lastUsedAt을 메모리 버퍼에만 기록 (DB 쓰기 생략)
            lastUsedBuffer.put(tok.getMacAddress(), now);
            if (tokenCacheTtlSeconds > 0) {
                tokenValidationCache.put(hash,
                        new TokenValidationCacheEntry(tok.getMacAddress(), now.plusSeconds(tokenCacheTtlSeconds)));
            }
            return Optional.of(tok.getMacAddress());
        } catch (Exception e) {
            log.debug("토큰 검증 중 예외 발생 (무시): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 10분마다 lastUsedAt을 일괄 DB 업데이트한다.
     * <p>
     * remove(mac, usedAt)로 비교 삭제하여 flush 진행 중에 새로 들어온 더 최신 값은 버퍼에 남긴다.
     * flushRunning AtomicBoolean은 @Scheduled 중복 실행을 방지하며,
     * @Transactional은 실제 DB 작업 범위에만 적용된다.
     * </p>
     */
    @Scheduled(fixedRate = 600_000)
    @Transactional
    public void flushLastUsed() {
        if (!flushRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (lastUsedBuffer.isEmpty()) return;
            // remove(mac, usedAt): flush 도중 새로 갱신된 최신값은 버퍼에 보존
            lastUsedBuffer.forEach((mac, usedAt) -> {
                boolean removed = lastUsedBuffer.remove(mac, usedAt);
                if (!removed) return;
                tokenRepository.findAllByMacAddress(mac).forEach(tok -> {
                    tok.setLastUsedAt(usedAt);
                    tokenRepository.save(tok);
                });
            });
        } finally {
            flushRunning.set(false);
        }
    }

    /**
     * 특정 MAC의 모든 토큰을 revoke 처리한다.
     *
     * @param macAddress 대상 에이전트 MAC 주소
     */
    @Transactional
    public void revokeByMac(String macAddress) {
        evictCachedTokensForMac(macAddress);
        tokenRepository.findAllByMacAddress(macAddress).forEach(tok -> {
            tok.setRevoked(true);
            tokenRepository.save(tok);
        });
    }

    public void evictCachedTokensForMac(String macAddress) {
        if (macAddress == null || macAddress.isBlank()) {
            return;
        }
        tokenValidationCache.entrySet().removeIf(entry -> macAddress.equalsIgnoreCase(entry.getValue().macAddress()));
    }

    /**
     * 입력 문자열의 SHA-256 해시를 16진수 문자열로 반환한다.
     *
     * @param input 해시할 문자열
     * @return 64자리 16진수 해시
     */
    public static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private record TokenValidationCacheEntry(String macAddress, LocalDateTime expiresAt) {
    }
}
