package com.example.backend.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.Locale;

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
