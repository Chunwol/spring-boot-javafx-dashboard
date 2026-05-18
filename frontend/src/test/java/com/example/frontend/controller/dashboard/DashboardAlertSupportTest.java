package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAlertSupportTest {

    @Test
    void parseAlertPayload_whenPlainText_shouldBuildFallbackAlert() {
        DashboardAlertItem item = DashboardAlertSupport.parseAlertPayload(
                "에이전트 연결이 끊겼습니다.",
                new ObjectMapper(),
                LoggerFactory.getLogger(DashboardAlertSupportTest.class)
        );

        assertThat(item).isNotNull();
        assertThat(item.getMessage()).isEqualTo("에이전트 연결이 끊겼습니다.");
        assertThat(item.getSeverity()).isEqualTo("WARNING");
        assertThat(item.getAlertType()).isEqualTo("SYSTEM");
        assertThat(item.isAcknowledged()).isFalse();
    }

    @Test
    void upsertAlert_shouldReplaceExistingAndTrackUnreadOnlyForNewItem() {
        List<DashboardAlertItem> alerts = new ArrayList<>();

        DashboardAlertItem existing = alert(1L, "기존", false);
        alerts.add(existing);

        DashboardAlertItem replaced = alert(1L, "대체", false);
        boolean addedUnreadOnReplace = DashboardAlertSupport.upsertAlert(alerts, replaced, 200);

        assertThat(addedUnreadOnReplace).isFalse();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMessage()).isEqualTo("대체");

        DashboardAlertItem newUnread = alert(2L, "신규", false);
        boolean addedUnreadOnNew = DashboardAlertSupport.upsertAlert(alerts, newUnread, 200);

        assertThat(addedUnreadOnNew).isTrue();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void collectBlinkingOfflineAlertMacs_shouldIncludeOnlyRecentUnackedOfflineAlerts() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 17, 10, 0);
        List<DashboardAlertItem> alerts = new ArrayList<>();

        DashboardAlertItem recentOffline = alert(11L, "오프라인 감지", false);
        recentOffline.setSourceMac("aa-bb-cc");
        recentOffline.setCreatedAt(now.minusSeconds(30));

        DashboardAlertItem oldOffline = alert(12L, "offline detected", false);
        oldOffline.setSourceMac("dd-ee-ff");
        oldOffline.setCreatedAt(now.minusMinutes(10));

        DashboardAlertItem ackedOffline = alert(13L, "오프라인", true);
        ackedOffline.setSourceMac("11-22-33");
        ackedOffline.setCreatedAt(now.minusSeconds(20));

        alerts.add(recentOffline);
        alerts.add(oldOffline);
        alerts.add(ackedOffline);

        Set<String> targets = DashboardAlertSupport.collectBlinkingOfflineAlertMacs(alerts, 120, now);

        assertThat(targets).containsExactly("AA-BB-CC");
    }

    @Test
    void formatBadgeCount_shouldReturnCappedText() {
        assertThat(DashboardAlertSupport.formatBadgeCount(0)).isNull();
        assertThat(DashboardAlertSupport.formatBadgeCount(12)).isEqualTo("12");
        assertThat(DashboardAlertSupport.formatBadgeCount(120)).isEqualTo("99+");
    }

    private static DashboardAlertItem alert(Long id, String message, boolean acknowledged) {
        DashboardAlertItem item = new DashboardAlertItem();
        item.setId(id);
        item.setMessage(message);
        item.setSeverity("WARNING");
        item.setAlertType("SYSTEM");
        item.setAcknowledged(acknowledged);
        item.setCreatedAt(LocalDateTime.now());
        return item;
    }
}
