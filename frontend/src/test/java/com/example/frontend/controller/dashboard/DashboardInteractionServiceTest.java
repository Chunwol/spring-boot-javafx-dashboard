package com.example.frontend.controller.dashboard;

import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardInteractionServiceTest {

    @BeforeAll
    static void initToolkit() {
        // Initialize JavaFX runtime once for event handler wiring.
        new JFXPanel();
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
}
