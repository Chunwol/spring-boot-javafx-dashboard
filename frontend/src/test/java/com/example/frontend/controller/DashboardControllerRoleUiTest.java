package com.example.frontend.controller;

import com.example.frontend.service.ApiClient;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
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

        assertThat(subtitle.getText()).contains("조회 전용");
        assertThat(topBadge.isVisible()).isTrue();
        assertThat(topBadge.isManaged()).isTrue();
        assertThat(statusRole.getText()).contains("읽기 전용");
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
        assertThat(statusRole.getText()).contains("사용자");
        assertThat(statusRole.getStyleClass()).doesNotContain("statusbar-role-readonly");
    }

    @Test
    void configureDashboardSettingsMenu_shouldKeepSettingsTextVisible() throws Exception {
        DashboardController controller = new DashboardController();
        MenuButton settingsMenu = new MenuButton();

        runOnFxAndWait(() -> {
            ApiClient.getInstance().setCurrentUserRole("ADMIN");
            invokeConfigureDashboardSettingsMenu(controller, settingsMenu);
        });

        assertThat(settingsMenu.getText()).isEmpty();
        assertThat(settingsMenu.getContentDisplay()).isEqualTo(ContentDisplay.GRAPHIC_ONLY);
        assertThat(settingsMenu.getGraphic()).isInstanceOf(Label.class);
        Label settingsLabel = (Label) settingsMenu.getGraphic();
        assertThat(settingsLabel.getText()).isEqualTo("설정");
        assertThat(settingsLabel.getStyleClass()).contains("dashboard-menu-button-text");
        assertThat(settingsMenu.isVisible()).isTrue();
        assertThat(settingsMenu.isManaged()).isTrue();
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

    private static void invokeConfigureDashboardSettingsMenu(DashboardController controller, MenuButton menuButton) {
        try {
            Method method = DashboardController.class.getDeclaredMethod("configureDashboardSettingsMenu", MenuButton.class);
            method.setAccessible(true);
            method.invoke(controller, menuButton);
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
