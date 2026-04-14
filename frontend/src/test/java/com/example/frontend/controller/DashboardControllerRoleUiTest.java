package com.example.frontend.controller;

import com.example.frontend.service.ApiClient;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardControllerRoleUiTest {

    @BeforeAll
    static void initToolkit() {
        new JFXPanel();
    }

    @Test
    void applyRoleUiState_shouldShowReadOnlyBadgesForViewer() throws Exception {
        DashboardController controller = new DashboardController();
        Label subtitle = new Label("실시간 모니터링");
        Label topBadge = new Label();
        Label statusRole = new Label();

        runOnFxAndWait(() -> {
            ApiClient.getInstance().setCurrentUserRole("VIEWER");
            setField(controller, "dashboardSubtitleLabel", subtitle);
            setField(controller, "viewerModeBadgeLabel", topBadge);
            setField(controller, "statusRoleLabel", statusRole);
            invokeApplyRoleUiState(controller);
        });

        assertThat(subtitle.getText()).contains("읽기 전용");
        assertThat(topBadge.isVisible()).isTrue();
        assertThat(topBadge.isManaged()).isTrue();
        assertThat(statusRole.getText()).contains("VIEWER");
        assertThat(statusRole.getStyleClass()).contains("statusbar-role-readonly");
    }

    @Test
    void applyRoleUiState_shouldHideReadOnlyBadgesForUser() throws Exception {
        DashboardController controller = new DashboardController();
        Label subtitle = new Label("실시간 모니터링");
        Label topBadge = new Label();
        topBadge.setVisible(true);
        topBadge.setManaged(true);
        Label statusRole = new Label();
        statusRole.getStyleClass().add("statusbar-role-readonly");

        runOnFxAndWait(() -> {
            ApiClient.getInstance().setCurrentUserRole("USER");
            setField(controller, "dashboardSubtitleLabel", subtitle);
            setField(controller, "viewerModeBadgeLabel", topBadge);
            setField(controller, "statusRoleLabel", statusRole);
            invokeApplyRoleUiState(controller);
        });

        assertThat(subtitle.getText()).doesNotContain("읽기 전용");
        assertThat(topBadge.isVisible()).isFalse();
        assertThat(topBadge.isManaged()).isFalse();
        assertThat(statusRole.getText()).contains("USER");
        assertThat(statusRole.getStyleClass()).doesNotContain("statusbar-role-readonly");
    }

    private static void invokeApplyRoleUiState(DashboardController controller) {
        try {
            Method method = DashboardController.class.getDeclaredMethod("applyRoleUiState");
            method.setAccessible(true);
            method.invoke(controller);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void runOnFxAndWait(Runnable runnable) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
