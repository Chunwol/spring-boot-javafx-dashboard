package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAlertCoordinatorTest {

    @Test
    void replaceSnapshot_andSetUnread_shouldUpdateInternalState() {
        DashboardAlertCoordinator coordinator = new DashboardAlertCoordinator();
        DashboardAlertItem one = alert(1L, false);
        DashboardAlertItem two = alert(2L, true);

        coordinator.replaceAlertSnapshot(List.of(one, two));
        coordinator.setUnreadAlertCount(5L);

        assertThat(coordinator.getAlertItems()).hasSize(2);
        assertThat(coordinator.getUnreadAlertCount()).isEqualTo(5L);
    }

    @Test
    void applyRealtimeAlert_shouldAddAlertAndIncreaseUnread() {
        DashboardAlertCoordinator coordinator = new DashboardAlertCoordinator();
        DashboardAlertItem incoming = alert(10L, false);

        boolean addedUnread = coordinator.applyRealtimeAlert(incoming, 200);

        assertThat(addedUnread).isTrue();
        assertThat(coordinator.getAlertItems()).hasSize(1);
        assertThat(coordinator.getUnreadAlertCount()).isEqualTo(1L);
    }

    @Test
    void acknowledgeAlert_shouldMarkAcknowledgedAndDecreaseUnread() {
        DashboardAlertCoordinator coordinator = new DashboardAlertCoordinator();
        DashboardAlertItem item = alert(11L, false);
        coordinator.replaceAlertSnapshot(List.of(item));
        coordinator.setUnreadAlertCount(3L);

        LocalDateTime now = LocalDateTime.of(2026, 4, 17, 13, 0);
        boolean changed = coordinator.acknowledgeAlert(item, now);

        assertThat(changed).isTrue();
        assertThat(item.isAcknowledged()).isTrue();
        assertThat(item.getAcknowledgedAt()).isEqualTo(now);
        assertThat(coordinator.getUnreadAlertCount()).isEqualTo(2L);
    }

    @Test
    void acknowledgeAll_shouldAcknowledgeAllUnreadAndResetUnreadCount() {
        DashboardAlertCoordinator coordinator = new DashboardAlertCoordinator();
        DashboardAlertItem unreadA = alert(21L, false);
        DashboardAlertItem unreadB = alert(22L, false);
        DashboardAlertItem acked = alert(23L, true);
        coordinator.replaceAlertSnapshot(List.of(unreadA, unreadB, acked));
        coordinator.setUnreadAlertCount(9L);

        LocalDateTime now = LocalDateTime.of(2026, 4, 17, 14, 30);
        int updated = coordinator.acknowledgeAll(now);

        assertThat(updated).isEqualTo(2);
        assertThat(unreadA.isAcknowledged()).isTrue();
        assertThat(unreadB.isAcknowledged()).isTrue();
        assertThat(unreadA.getAcknowledgedAt()).isEqualTo(now);
        assertThat(unreadB.getAcknowledgedAt()).isEqualTo(now);
        assertThat(coordinator.getUnreadAlertCount()).isEqualTo(0L);
    }

    @Test
    void formatBadgeText_shouldReturnExpectedBadgeLabel() {
        DashboardAlertCoordinator coordinator = new DashboardAlertCoordinator();

        coordinator.setUnreadAlertCount(0L);
        assertThat(coordinator.formatBadgeText()).isNull();

        coordinator.setUnreadAlertCount(16L);
        assertThat(coordinator.formatBadgeText()).isEqualTo("16");

        coordinator.setUnreadAlertCount(132L);
        assertThat(coordinator.formatBadgeText()).isEqualTo("99+");
    }

    private static DashboardAlertItem alert(Long id, boolean acknowledged) {
        DashboardAlertItem item = new DashboardAlertItem();
        item.setId(id);
        item.setMessage("테스트 경보");
        item.setSeverity("WARNING");
        item.setAlertType("SYSTEM");
        item.setSourceMac("AA-BB-CC");
        item.setCreatedAt(LocalDateTime.of(2026, 4, 17, 9, 0));
        item.setAcknowledged(acknowledged);
        return item;
    }
}
