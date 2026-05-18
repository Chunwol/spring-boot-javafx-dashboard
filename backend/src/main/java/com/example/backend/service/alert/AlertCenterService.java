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

/**
 * 운영 알림(OperationalAlert) CRUD 서비스.
 * <p>
 * AlertEvent를 받아 DB에 저장하고, 목록 조회·읽음 처리 API를 제공한다.
 * 심각도(severity)와 유형(alertType)은 표준화된 값으로 정규화한다.
 * </p>
 */
@Service
public class AlertCenterService {

    private static final int MAX_FETCH_LIMIT = 200;

    private final OperationalAlertRepository operationalAlertRepository;

    public AlertCenterService(OperationalAlertRepository operationalAlertRepository) {
        this.operationalAlertRepository = operationalAlertRepository;
    }

    /**
     * AlertEvent를 DB에 저장한다.
     *
     * @param event 발생한 알림 이벤트
     * @return 저장된 OperationalAlert 엔티티
     */
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

    /**
     * 최근 알림 목록을 반환한다.
     *
     * @param limit      최대 조회 건수 (1~200)
     * @param unreadOnly true면 미확인 알림만 반환
     * @return 알림 목록 (최신순)
     */
    @Transactional(readOnly = true)
    public List<OperationalAlert> listRecent(int limit, boolean unreadOnly) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_FETCH_LIMIT));
        PageRequest page = PageRequest.of(0, safeLimit);
        if (unreadOnly) {
            return operationalAlertRepository.findByAcknowledgedFalseOrderByCreatedAtDesc(page).getContent();
        }
        return operationalAlertRepository.findAllByOrderByCreatedAtDesc(page).getContent();
    }

    /**
     * 미확인 알림 수를 반환한다.
     *
     * @return 미확인 알림 건수
     */
    @Transactional(readOnly = true)
    public long unreadCount() {
        return operationalAlertRepository.countByAcknowledgedFalse();
    }

    /**
     * 특정 알림을 확인 처리한다.
     *
     * @param id             알림 ID
     * @param acknowledgedBy 확인자 식별자
     * @return 처리된 알림 (ID가 없으면 empty)
     */
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

    /**
     * 모든 미확인 알림을 일괄 확인 처리한다.
     *
     * @param acknowledgedBy 확인자 식별자
     * @return 처리된 건수
     */
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
