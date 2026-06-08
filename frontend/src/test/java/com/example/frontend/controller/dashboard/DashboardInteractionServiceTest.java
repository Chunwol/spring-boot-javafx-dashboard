package com.example.frontend.controller.dashboard;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardInteractionServiceTest {

    @BeforeAll
    static void initToolkit() {
        FxTestHelper.initToolkit();
    }

    @Test
    void attachDragHandlers_shouldRegisterAllMouseHandlers() {
        DashboardInteractionService service = new DashboardInteractionService();
        Pane parent = new Pane();
        Pane panel = new Pane();
        parent.getChildren().add(panel);

        service.attachDragHandlers(panel, () -> false, v -> v, null);

        assertThat(panel.getOnMousePressed()).isNotNull();
        assertThat(panel.getOnMouseDragged()).isNotNull();
        assertThat(panel.getOnMouseReleased()).isNotNull();
    }

    @Test
    void attachResizeHandlers_shouldRunReleaseCallbackWhenInteractable() {
        DashboardInteractionService service = new DashboardInteractionService();
        Region panel = new Pane();
        Node handle = new Pane();
        AtomicBoolean released = new AtomicBoolean(false);

        service.attachResizeHandlers(
                panel,
                handle,
                100,
                100,
                () -> true,
                v -> v,
                () -> released.set(true)
        );

        MouseEvent releaseEvent = new MouseEvent(
                MouseEvent.MOUSE_RELEASED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null
        );

        handle.getOnMouseReleased().handle(releaseEvent);

        assertThat(released.get()).isTrue();
    }

    @Test
    void dragHandlers_shouldUpdateLayoutUsingSnapper() throws InterruptedException {
        DashboardInteractionService service = new DashboardInteractionService();
        AtomicReference<Pane> panelRef = new AtomicReference<>();

        runOnFxAndWait(() -> {
            Pane parent = new Pane();
            Pane panel = new Pane();
            panel.setLayoutX(5);
            panel.setLayoutY(10);
            parent.getChildren().add(panel);
            new Scene(parent, 400, 300);

            service.attachDragHandlers(panel, () -> true, value -> Math.round(value / 10.0) * 10.0, null);

            panel.getOnMousePressed().handle(mouseEvent(MouseEvent.MOUSE_PRESSED, 15, 25));
            panel.getOnMouseDragged().handle(mouseEvent(MouseEvent.MOUSE_DRAGGED, 44, 67));
            panelRef.set(panel);
        });

        Pane panel = panelRef.get();
        assertThat(panel.getLayoutX()).isEqualTo(30.0);
        assertThat(panel.getLayoutY()).isEqualTo(50.0);
    }

    @Test
    void resizeHandlers_shouldApplyMinimumsAndInvokeOnDragged() throws InterruptedException {
        DashboardInteractionService service = new DashboardInteractionService();
        AtomicReference<Region> panelRef = new AtomicReference<>();
        AtomicReference<Double> draggedWidth = new AtomicReference<>();
        AtomicReference<Double> draggedHeight = new AtomicReference<>();

        runOnFxAndWait(() -> {
            Pane root = new Pane();
            Pane panel = new Pane();
            Pane handle = new Pane();
            root.getChildren().addAll(panel, handle);
            new Scene(root, 400, 300);

            service.attachResizeHandlers(
                    panel,
                    handle,
                    100,
                    120,
                    () -> true,
                    value -> Math.round(value / 10.0) * 10.0,
                    (width, height) -> {
                        draggedWidth.set(width);
                        draggedHeight.set(height);
                    },
                    null
            );

            handle.getOnMouseDragged().handle(mouseEvent(MouseEvent.MOUSE_DRAGGED, 54, 79));
            panelRef.set(panel);
        });

        Region panel = panelRef.get();
        assertThat(panel.getPrefWidth()).isEqualTo(100.0);
        assertThat(panel.getPrefHeight()).isEqualTo(120.0);
        assertThat(draggedWidth.get()).isEqualTo(100.0);
        assertThat(draggedHeight.get()).isEqualTo(120.0);
    }

    private static MouseEvent mouseEvent(javafx.event.EventType<MouseEvent> type, double sceneX, double sceneY) {
        return new MouseEvent(
                type,
                sceneX,
                sceneY,
                sceneX,
                sceneY,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null
        );
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
