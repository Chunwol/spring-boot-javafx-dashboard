package com.example.backend.model.alert;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 운영 알림 엔티티.
 * 에이전트 또는 시스템에서 발생한 이벤트(오프라인, 트래픽 초과 등)에 대한
 * 알림 내역을 DB에 영속화하고, 확인(acknowledge) 상태를 관리한다.
 */
@Schema(description = "운영 알림 (에이전트 이벤트, 시스템 경보 등)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "operational_alerts", indexes = {
    @Index(name = "idx_oa_source_mac", columnList = "source_mac"),
    @Index(name = "idx_oa_acknowledged", columnList = "acknowledged"),
    @Index(name = "idx_oa_created_at", columnList = "created_at"),
    @Index(name = "idx_oa_alert_type", columnList = "alert_type")
})
public class OperationalAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 알림 발생 소스의 MAC 주소 */
    @Schema(description = "알림 소스 MAC 주소")
    @Column(name = "source_mac", nullable = false)
    private String sourceMac;

    /** 알림 메시지 본문 */
    @Schema(description = "알림 메시지")
    @Column(name = "message", nullable = false)
    private String message;

    /** 심각도 (INFO / WARNING / CRITICAL) */
    @Schema(description = "심각도: INFO, WARNING, CRITICAL")
    @Column(name = "severity", nullable = false)
    private String severity = "WARNING";

    /** 알림 유형 (SYSTEM, TRAFFIC, OFFLINE 등) */
    @Schema(description = "알림 유형")
    @Column(name = "alert_type", nullable = false)
    private String alertType = "SYSTEM";

    /** 관리자 확인 여부 */
    @Schema(description = "관리자 확인 여부")
    @Column(name = "acknowledged", nullable = false)
    private Boolean acknowledged = false;

    /** 확인 일시 */
    @Schema(description = "확인 일시")
    @Column(name = "acknowledged_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime acknowledgedAt;

    /** 확인한 관리자 사용자명 */
    @Schema(description = "확인한 관리자")
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    /** 알림 생성 일시 */
    @Schema(description = "알림 생성 일시")
    @Column(name = "created_at", nullable = false)
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;

    /**
     * null-safe acknowledged getter.
     * DB에 null 이 저장된 경우 false 를 반환한다.
     */
    public Boolean getAcknowledged() {
        return acknowledged != null ? acknowledged : false;
    }
}
