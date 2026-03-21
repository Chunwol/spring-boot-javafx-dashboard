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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentRegistrationService {

    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    private final ConcurrentMap<String, Object> registrationLocks = new ConcurrentHashMap<>();

    @Value("${agent.install-key:CHANGE_ME_INSTALL_KEY}")
    private String installKey;

    public AgentRegistrationService(PcMasterRepository pcMasterRepository,
                                    AgentAuthTokenRepository agentAuthTokenRepository) {
        this.pcMasterRepository = pcMasterRepository;
        this.agentAuthTokenRepository = agentAuthTokenRepository;
    }

    @Transactional
    public AgentRegisterResponse registerAgent(String requestInstallKey, AgentRegisterRequest request) {
        validateInstallKey(requestInstallKey);
        validateRequest(request);

        String mac = request.getMacAddress().trim().toUpperCase();
        Object lock = registrationLocks.computeIfAbsent(mac, key -> new Object());
        try {
            synchronized (lock) {
                return registerAgentUnderLock(mac, request);
            }
        } finally {
            registrationLocks.remove(mac, lock);
        }
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

    private void validateInstallKey(String requestInstallKey) {
        if (requestInstallKey == null || requestInstallKey.isBlank()) {
            throw new IllegalArgumentException("설치키가 누락되었습니다.");
        }
        // 타이밍 어택 방지: MessageDigest.isEqual로 상수 시간 비교
        boolean match = MessageDigest.isEqual(
                requestInstallKey.getBytes(StandardCharsets.UTF_8),
                installKey.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new IllegalArgumentException("설치키가 유효하지 않습니다.");
        }
    }

    private void validateRequest(AgentRegisterRequest request) {
        if (request == null || request.getMacAddress() == null || request.getMacAddress().isBlank()) {
            throw new IllegalArgumentException("macAddress는 필수입니다.");
        }
        if (request.getHostname() == null || request.getHostname().isBlank()) {
            throw new IllegalArgumentException("hostname은 필수입니다.");
        }
    }

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
