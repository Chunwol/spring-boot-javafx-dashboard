package com.example.backend.model.agent;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 에이전트 인증 토큰 엔티티.
 * MAC 주소와 연결된 에이전트별 JWT/해시 기반 토큰을 관리하며,
 * 발급 일시, 마지막 사용 일시, 폐기 여부를 추적한다.
 */
@Schema(description = "에이전트 인증 토큰 (에이전트 식별 및 접근 제어용)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_auth_tokens", indexes = {
    @Index(name = "idx_aat_mac_address", columnList = "mac_address"),
    @Index(name = "idx_aat_token_hash", columnList = "token_hash"),
    @Index(name = "idx_aat_revoked", columnList = "revoked")
})
public class AgentAuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 에이전트 MAC 주소 (고유 식별자) */
    @Schema(description = "에이전트 MAC 주소")
    @Column(name = "mac_address", nullable = false, unique = true)
    private String macAddress;

    /** 토큰 해시값 (평문 토큰을 저장하지 않음) */
    @Schema(description = "토큰 해시 (SHA-256)")
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    /** 토큰 발급 일시 */
    @Schema(description = "토큰 발급 일시")
    @Column(name = "issued_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime issuedAt;

    /** 마지막 사용 일시 */
    @Schema(description = "마지막 사용 일시")
    @Column(name = "last_used_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime lastUsedAt;

    /** 폐기 여부 (true 이면 해당 토큰으로 인증 불가) */
    @Schema(description = "폐기 여부")
    @Column(nullable = false)
    private boolean revoked;
}
