package com.example.backend.service.agent;

import com.example.backend.dto.agent.AgentRegisterRequest;
import com.example.backend.dto.agent.AgentRegisterResponse;
import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AgentRegistrationService {

    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;

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

        AgentAuthToken authToken = agentAuthTokenRepository.findByMacAddress(mac)
                .orElseGet(AgentAuthToken::new);
        authToken.setMacAddress(mac);
        authToken.setTokenHash(tokenHash);
        authToken.setIssuedAt(LocalDateTime.now());
        authToken.setLastUsedAt(LocalDateTime.now());
        authToken.setRevoked(false);
        agentAuthTokenRepository.save(authToken);

        return new AgentRegisterResponse(plainToken, "등록 완료. 하트비트를 시작하세요.");
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
