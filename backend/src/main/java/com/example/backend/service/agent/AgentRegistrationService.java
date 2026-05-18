package com.example.backend.service.agent;

import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 에이전트 최초 등록 서비스.
 * <p>
 * 설치키 검증 → PC 레코드 upsert → 에이전트 인증 토큰 발급 순서로 처리한다.
 * 동일 MAC에 대한 동시 등록 요청은 Stripe 기반 내부 Lock으로 직렬화하고,
 * 분산 환경에서 UNIQUE 충돌이 발생하면 낙관적 재시도(saveTokenResilient)로 처리한다.
 * </p>
 */
@Service
public class AgentRegistrationService {

    private static final int LOCK_STRIPE_COUNT = 512;

    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    /**
     * Stripe Lock: MAC 해시 기반으로 512개의 독립 잠금 슬롯을 사용.
     * 모든 등록 요청을 단일 lock으로 직렬화하면 성능 병목이 발생하므로,
     * MAC별로 같은 슬롯에만 경합이 생기도록 분산한다.
     */
    private final Object[] registrationLockStripes = IntStream.range(0, LOCK_STRIPE_COUNT)
            .mapToObj(i -> new Object())
            .toArray();

    @Value("${agent.install-key:CHANGE_ME_INSTALL_KEY}")
    private String installKey;

    public AgentRegistrationService(PcMasterRepository pcMasterRepository,
                                    AgentAuthTokenRepository agentAuthTokenRepository) {
        this.pcMasterRepository = pcMasterRepository;
        this.agentAuthTokenRepository = agentAuthTokenRepository;
    }

    /**
     * 에이전트를 등록하고 인증 토큰을 반환한다.
     *
     * @param requestInstallKey 설치키 (헤더 또는 바디에서 전달)
     * @param request           등록 요청 DTO (macAddress, hostname, ipAddress)
     * @return 발급된 plaintext 토큰과 안내 메시지
     */
    @Transactional
    public AgentRegisterResponse registerAgent(String requestInstallKey, AgentRegisterRequest request) {
        validateInstallKey(requestInstallKey);
        validateRequest(request);

        String mac = request.getMacAddress().trim().toUpperCase();
        synchronized (lockForMac(mac)) {
            return registerAgentUnderLock(mac, request);
        }
    }

    private Object lockForMac(String mac) {
        int idx = Math.floorMod(mac.hashCode(), LOCK_STRIPE_COUNT);
        return registrationLockStripes[idx];
    }

    private AgentRegisterResponse registerAgentUnderLock(String mac, AgentRegisterRequest request) {
        PcMaster pc = pcMasterRepository.findById(mac).orElseGet(PcMaster::new);
        pc.setMacAddress(mac);
        pc.setPcName(request.getHostname());
        pc.setIpAddress(request.getIpAddress());
        if (pc.getStatus() == null) {
            pc.setStatus("OFFLINE");
        }
        if (pc.getRole() == null) {
            pc.setRole("UNKNOWN");
        }
        pc.setLastSeen(LocalDateTime.now());
        pcMasterRepository.save(pc);

        String plainToken = "agt_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = sha256Hex(plainToken);

        List<AgentAuthToken> existingTokens = agentAuthTokenRepository.findAllByMacAddress(mac);
        AgentAuthToken authToken = existingTokens.stream()
                .reduce(this::preferredToken)
                .orElseGet(AgentAuthToken::new);
        authToken = saveTokenResilient(mac, tokenHash, authToken);

        // 중복 토큰 정리: 최신 1개만 남기고 나머지는 제거
        for (AgentAuthToken token : existingTokens) {
            if (token.getId() != null && !token.getId().equals(authToken.getId())) {
                agentAuthTokenRepository.delete(token);
            }
        }

        return new AgentRegisterResponse(plainToken, "등록 완료. 하트비트를 시작하세요.");
    }

    /**
     * 토큰 저장 시 UNIQUE 제약 충돌이 발생하면 기존 레코드를 다시 읽어 업데이트로 전환한다.
     * 멀티 인스턴스 환경에서 동시 등록이 발생해도 데이터 정합성을 보장한다.
     */
    private AgentAuthToken saveTokenResilient(String mac, String tokenHash, AgentAuthToken candidate) {
        LocalDateTime now = LocalDateTime.now();
        candidate.setMacAddress(mac);
        candidate.setTokenHash(tokenHash);
        candidate.setIssuedAt(now);
        candidate.setLastUsedAt(now);
        candidate.setRevoked(false);

        try {
            return agentAuthTokenRepository.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException ex) {
            // 멀티 인스턴스 동시 등록 시 UNIQUE(mac_address) 충돌 가능.
            // 최신 레코드를 다시 읽어 업데이트로 전환한다.
            AgentAuthToken fallback = agentAuthTokenRepository.findAllByMacAddress(mac).stream()
                    .reduce(this::preferredToken)
                    .orElse(candidate);
            fallback.setMacAddress(mac);
            fallback.setTokenHash(tokenHash);
            fallback.setIssuedAt(now);
            fallback.setLastUsedAt(now);
            fallback.setRevoked(false);
            return agentAuthTokenRepository.saveAndFlush(fallback);
        }
    }

    private AgentAuthToken preferredToken(AgentAuthToken a, AgentAuthToken b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.getLastUsedAt() != null && b.getLastUsedAt() != null) {
            return a.getLastUsedAt().isAfter(b.getLastUsedAt()) ? a : b;
        }
        if (a.getLastUsedAt() != null) return a;
        if (b.getLastUsedAt() != null) return b;
        if (a.getIssuedAt() != null && b.getIssuedAt() != null) {
            return a.getIssuedAt().isAfter(b.getIssuedAt()) ? a : b;
        }
        if (a.getIssuedAt() != null) return a;
        if (b.getIssuedAt() != null) return b;
        return a;
    }

    /**
     * 설치키를 검증한다. 타이밍 어택 방지를 위해 MessageDigest.isEqual(상수 시간 비교)을 사용한다.
     *
     * @param requestInstallKey 요청에서 전달된 설치키
     * @throws IllegalArgumentException 설치키가 없거나 불일치하는 경우
     */
    private void validateInstallKey(String requestInstallKey) {
        if (requestInstallKey == null || requestInstallKey.isBlank()) {
            throw new IllegalArgumentException("설치키가 누락되었습니다.");
        }
        // WHY MessageDigest.isEqual: 문자열 equals()는 길이 불일치 시 조기 종료하여
        // 타이밍 어택으로 키 길이를 추론할 수 있다. 상수 시간 비교로 이를 방지한다.
        boolean match = MessageDigest.isEqual(
                requestInstallKey.getBytes(StandardCharsets.UTF_8),
                installKey.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new IllegalArgumentException("설치키가 유효하지 않습니다.");
        }
    }

    /**
     * 등록 요청 필수 필드(macAddress, hostname)를 검증한다.
     *
     * @param request 에이전트 등록 요청 DTO
     * @throws IllegalArgumentException 필수 필드가 누락된 경우
     */
    private void validateRequest(AgentRegisterRequest request) {
        if (request == null || request.getMacAddress() == null || request.getMacAddress().isBlank()) {
            throw new IllegalArgumentException("macAddress는 필수입니다.");
        }
        if (request.getHostname() == null || request.getHostname().isBlank()) {
            throw new IllegalArgumentException("hostname은 필수입니다.");
        }
    }

    /**
     * 문자열의 SHA-256 해시를 16진수 문자열로 반환한다.
     *
     * @param value 해시할 문자열
     * @return 64자리 16진수 SHA-256 해시
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("토큰 해시 계산 실패", e);
        }
    }
}
