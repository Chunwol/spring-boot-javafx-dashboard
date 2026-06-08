package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 운영 경보의 파싱/표시/집계 관련 공통 로직.
 */
public final class DashboardAlertSupport {

    private static final DateTimeFormatter ALERT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private DashboardAlertSupport() {
    }

    public static DashboardAlertItem parseAlertPayload(String alertPayload, ObjectMapper objectMapper, Logger log) {
        if (alertPayload == null || alertPayload.isBlank()) {
            return null;
        }

        String raw = alertPayload.trim();
        if (raw.startsWith("{")) {
            try {
                return objectMapper.readValue(raw, DashboardAlertItem.class);
            } catch (Exception e) {
                log.debug("경보 JSON 파싱 실패, plain-text로 처리: {}", e.getMessage());
            }
        }

        DashboardAlertItem fallback = new DashboardAlertItem();
        fallback.setMessage(raw);
        fallback.setSeverity("WARNING");
        fallback.setAlertType("SYSTEM");
        fallback.setSourceMac("SYSTEM");
        fallback.setCreatedAt(LocalDateTime.now());
        fallback.setAcknowledged(false);
        return fallback;
    }

    public static boolean upsertAlert(List<DashboardAlertItem> alertItems, DashboardAlertItem incoming, int maxItems) {
        if (alertItems == null || incoming == null) {
            return false;
        }

        Long incomingId = incoming.getId();
        if (incomingId != null) {
            for (int i = 0; i < alertItems.size(); i++) {
                DashboardAlertItem existing = alertItems.get(i);
                if (existing != null && Objects.equals(existing.getId(), incomingId)) {
                    boolean wasUnread = !existing.isAcknowledged();
                    alertItems.remove(i);
                    alertItems.add(0, incoming);
                    return !incoming.isAcknowledged() && !wasUnread;
                }
            }
        }

        alertItems.add(0, incoming);
        int safeMaxItems = Math.max(1, maxItems);
        while (alertItems.size() > safeMaxItems) {
            alertItems.remove(alertItems.size() - 1);
        }
        return !incoming.isAcknowledged();
    }

    public static String formatBadgeCount(long unreadCount) {
        long count = Math.max(0, unreadCount);
        if (count <= 0) {
            return null;
        }
        return count > 99 ? "99+" : String.valueOf(count);
    }

    public static String formatAlertTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "-";
        }
        return dateTime.format(ALERT_TIME_FORMAT);
    }

    public static String severityDisplayText(String severity) {
        if (severity == null || severity.isBlank()) {
            return "WARNING";
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INFO" -> "INFO";
            case "CRITICAL" -> "CRITICAL";
            default -> "WARNING";
        };
    }

    public static String severityStyleClass(String severity) {
        String normalized = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INFO" -> "alerts-severity-info";
            case "CRITICAL" -> "alerts-severity-critical";
            default -> "alerts-severity-warning";
        };
    }

    public static Set<String> collectBlinkingOfflineAlertMacs(
            List<DashboardAlertItem> alertItems,
            long blinkWindowSeconds,
            LocalDateTime now
    ) {
        Set<String> targetMacs = new HashSet<>();
        if (alertItems == null || alertItems.isEmpty()) {
            return targetMacs;
        }

        LocalDateTime cutoff = now.minusSeconds(Math.max(0L, blinkWindowSeconds));
        for (DashboardAlertItem item : alertItems) {
            if (item == null || item.isAcknowledged() || !isOfflineAlert(item)) {
                continue;
            }
            LocalDateTime createdAt = item.getCreatedAt();
            if (createdAt != null && createdAt.isBefore(cutoff)) {
                continue;
            }
            String normalizedMac = normalizeAlertSourceMac(item.getSourceMac());
            if (normalizedMac != null) {
                targetMacs.add(normalizedMac);
            }
        }
        return targetMacs;
    }

    public static Set<String> collectBlinkingUpdateAlertMacs(List<DashboardAlertItem> alertItems) {
        Set<String> targetMacs = new HashSet<>();
        if (alertItems == null || alertItems.isEmpty()) {
            return targetMacs;
        }

        for (DashboardAlertItem item : alertItems) {
            if (item == null || item.isAcknowledged() || !isUpdateRebootAlert(item)) {
                continue;
            }
            String normalizedMac = normalizeAlertSourceMac(item.getSourceMac());
            if (normalizedMac != null) {
                targetMacs.add(normalizedMac);
            }
        }
        return targetMacs;
    }

    private static boolean isOfflineAlert(DashboardAlertItem item) {
        String type = item.getAlertType();
        if (type != null && type.toUpperCase(Locale.ROOT).contains("OFFLINE")) {
            return true;
        }

        String message = item.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        return normalizedMessage.contains("오프라인") || normalizedMessage.contains("offline");
    }

    private static boolean isUpdateRebootAlert(DashboardAlertItem item) {
        String type = item.getAlertType();
        if (type != null && "UPDATE_REBOOT_REQUIRED".equalsIgnoreCase(type.trim())) {
            return true;
        }

        String message = item.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("업데이트 확인 필요")
                || (normalized.contains("update") && normalized.contains("reboot"));
    }

    private static String normalizeAlertSourceMac(String sourceMac) {
        if (sourceMac == null) {
            return null;
        }
        String normalized = sourceMac.trim();
        if (normalized.isBlank() || "SYSTEM".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
