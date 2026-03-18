package com.example.frontend.controller.dashboard;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Region;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Dashboard drag/resize interaction helper.
 */
public class DashboardInteractionService {

    public void attachDragHandlers(
            Region panel,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapper,
            Runnable onReleased
    ) {
        final double[] dragStart = new double[2];

        panel.setOnMousePressed(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            dragStart[0] = parentPoint.getX() - panel.getLayoutX();
            dragStart[1] = parentPoint.getY() - panel.getLayoutY();
            panel.toFront();
            event.consume();
        });

        panel.setOnMouseDragged(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            panel.setLayoutX(snapper.applyAsDouble(parentPoint.getX() - dragStart[0]));
            panel.setLayoutY(snapper.applyAsDouble(parentPoint.getY() - dragStart[1]));
            event.consume();
        });

        panel.setOnMouseReleased(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            if (onReleased != null) {
                onReleased.run();
            }
            event.consume();
        });
    }

    public void attachResizeHandlers(
            Region panel,
            Node handle,
            double minWidth,
            double minHeight,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapper,
            Runnable onReleased
    ) {
        handle.setOnMouseDragged(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            Point2D local = panel.sceneToLocal(event.getSceneX(), event.getSceneY());
            panel.setPrefWidth(snapper.applyAsDouble(Math.max(minWidth, local.getX())));
            panel.setPrefHeight(snapper.applyAsDouble(Math.max(minHeight, local.getY())));
            event.consume();
        });

        handle.setOnMouseReleased(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            if (onReleased != null) {
                onReleased.run();
            }
            event.consume();
        });
    }
}