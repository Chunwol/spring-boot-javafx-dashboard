package com.example.frontend.controller.dashboard;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/**
 * UI Service for DashboardController.
 * Handles visual updates, animations, and coordinate transforms.
 * Separated from ViewModel to keep state management clean.
 */
public class DashboardUIService {

    private final Pane toastContainer;
    private final Pane classroomContainer;
    private final ScrollPane dashboardScrollPane;
    private final DashboardCanvasModule canvasModule;
    private final HBox editOnlyToolbar;
    private final VBox leftEditControls;

    public DashboardUIService(
            Pane toastContainer,
            Pane classroomContainer,
            ScrollPane dashboardScrollPane,
            DashboardCanvasModule canvasModule,
            HBox editOnlyToolbar,
            VBox leftEditControls
    ) {
        this.toastContainer = toastContainer;
        this.classroomContainer = classroomContainer;
        this.dashboardScrollPane = dashboardScrollPane;
        this.canvasModule = canvasModule;
        this.editOnlyToolbar = editOnlyToolbar;
        this.leftEditControls = leftEditControls;
    }

    /**
     * Show a toast notification.
     */
    public void showToast(String message, boolean isError) {
        if (toastContainer == null) {
            return;
        }
        Label toast = new Label(message);
        toast.setStyle("-fx-background-color: " + (isError ? "#b91c1c" : "#0f766e")
                + "; -fx-text-fill: white; -fx-padding: 8 12 8 12; -fx-background-radius: 8; -fx-font-weight: 600;");
        toastContainer.getChildren().add(0, toast);

        PauseTransition delay = new PauseTransition(Duration.seconds(2.2));
        delay.setOnFinished(e -> toastContainer.getChildren().remove(toast));
        delay.play();
    }

    /**
     * Apply zoom scale to the classroom container.
     */
    public void applyZoom(double scale) {
        if (classroomContainer == null) {
            return;
        }
        classroomContainer.setScaleX(scale);
        classroomContainer.setScaleY(scale);
    }

    /**
     * Fit all panels to viewport by calculating optimal zoom and scroll position.
     */
    public void fitAllPanelsToViewport(List<Node> nodes) {
        if (dashboardScrollPane == null || classroomContainer == null) {
            return;
        }

        if (nodes == null || nodes.isEmpty()) {
            applyZoom(1.0);
            showToast("No panels to fit", true);
            return;
        }

        // Defer if viewport not ready
        if (dashboardScrollPane.getViewportBounds().getWidth() <= 1 || dashboardScrollPane.getViewportBounds().getHeight() <= 1) {
            Platform.runLater(() -> fitAllPanelsToViewport(nodes));
            return;
        }

        // Calculate content bounds
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (Node node : nodes) {
            double x = node.getLayoutX();
            double y = node.getLayoutY();
            double w = Math.max(1, node.prefWidth(-1));
            double h = Math.max(1, node.prefHeight(-1));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        double contentW = Math.max(1, (maxX - minX) + 80);
        double contentH = Math.max(1, (maxY - minY) + 80);
        double viewportW = Math.max(1, dashboardScrollPane.getViewportBounds().getWidth());
        double viewportH = Math.max(1, dashboardScrollPane.getViewportBounds().getHeight());

        // Calculate optimal scale
        double targetScale = Math.min(viewportW / contentW, viewportH / contentH);
        double clampedScale = Math.max(0.4, Math.min(2.2, targetScale));
        applyZoom(clampedScale);

        // Calculate scroll positions
        double scaledW = Math.max(1, classroomContainer.getPrefWidth() * clampedScale);
        double scaledH = Math.max(1, classroomContainer.getPrefHeight() * clampedScale);
        double centerX = ((minX + maxX) / 2.0) * clampedScale;
        double centerY = ((minY + maxY) / 2.0) * clampedScale;

        double hRange = Math.max(1, scaledW - viewportW);
        double vRange = Math.max(1, scaledH - viewportH);

        dashboardScrollPane.setHvalue(Math.max(0, Math.min(1, (centerX - viewportW / 2.0) / hRange)));
        dashboardScrollPane.setVvalue(Math.max(0, Math.min(1, (centerY - viewportH / 2.0) / vRange)));

        showToast("Fit-all applied", false);
    }

    /**
     * Update UI state based on edit mode toggle.
     */
    public void applyEditModeUiState(boolean isEditMode) {
        if (canvasModule != null) {
            canvasModule.applyEditMode(isEditMode);
        }
        if (editOnlyToolbar != null) {
            editOnlyToolbar.setManaged(isEditMode);
            editOnlyToolbar.setVisible(isEditMode);
        }
        if (leftEditControls != null) {
            leftEditControls.setManaged(isEditMode);
            leftEditControls.setVisible(isEditMode);
        }
    }

    /**
     * Toggle visibility of UI elements.
     */
    public void setUIComponentVisibility(Node node, boolean visible) {
        if (node != null) {
            node.setManaged(visible);
            node.setVisible(visible);
        }
    }

    /**
     * Convert scene coordinates to local coordinates in parent.
     */
    public Point2D sceneToParentLocal(Node parent, double sceneX, double sceneY) {
        if (parent == null) {
            return new Point2D(sceneX, sceneY);
        }
        return parent.sceneToLocal(sceneX, sceneY);
    }

    /**
     * Convert local coordinates to scene coordinates.
     */
    public Point2D localToScene(Node node, double localX, double localY) {
        if (node == null) {
            return new Point2D(localX, localY);
        }
        return node.localToScene(localX, localY);
    }

    /**
     * Get responsive default size based on viewport width.
     */
    public double[] getResponsiveDefaultSize(String type, double sceneWidth) {
        boolean large = sceneWidth >= 1800;
        boolean medium = sceneWidth >= 1400;

        if ("NETWORK".equals(type)) {
            return large ? new double[]{720, 360}
                    : medium ? new double[]{620, 320}
                    : new double[]{520, 280};
        }
        return large ? new double[]{440, 320}
                : medium ? new double[]{380, 300}
                : new double[]{320, 260};
    }
}
