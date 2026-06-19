package com.example.backend.service.agent;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.model.UserSetting;
import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.UserSettingRepository;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 에이전트 최초 등록 서비스.
 * <p>
 * 설치키 검증 → PC 레코드 upsert → 에이전트 인증 토큰 발급 순서로 처리한다.
 * 동일 MAC에 대한 동시 등록 요청은 PostgreSQL 트랜잭션 advisory lock(pg_advisory_xact_lock)으로
 * 직렬화하여 UNIQUE(mac_address) 충돌을 방지한다.
 * </p>
 */
@Service
public class AgentRegistrationService {

    /** user_settings 테이블에서 등록 허용 여부를 관리하는 키 */
    static final String SETTING_KEY_REGISTRATION_ENABLED = "agent.registration.enabled";

    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    private final AgentTokenService agentTokenService;
    private final AgentInstallKeyService agentInstallKeyService;
    private final UserSettingRepository userSettingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AgentRegistrationService(PcMasterRepository pcMasterRepository,
                                    AgentAuthTokenRepository agentAuthTokenRepository,
                                    AgentTokenService agentTokenService,
                                    AgentInstallKeyService agentInstallKeyService,
                                    UserSettingRepository userSettingRepository) {
        this.pcMasterRepository = pcMasterRepository;
        this.agentAuthTokenRepository = agentAuthTokenRepository;
        this.agentTokenService = agentTokenService;
        this.agentInstallKeyService = agentInstallKeyService;
        this.userSettingRepository = userSettingRepository;
    }

    /**
     * 에이전트 등록 허용 여부를 반환한다.
     * user_settings 테이블에 키가 없으면 기본값 true(허용)로 동작한다.
     */
    public boolean isRegistrationEnabled() {
        return userSettingRepository.findById(SETTING_KEY_REGISTRATION_ENABLED)
                .map(s -> !"false".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    /**
     * 에이전트 등록 허용 여부를 설정한다.
     */
    @Transactional
    public void setRegistrationEnabled(boolean enabled) {
        UserSetting setting = userSettingRepository.findById(SETTING_KEY_REGISTRATION_ENABLED)
                .orElse(new UserSetting(SETTING_KEY_REGISTRATION_ENABLED, "true"));
        setting.setValue(String.valueOf(enabled));
        userSettingRepository.save(setting);
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
        // 등록 차단 상태 확인 — 윈도우 이미지 배포 시 중복 등록 방지용
        if (!isRegistrationEnabled()) {
            throw new RegistrationDisabledException("에이전트 등록이 비활성화되어 있습니다. 관리자에게 문의하세요.");
        }
        validateInstallKey(requestInstallKey);
        validateRequest(request);

        String mac = request.getMacAddress().trim().toUpperCase();
        return registerAgentUnderLock(mac, request);
    }

    private AgentRegisterResponse registerAgentUnderLock(String mac, AgentRegisterRequest request) {
        // 동일 MAC에 대한 동시 등록을 직렬화한다.
        // 트랜잭션 commit까지 잠금이 유지되므로(advisory lock은 트랜잭션 종료 시 자동 해제),
        // 이후 findAllByMacAddress가 직전 트랜잭션의 커밋 결과를 반드시 보게 되어
        // UNIQUE(mac_address) 충돌(및 그로 인한 Hibernate AssertionFailure)을 방지한다.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1)::bigint)")
                .setParameter(1, mac)
                .getSingleResult();

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
        authToken = persistToken(mac, tokenHash, authToken);

        // 중복 토큰 정리: 최신 1개만 남기고 나머지는 제거
        for (AgentAuthToken token : existingTokens) {
            if (token.getId() != null && !token.getId().equals(authToken.getId())) {
                agentAuthTokenRepository.delete(token);
            }
        }
        agentTokenService.evictCachedTokensForMac(mac);

        return AgentRegisterResponse.of(plainToken, "등록 완료. 하트비트를 시작하세요.");
    }

    /**
     * 토큰을 저장(insert 또는 update)한다.
     * pg_advisory_xact_lock으로 동일 MAC에 대한 호출이 직렬화되므로
     * UNIQUE(mac_address) 충돌은 발생하지 않는다.
     */
    private AgentAuthToken persistToken(String mac, String tokenHash, AgentAuthToken candidate) {
        LocalDateTime now = LocalDateTime.now();
        candidate.setMacAddress(mac);
        candidate.setTokenHash(tokenHash);
        candidate.setIssuedAt(now);
        candidate.setLastUsedAt(now);
        candidate.setRevoked(false);
        return agentAuthTokenRepository.saveAndFlush(candidate);
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
     * @throws UnauthorizedException 설치키가 없거나 불일치하는 경우
     */
    private void validateInstallKey(String requestInstallKey) {
        if (requestInstallKey == null || requestInstallKey.isBlank()) {
            throw new UnauthorizedException("설치키가 누락되었습니다.");
        }
        String currentInstallKey = agentInstallKeyService.getInstallKey();
        if (currentInstallKey == null || currentInstallKey.isBlank()) {
            throw new UnauthorizedException("서버 설치키가 설정되어 있지 않습니다.");
        }
        // WHY MessageDigest.isEqual: 문자열 equals()는 길이 불일치 시 조기 종료하여
        // 타이밍 어택으로 키 길이를 추론할 수 있다. 상수 시간 비교로 이를 방지한다.
        boolean match = MessageDigest.isEqual(
                requestInstallKey.getBytes(StandardCharsets.UTF_8),
                currentInstallKey.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new UnauthorizedException("설치키가 유효하지 않습니다.");
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

    /** 에이전트 등록이 관리자에 의해 비활성화된 경우 발생하는 예외 */
    public static class RegistrationDisabledException extends RuntimeException {
        public RegistrationDisabledException(String message) {
            super(message);
        }
    }
}
