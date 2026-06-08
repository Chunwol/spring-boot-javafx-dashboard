package com.example.frontend.controller.dashboard;

import com.example.frontend.service.ApiClient;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class DashboardSessionActionModuleTest {

    @BeforeAll
    static void initToolkit() {
        FxTestHelper.initToolkit();
    }

    @Test
    void moveToLoginScreen_whenWindowIsNull_shouldClearTokenAndReturnCleanly() throws Exception {
        TrackingApiClient apiClient = new TrackingApiClient();
        AtomicBoolean shutdownCalled = new AtomicBoolean(false);
        List<String> toastMessages = new ArrayList<>();

        DashboardSessionActionModule module = new DashboardSessionActionModule(
                apiClient,
                () -> shutdownCalled.set(true),
                (message, error) -> toastMessages.add(message),
                DashboardSessionActionModule.class
        );

        // null window → FXML found, but Stage cast fails → silent return
        // Token must be cleared regardless of early exit path
        module.moveToLoginScreen(null, null, false);

        assertThat(apiClient.lastBearerToken).isNull();
        assertThat(shutdownCalled.get()).isFalse();
        assertThat(toastMessages).isEmpty();
    }

    @Test
    void moveToLoginScreen_whenShutdownRequested_shouldRunShutdownBeforeReturn() throws Exception {
        TrackingApiClient apiClient = new TrackingApiClient();
        AtomicBoolean shutdownCalled = new AtomicBoolean(false);

        DashboardSessionActionModule module = new DashboardSessionActionModule(
                apiClient,
                () -> shutdownCalled.set(true),
                (message, error) -> { },
                DashboardSessionActionModule.class
        );

        module.moveToLoginScreen(null, "서버 연결 종료", true);

        assertThat(apiClient.lastBearerToken).isNull();
        assertThat(shutdownCalled.get()).isTrue();
    }

    @Test
    void moveToLoginScreen_whenResourcesExist_shouldSwitchStageScene() throws InterruptedException {
        URL loginResource = DashboardSessionActionModule.class.getResource("/fxml/login.fxml");
        Assumptions.assumeTrue(loginResource != null, "login.fxml not available on test classpath");

        TrackingApiClient apiClient = new TrackingApiClient();
        AtomicBoolean shutdownCalled = new AtomicBoolean(false);
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        DashboardSessionActionModule module = new DashboardSessionActionModule(
                apiClient,
                () -> shutdownCalled.set(true),
                (message, error) -> { },
                DashboardSessionActionModule.class
        );

        runOnFxAndWait(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new Pane(), 240, 160));
            stageRef.set(stage);
            try {
                module.moveToLoginScreen(stage, null, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Stage stage = stageRef.get();
        assertThat(stage).isNotNull();
        assertThat(stage.getScene()).isNotNull();
        assertThat(stage.getTitle()).isEqualTo("스마트 강의실 대시보드 - 로그인");
        assertThat(apiClient.lastBearerToken).isNull();
        assertThat(shutdownCalled.get()).isFalse();

        runOnFxAndWait(stage::close);
    }

    private static void runOnFxAndWait(Runnable runnable) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                failureRef.set(t);
            } finally {
                latch.countDown();
            }
        });
        boolean completed = latch.await(15, TimeUnit.SECONDS);
        assertThat(completed).withFailMessage("FX thread did not finish within 15 s").isTrue();
        if (failureRef.get() != null) {
            fail("FX thread execution failed", failureRef.get());
        }
    }

    private static final class TrackingApiClient extends ApiClient {
        private String lastBearerToken = "initial";

        private TrackingApiClient() {
            super("http://localhost:8888/api/v1");
        }

        @Override
        public void setBearerToken(String bearerToken) {
            this.lastBearerToken = bearerToken;
            super.setBearerToken(bearerToken);
        }
    }

}
