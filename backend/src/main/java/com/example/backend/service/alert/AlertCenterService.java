package com.example.backend.service.alert;

import com.example.backend.event.AlertEvent;
import com.example.backend.model.alert.OperationalAlert;
import com.example.backend.repository.alert.OperationalAlertRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AlertCenterService {

    private static final int MAX_FETCH_LIMIT = 200;

    private final OperationalAlertRepository operationalAlertRepository;

    public AlertCenterService(OperationalAlertRepository operationalAlertRepository) {
        this.operationalAlertRepository = operationalAlertRepository;
    }

    @Transactional
    public OperationalAlert create(AlertEvent event) {
        OperationalAlert alert = new OperationalAlert();
        alert.setSourceMac(normalizeSourceMac(event.getSourceMac()));
        alert.setMessage(event.getMessage() == null ? "" : event.getMessage());
        alert.setSeverity(normalizeSeverity(event.getSeverity()));
        alert.setAlertType(normalizeType(event.getAlertType()));
        alert.setCreatedAt(event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt());
        alert.setAcknowledged(false);
        return operationalAlertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<OperationalAlert> listRecent(int limit, boolean unreadOnly) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_FETCH_LIMIT));
        PageRequest page = PageRequest.of(0, safeLimit);
        if (unreadOnly) {
            return operationalAlertRepository.findByAcknowledgedFalseOrderByCreatedAtDesc(page).getContent();
        }
        return operationalAlertRepository.findAllByOrderByCreatedAtDesc(page).getContent();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return operationalAlertRepository.countByAcknowledgedFalse();
    }

    @Transactional
    public Optional<OperationalAlert> acknowledge(Long id, String acknowledgedBy) {
        return operationalAlertRepository.findById(id).map(alert -> {
            if (!alert.getAcknowledged()) {
                alert.setAcknowledged(true);
                alert.setAcknowledgedAt(LocalDateTime.now());
                alert.setAcknowledgedBy(resolveAcknowledgedBy(acknowledgedBy));
                operationalAlertRepository.save(alert);
            }
            return alert;
        });
    }

    @Transactional
    public int acknowledgeAllUnread(String acknowledgedBy) {
        return operationalAlertRepository.acknowledgeAllUnread(
                LocalDateTime.now(),
                resolveAcknowledgedBy(acknowledgedBy));
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

    private String resolveAcknowledgedBy(String acknowledgedBy) {
        return (acknowledgedBy == null || acknowledgedBy.isBlank())
                ? "unknown"
                : acknowledgedBy.trim();
    }
}
