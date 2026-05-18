package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.LayoutBackup;
import com.example.frontend.service.DashboardLayoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutActionModuleTest {

    @BeforeAll
    static void initToolkit() {
        new JFXPanel();
    }

    @Test
    void backupCurrentLayout_whenDashboardMissing_shouldShowErrorAndSkipService() {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        CapturingDashboardUIService ui = new CapturingDashboardUIService();

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> null,
                () -> null,
                () -> Map.of("zoom", 1.0),
                backup -> { },
                () -> Optional.of("테스트백업")
        );

        module.backupCurrentLayout();

        assertThat(service.backupCalled).isFalse();
        assertThat(ui.messages).contains("대시보드를 선택해주세요.");
        assertThat(ui.errorFlags).contains(true);
    }

    @Test
    void backupCurrentLayout_whenNameBlank_shouldShowErrorAndSkipService() {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        CapturingDashboardUIService ui = new CapturingDashboardUIService();

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> 10L,
                () -> null,
                () -> Map.of("zoom", 1.0),
                backup -> { },
                () -> Optional.of("   ")
        );

        module.backupCurrentLayout();

        assertThat(service.backupCalled).isFalse();
        assertThat(ui.messages).contains("백업 이름을 입력해주세요.");
    }

    @Test
    void backupCurrentLayout_whenValid_shouldCallServiceAndShowSuccessToast() throws InterruptedException {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        service.backupResult = CompletableFuture.completedFuture("C:/tmp/layout_backup.json");
        CapturingDashboardUIService ui = new CapturingDashboardUIService();

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> 11L,
                () -> null,
                () -> Map.of("zoom", 1.25),
                backup -> { },
                () -> Optional.of("운영백업")
        );

        module.backupCurrentLayout();
        awaitFx();

        assertThat(service.backupCalled).isTrue();
        assertThat(service.backupDashboardId).isEqualTo(11L);
        assertThat(service.backupName).isEqualTo("운영백업");
        assertThat(service.backupLayoutData).isEqualTo(Map.of("zoom", 1.25));
        assertThat(ui.messages.stream().anyMatch(msg -> msg.startsWith("백업 완료:"))).isTrue();
    }

    @Test
    void restoreLayout_whenDashboardMissing_shouldShowErrorAndSkipService() {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        CapturingDashboardUIService ui = new CapturingDashboardUIService();

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> null,
                () -> null,
                () -> Map.of(),
                backup -> { },
                () -> Optional.of("x")
        );

        module.restoreLayout();

        assertThat(service.restoreCalled).isFalse();
        assertThat(ui.messages).contains("대시보드를 선택해주세요.");
    }

    @Test
    void restoreLayout_whenBackupExists_shouldApplyBackupAndShowSuccessToast() throws InterruptedException {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        LayoutBackup backup = new LayoutBackup();
        backup.setDashboardId(22L);
        backup.setName("복원본");
        backup.setTimestamp(LocalDateTime.now());
        backup.setLayoutData(Map.of("zoom", 1.1));
        service.restoreResult = CompletableFuture.completedFuture(backup);

        CapturingDashboardUIService ui = new CapturingDashboardUIService();
        AtomicReference<LayoutBackup> restored = new AtomicReference<>();
        Supplier<Window> ownerSupplier = () -> null;

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> 22L,
                ownerSupplier,
                () -> Map.of(),
                restored::set,
                () -> Optional.of("x")
        );

        module.restoreLayout();
        awaitFx();

        assertThat(service.restoreCalled).isTrue();
        assertThat(service.restoreDashboardId).isEqualTo(22L);
        assertThat(restored.get()).isEqualTo(backup);
        assertThat(ui.messages).contains("레이아웃이 복원되었습니다.");
    }

    @Test
    void restoreLayout_whenServiceFails_shouldShowErrorToast() throws InterruptedException {
        FakeDashboardLayoutService service = new FakeDashboardLayoutService();
        CompletableFuture<LayoutBackup> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("복원 폭발"));
        service.restoreResult = failed;

        CapturingDashboardUIService ui = new CapturingDashboardUIService();

        DashboardLayoutActionModule module = new DashboardLayoutActionModule(
                service,
                ui,
                () -> 35L,
                () -> null,
                () -> Map.of(),
                backup -> { },
                () -> Optional.of("x")
        );

        module.restoreLayout();
        awaitFx();

        assertThat(ui.messages.stream().anyMatch(msg -> msg.startsWith("복원 실패:"))).isTrue();
        assertThat(ui.errorFlags).contains(true);
    }

    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }

    private static final class CapturingDashboardUIService extends DashboardUIService {

        private final List<String> messages = new ArrayList<>();
        private final List<Boolean> errorFlags = new ArrayList<>();

        private CapturingDashboardUIService() {
            super(new Pane(), new Pane(), new ScrollPane(), null, new HBox(), new VBox());
        }

        @Override
        public void showToast(String message, boolean isError) {
            messages.add(message);
            errorFlags.add(isError);
        }
    }

    private static final class FakeDashboardLayoutService extends DashboardLayoutService {

        private CompletableFuture<String> backupResult = CompletableFuture.completedFuture("C:/tmp/default_backup.json");
        private CompletableFuture<LayoutBackup> restoreResult = CompletableFuture.completedFuture(null);

        private boolean backupCalled;
        private Long backupDashboardId;
        private String backupName;
        private Object backupLayoutData;

        private boolean restoreCalled;
        private Window restoreOwner;
        private Long restoreDashboardId;

        private FakeDashboardLayoutService() {
            super(new ObjectMapper(), "http://localhost:8888/api/v1");
        }

        @Override
        public CompletableFuture<String> backupCurrentLayout(Long dashboardId, String name, Object layoutData) {
            backupCalled = true;
            backupDashboardId = dashboardId;
            backupName = name;
            backupLayoutData = layoutData;
            return backupResult;
        }

        @Override
        public CompletableFuture<LayoutBackup> restoreLayoutFromFile(Window owner, Long dashboardId) {
            restoreCalled = true;
            restoreOwner = owner;
            restoreDashboardId = dashboardId;
            return restoreResult;
        }
    }
}
