package com.example.frontend.controller.dashboard;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Dashboard drag/resize interaction helper.
 */
public class DashboardInteractionService {

    private static final double EXPAND_PADDING = 200.0;

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
            double newX = snapper.applyAsDouble(parentPoint.getX() - dragStart[0]);
            double newY = snapper.applyAsDouble(parentPoint.getY() - dragStart[1]);
            // 음수 좌표 방지: 패널이 캔버스 밖으로 벗어나지 않도록
            newX = Math.max(0, newX);
            newY = Math.max(0, newY);
            panel.setLayoutX(newX);
            panel.setLayoutY(newY);
            // 드래그 중 패널이 컨테이너 경계를 넘으면 실시간 확장
            expandParentIfNeeded(panel);
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

    /**
     * 패널이 부모 Pane의 현재 크기를 넘어서면 부모를 확장한다.
     */
    private void expandParentIfNeeded(Region panel) {
        if (!(panel.getParent() instanceof Pane parent)) {
            return;
        }
        double neededW = panel.getLayoutX() + panel.prefWidth(-1) + EXPAND_PADDING;
        double neededH = panel.getLayoutY() + panel.prefHeight(-1) + EXPAND_PADDING;
        if (neededW > parent.getPrefWidth()) {
            parent.setPrefWidth(neededW);
        }
        if (neededH > parent.getPrefHeight()) {
            parent.setPrefHeight(neededH);
        }
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
        attachResizeHandlers(panel, handle, minWidth, minHeight, canInteract, snapper, null, onReleased);
    }

    public void attachResizeHandlers(
            Region panel,
            Node handle,
            double minWidth,
            double minHeight,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapper,
            BiConsumer<Double, Double> onDragged,
            Runnable onReleased
    ) {
        handle.setOnMouseDragged(event -> {
            if (!canInteract.getAsBoolean()) {
                return;
            }
            Point2D local = panel.sceneToLocal(event.getSceneX(), event.getSceneY());
            double newW = snapper.applyAsDouble(Math.max(minWidth, local.getX()));
            double newH = snapper.applyAsDouble(Math.max(minHeight, local.getY()));
            panel.setPrefWidth(newW);
            panel.setPrefHeight(newH);
            expandParentIfNeeded(panel);
            if (onDragged != null) {
                onDragged.accept(newW, newH);
            }
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