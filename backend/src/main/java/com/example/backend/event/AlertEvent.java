package com.example.backend.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 알림(Alert) 발생을 나타내는 Spring 애플리케이션 이벤트.
 *
 * <p>트래픽 임계값 초과, 에이전트 오프라인, 시스템 이상 등 다양한 상황에서
 * {@code ApplicationEventPublisher}를 통해 발행된다.
 * {@code AlertService}가 이 이벤트를 수신하여 DB에 저장하고 WebSocket으로 브로드캐스트한다.
 *
 * <p>필드 정규화 규칙:
 * <ul>
 *   <li>{@code sourceMac}: null 또는 공백이면 {@code "SYSTEM"}으로 대체</li>
 *   <li>{@code severity}: {@code INFO}, {@code WARNING}, {@code CRITICAL} 중 하나;
 *       그 외 값은 {@code WARNING}으로 정규화</li>
 *   <li>{@code alertType}: 대문자로 정규화; null이면 {@code "SYSTEM"}</li>
 * </ul>
 */
public class AlertEvent extends ApplicationEvent {
    private final String sourceMac;
    private final String message;
    private final String severity;
    private final String alertType;
    private final LocalDateTime occurredAt;

    public AlertEvent(Object source, String sourceMac, String message) {
        this(source, sourceMac, message, "WARNING", "SYSTEM", LocalDateTime.now());
    }

    public AlertEvent(Object source, String sourceMac, String message, String severity, String alertType) {
        this(source, sourceMac, message, severity, alertType, LocalDateTime.now());
    }

    public AlertEvent(Object source,
                      String sourceMac,
                      String message,
                      String severity,
                      String alertType,
                      LocalDateTime occurredAt) {
        super(source);
        this.sourceMac = normalizeSourceMac(sourceMac);
        this.message = message == null ? "" : message;
        this.severity = normalizeSeverity(severity);
        this.alertType = normalizeType(alertType);
        this.occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
    }

    private String normalizeSourceMac(String value) {
        return value == null || value.isBlank() ? "SYSTEM" : value.trim();
    }

    private String normalizeSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "WARNING";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INFO", "WARNING", "CRITICAL" -> normalized;
            default -> "WARNING";
        };
    }

    private String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            return "SYSTEM";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public String getSourceMac() {
        return sourceMac;
    }

    public String getMessage() {
        return message;
    }

    public String getSeverity() {
        return severity;
    }

    public String getAlertType() {
        return alertType;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
