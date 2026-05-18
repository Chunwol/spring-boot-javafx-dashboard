package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.LayoutBackup;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardLayoutService;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 레이아웃 백업/복원 유스케이스를 처리하는 액션 모듈.
 */
public class DashboardLayoutActionModule {

    private final DashboardLayoutService dashboardLayoutService;
    private final DashboardUIService dashboardUIService;
    private final Supplier<Long> dashboardIdSupplier;
    private final Supplier<Window> ownerWindowSupplier;
    private final Supplier<Object> layoutDataSupplier;
    private final Consumer<LayoutBackup> restoredLayoutConsumer;
    private final Supplier<Optional<String>> backupNameSupplier;

    public DashboardLayoutActionModule(
            DashboardLayoutService dashboardLayoutService,
            DashboardUIService dashboardUIService,
            Supplier<Long> dashboardIdSupplier,
            Supplier<Window> ownerWindowSupplier,
            Supplier<Object> layoutDataSupplier,
            Consumer<LayoutBackup> restoredLayoutConsumer
    ) {
        this(
                dashboardLayoutService,
                dashboardUIService,
                dashboardIdSupplier,
                ownerWindowSupplier,
                layoutDataSupplier,
                restoredLayoutConsumer,
                DashboardLayoutActionModule::defaultBackupNamePrompt
        );
    }

    public DashboardLayoutActionModule(
            DashboardLayoutService dashboardLayoutService,
            DashboardUIService dashboardUIService,
            Supplier<Long> dashboardIdSupplier,
            Supplier<Window> ownerWindowSupplier,
            Supplier<Object> layoutDataSupplier,
            Consumer<LayoutBackup> restoredLayoutConsumer,
            Supplier<Optional<String>> backupNameSupplier
    ) {
        this.dashboardLayoutService = dashboardLayoutService;
        this.dashboardUIService = dashboardUIService;
        this.dashboardIdSupplier = dashboardIdSupplier;
        this.ownerWindowSupplier = ownerWindowSupplier;
        this.layoutDataSupplier = layoutDataSupplier;
        this.restoredLayoutConsumer = restoredLayoutConsumer;
        this.backupNameSupplier = backupNameSupplier;
    }

    public void backupCurrentLayout() {
        Long dashboardId = dashboardIdSupplier == null ? null : dashboardIdSupplier.get();
        if (dashboardId == null) {
            showToast("대시보드를 선택해주세요.", true);
            return;
        }

        Optional<String> backupNameOpt = backupNameSupplier == null ? Optional.empty() : backupNameSupplier.get();
        if (backupNameOpt == null || backupNameOpt.isEmpty()) {
            return;
        }

        String name = backupNameOpt.get();
        if (name == null || name.trim().isEmpty()) {
            showToast("백업 이름을 입력해주세요.", true);
            return;
        }

        Object layoutData = layoutDataSupplier == null ? null : layoutDataSupplier.get();
        dashboardLayoutService.backupCurrentLayout(dashboardId, name.trim(), layoutData)
                .thenAccept(path -> Platform.runLater(() -> {
                    String fileName = path == null ? "unknown" : new File(path).getName();
                    showToast("백업 완료: " + fileName, false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> showToast(
                            "백업 실패: " + ApiClient.userFacingErrorMessage(ex, "요청을 처리하지 못했습니다."),
                            true));
                    return null;
                });
    }

    public void restoreLayout() {
        Long dashboardId = dashboardIdSupplier == null ? null : dashboardIdSupplier.get();
        if (dashboardId == null) {
            showToast("대시보드를 선택해주세요.", true);
            return;
        }

        Window owner = ownerWindowSupplier == null ? null : ownerWindowSupplier.get();
        dashboardLayoutService.restoreLayoutFromFile(owner, dashboardId)
                .thenAccept(backup -> {
                    if (backup == null) {
                        return;
                    }
                    Platform.runLater(() -> {
                        if (restoredLayoutConsumer != null) {
                            restoredLayoutConsumer.accept(backup);
                        }
                        showToast("레이아웃이 복원되었습니다.", false);
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> showToast(
                            "복원 실패: " + ApiClient.userFacingErrorMessage(ex, "요청을 처리하지 못했습니다."),
                            true));
                    return null;
                });
    }

    private void showToast(String message, boolean error) {
        if (dashboardUIService != null) {
            dashboardUIService.showToast(message, error);
        }
    }

    private static Optional<String> defaultBackupNamePrompt() {
        TextInputDialog dialog = new TextInputDialog("백업_" + System.currentTimeMillis());
        dialog.setTitle("백업");
        dialog.setHeaderText("레이아웃 백업 이름 입력");
        dialog.setContentText("백업이름:");
        return dialog.showAndWait();
    }
}
