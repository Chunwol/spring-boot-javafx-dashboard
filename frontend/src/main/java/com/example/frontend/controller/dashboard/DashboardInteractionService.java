package com.example.frontend.controller.dashboard;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

/**
 * Dashboard drag/resize interaction helper.
 */
public class DashboardInteractionService {

    private static final double EXPAND_PADDING = 200.0;

    private DashboardSmartGuides smartGuides;

    public void setSmartGuides(DashboardSmartGuides smartGuides) {
        this.smartGuides = smartGuides;
    }

    public void attachDragHandlers(
            Region panel,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapper,
            Runnable onReleased
    ) {
        attachDragHandlers(panel, canInteract, snapper, null, onReleased);
    }

    public void attachDragHandlers(
            Region panel,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapper,
            Supplier<Set<Node>> selectedNodesSupplier,
            Runnable onReleased
    ) {
        final double[] dragStart = new double[2];
        final Map<Node, double[]> initialPositions = new HashMap<>();

        panel.setOnMousePressed(event -> {
            if (!canInteract.getAsBoolean()) return;
            panel.getProperties().put("__dragging__", false);
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            dragStart[0] = parentPoint.getX() - panel.getLayoutX();
            dragStart[1] = parentPoint.getY() - panel.getLayoutY();
            panel.toFront();
            // 다중선택 드래그: 선택된 노드들의 초기 위치 저장
            initialPositions.clear();
            Set<Node> selected = selectedNodesSupplier != null ? selectedNodesSupplier.get() : null;
            if (selected != null && selected.contains(panel)) {
                for (Node n : selected) {
                    initialPositions.put(n, new double[]{n.getLayoutX(), n.getLayoutY()});
                }
            } else {
                // 비선택 패널: 자신만 단독 이동
                initialPositions.put(panel, new double[]{panel.getLayoutX(), panel.getLayoutY()});
            }
            event.consume();
        });

        panel.setOnMouseDragged(event -> {
            if (!canInteract.getAsBoolean()) return;
            panel.getProperties().put("__dragging__", true);
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            double newX = snapper.applyAsDouble(parentPoint.getX() - dragStart[0]);
            double newY = snapper.applyAsDouble(parentPoint.getY() - dragStart[1]);
            newX = Math.max(0, newX);
            newY = Math.max(0, newY);

            // 단일 패널 드래그 시 스마트 가이드 적용
            if (initialPositions.size() <= 1 && smartGuides != null) {
                double[] adj = smartGuides.snap(panel, newX, newY);
                newX = adj[0];
                newY = adj[1];
            }

            // initialPositions는 항상 최소 self를 포함 (단독 또는 다중선택)
            double[] panelInit = initialPositions.get(panel);
            double dx = newX - panelInit[0];
            double dy = newY - panelInit[1];
            for (Map.Entry<Node, double[]> entry : initialPositions.entrySet()) {
                Node n = entry.getKey();
                double[] init = entry.getValue();
                double nx = Math.max(0, snapper.applyAsDouble(init[0] + dx));
                double ny = Math.max(0, snapper.applyAsDouble(init[1] + dy));
                n.setLayoutX(nx);
                n.setLayoutY(ny);
                if (n instanceof Region r) expandParentIfNeeded(r);
            }
            event.consume();
        });

        panel.setOnMouseReleased(event -> {
            if (!canInteract.getAsBoolean()) return;
            if (smartGuides != null) smartGuides.clearGuides();
            if (onReleased != null) onReleased.run();
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