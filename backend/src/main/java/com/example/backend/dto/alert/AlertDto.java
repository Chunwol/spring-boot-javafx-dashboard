package com.example.backend.dto.alert;

import com.example.backend.model.alert.OperationalAlert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 운영 알림(OperationalAlert) 응답 DTO.
 *
 * <p>{@link OperationalAlert} 엔티티를 API 응답용으로 변환한다.
 * 정적 팩토리 메서드 {@link #from(OperationalAlert)}을 통해 생성한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "운영 알림 응답 DTO")
public class AlertDto {

    @Schema(description = "알림 고유 ID", example = "42")
    private Long id;

    @Schema(description = "알림 발생 PC의 MAC 주소", example = "AA:BB:CC:DD:EE:FF")
    private String sourceMac;

    @Schema(description = "알림 메시지", example = "CPU 사용률 90% 초과")
    private String message;

    @Schema(description = "심각도 (INFO / WARN / CRITICAL)", example = "WARN")
    private String severity;

    @Schema(description = "알림 유형 (HIGH_CPU / HIGH_TRAFFIC 등)", example = "HIGH_CPU")
    private String alertType;

    @Schema(description = "확인 처리 여부", example = "false")
    private boolean acknowledged;

    @Schema(description = "확인 처리한 사용자명", example = "admin")
    private String acknowledgedBy;

    @Schema(description = "확인 처리 시각")
    private LocalDateTime acknowledgedAt;

    @Schema(description = "알림 생성 시각")
    private LocalDateTime createdAt;

    /**
     * {@link OperationalAlert} 엔티티로부터 DTO를 생성한다.
     *
     * @param alert 변환할 엔티티 (null 불가)
     * @return 생성된 AlertDto
     */
    public static AlertDto from(OperationalAlert alert) {
        AlertDto dto = new AlertDto();
        dto.setId(alert.getId());
        dto.setSourceMac(alert.getSourceMac());
        dto.setMessage(alert.getMessage());
        dto.setSeverity(alert.getSeverity());
        dto.setAlertType(alert.getAlertType());
        dto.setAcknowledged(alert.getAcknowledged());
        dto.setAcknowledgedBy(alert.getAcknowledgedBy());
        dto.setAcknowledgedAt(alert.getAcknowledgedAt());
        dto.setCreatedAt(alert.getCreatedAt());
        return dto;
    }
}
