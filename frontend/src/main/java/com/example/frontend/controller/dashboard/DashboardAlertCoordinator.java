package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영 경보 목록/미확인 수를 동기화하는 상태 코디네이터.
 */
public class DashboardAlertCoordinator {

    private final List<DashboardAlertItem> alertItems = new ArrayList<>();
    private long unreadAlertCount = 0L;

    public List<DashboardAlertItem> getAlertItems() {
        return alertItems;
    }

    public long getUnreadAlertCount() {
        return unreadAlertCount;
    }

    public void replaceAlertSnapshot(List<DashboardAlertItem> items) {
        alertItems.clear();
        if (items != null) {
            alertItems.addAll(items);
        }
    }

    public void setUnreadAlertCount(Long count) {
        unreadAlertCount = count == null ? 0L : Math.max(0L, count);
    }

    public boolean applyRealtimeAlert(DashboardAlertItem item, int fetchLimit) {
        boolean addedUnread = DashboardAlertSupport.upsertAlert(alertItems, item, fetchLimit);
        if (addedUnread) {
            unreadAlertCount++;
        }
        return addedUnread;
    }

    public boolean acknowledgeAlert(DashboardAlertItem item, LocalDateTime acknowledgedAt) {
        if (item == null || item.getId() == null || item.isAcknowledged()) {
            return false;
        }
        LocalDateTime now = acknowledgedAt == null ? LocalDateTime.now() : acknowledgedAt;
        item.setAcknowledged(true);
        item.setAcknowledgedAt(now);
        if (unreadAlertCount > 0) {
            unreadAlertCount--;
        }
        return true;
    }

    public int acknowledgeAll(LocalDateTime acknowledgedAt) {
        LocalDateTime now = acknowledgedAt == null ? LocalDateTime.now() : acknowledgedAt;
        int updated = 0;
        for (DashboardAlertItem item : alertItems) {
            if (item != null && !item.isAcknowledged()) {
                item.setAcknowledged(true);
                item.setAcknowledgedAt(now);
                updated++;
            }
        }
        unreadAlertCount = 0L;
        return updated;
    }

    public String formatBadgeText() {
        return DashboardAlertSupport.formatBadgeCount(unreadAlertCount);
    }
}
