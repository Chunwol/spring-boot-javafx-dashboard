package com.example.backend.model.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 감사 로그 엔티티.
 * 에이전트 등록, 하트비트, 명령 실행, 토큰 폐기 등 주요 시스템 이벤트를
 * 행위자(actor)와 대상(target) 기준으로 기록한다.
 */
@Schema(description = "감사 로그 (주요 시스템 이벤트 기록)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_al_actor", columnList = "actor"),
    @Index(name = "idx_al_action", columnList = "action"),
    @Index(name = "idx_al_created_at", columnList = "created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행위자 (에이전트 MAC 또는 "system") */
    @Schema(description = "행위자 (MAC 주소 또는 system)")
    @Column(nullable = false)
    private String actor;

    /**
     * 이벤트 유형.
     * 예: AGENT_REGISTER, HEARTBEAT, COMMAND_ISSUED, TOKEN_REVOKED
     */
    @Schema(description = "이벤트 유형 (AGENT_REGISTER, HEARTBEAT, COMMAND_ISSUED, TOKEN_REVOKED 등)")
    @Column(nullable = false)
    private String action;

    /** 대상 리소스 유형 (PC, COMMAND, DASHBOARD 등) */
    @Schema(description = "대상 리소스 유형")
    @Column(name = "target_type")
    private String targetType;

    /** 대상 리소스 ID */
    @Schema(description = "대상 리소스 ID")
    @Column(name = "target_id")
    private String targetId;

    /** 이벤트 상세 정보 (JSON 형식) */
    @Schema(description = "이벤트 상세 JSON")
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    /** 요청 IP 주소 */
    @Schema(description = "요청 IP 주소")
    @Column(name = "ip_address")
    private String ipAddress;

    /** 로그인 시도에 사용된 브라우저 User-Agent. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** 로그 생성 일시 (@PrePersist 에서 자동 설정) */
    @Schema(description = "로그 생성 일시")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
