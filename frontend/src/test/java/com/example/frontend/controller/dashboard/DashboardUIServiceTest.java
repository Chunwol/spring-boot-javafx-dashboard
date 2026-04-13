package com.example.frontend.controller.dashboard;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardUIServiceTest {

    @BeforeAll
    static void initToolkit() {
        new JFXPanel();
    }

    @Test
    void applyZoom_shouldUpdateContainerScale() throws InterruptedException {
        Pane toast = new Pane();
        Pane classroom = new Pane();
        DashboardUIService service = new DashboardUIService(toast, classroom, new ScrollPane(), null, new HBox(), new VBox());

        runOnFxAndWait(() -> service.applyZoom(1.35));

        assertThat(classroom.getTransforms()).hasSize(1);
        Scale scale = (Scale) classroom.getTransforms().get(0);
        assertThat(scale.getX()).isEqualTo(1.35);
        assertThat(scale.getY()).isEqualTo(1.35);
    }

    @Test
    void applyEditModeUiState_shouldToggleEditContainers() throws InterruptedException {
        HBox editToolbar = new HBox();
        VBox leftEdit = new VBox();
        DashboardUIService service = new DashboardUIService(new Pane(), new Pane(), new ScrollPane(), null, editToolbar, leftEdit);

        runOnFxAndWait(() -> service.applyEditModeUiState(true));
        assertThat(editToolbar.isManaged()).isTrue();
        assertThat(editToolbar.isVisible()).isTrue();
        assertThat(leftEdit.isManaged()).isTrue();
        assertThat(leftEdit.isVisible()).isTrue();

        runOnFxAndWait(() -> service.applyEditModeUiState(false));
        assertThat(editToolbar.isManaged()).isFalse();
        assertThat(editToolbar.isVisible()).isFalse();
        assertThat(leftEdit.isManaged()).isFalse();
        assertThat(leftEdit.isVisible()).isFalse();
    }

    @Test
    void fitAllPanelsToViewport_whenNoNodes_shouldResetZoomAndShowToast() throws InterruptedException {
        Pane toast = new Pane();
        Pane classroom = new Pane();
        DashboardUIService service = new DashboardUIService(toast, classroom, new ScrollPane(), null, null, null);

        runOnFxAndWait(() -> service.fitAllPanelsToViewport(List.of()));

        assertThat(classroom.getTransforms()).hasSize(1);
        Scale resetScale = (Scale) classroom.getTransforms().get(0);
        assertThat(resetScale.getX()).isEqualTo(1.0);
        assertThat(resetScale.getY()).isEqualTo(1.0);
        assertThat(toast.getChildren()).isNotEmpty();
        assertThat(((Label) toast.getChildren().get(0)).getText()).contains("맞출 패널이 없습니다");
    }

    @Test
    void getResponsiveDefaultSize_shouldReturnExpectedBuckets() {
        DashboardUIService service = new DashboardUIService(new Pane(), new Pane(), new ScrollPane(), null, null, null);

        double[] largeNetwork = service.getResponsiveDefaultSize("NETWORK", 1900);
        double[] mediumNetwork = service.getResponsiveDefaultSize("NETWORK", 1500);
        double[] smallNetwork = service.getResponsiveDefaultSize("NETWORK", 1000);

        assertThat(largeNetwork).containsExactly(720, 360);
        assertThat(mediumNetwork).containsExactly(620, 320);
        assertThat(smallNetwork).containsExactly(520, 280);
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
        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }
}
