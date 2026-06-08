package com.example.frontend.controller.dashboard;

import javafx.beans.value.ChangeListener;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;

/**
 * Figma 스타일 8방향 리사이즈 핸들 (오버레이).
 * <p>
 * 선택된 패널의 8개 지점(4개 모서리 + 4개 변 중점)에 작은 사각형 핸들을 표시한다.
 * 핸들은 캔버스 위에 절대 위치로 배치되어 패널 레이아웃에 영향을 주지 않는다.
 * <ul>
 *   <li>NW/NE/SW/SE: 대각선 리사이즈</li>
 *   <li>N/S: 세로 리사이즈</li>
 *   <li>W/E: 가로 리사이즈</li>
 * </ul>
 */
public class DashboardSelectionHandles {
    private static final double HANDLE_SIZE = 10.0;
    private static final double MIN_WIDTH = 80.0;
    private static final double MIN_HEIGHT = 60.0;

    private enum Direction { NW, N, NE, W, E, SW, S, SE }

    private final Region[] handles = new Region[8];
    private final BooleanSupplier canInteract;
    private final DoubleUnaryOperator snapper;
    private Consumer<Region> onResizeReleased;

    private Region target;

    private final ChangeListener<Number> repositionListener = (o, ov, nv) -> updatePositions();

    public DashboardSelectionHandles(Pane container,
                                       BooleanSupplier canInteract,
                                       DoubleUnaryOperator snapper) {
        this.canInteract = canInteract;
        this.snapper = snapper != null ? snapper : v -> v;

        Direction[] dirs = {Direction.NW, Direction.N, Direction.NE,
                            Direction.W, Direction.E,
                            Direction.SW, Direction.S, Direction.SE};
        Cursor[] cursors = {Cursor.NW_RESIZE, Cursor.N_RESIZE, Cursor.NE_RESIZE,
                            Cursor.W_RESIZE, Cursor.E_RESIZE,
                            Cursor.SW_RESIZE, Cursor.S_RESIZE, Cursor.SE_RESIZE};

        for (int i = 0; i < 8; i++) {
            Region h = new Region();
            h.setPrefSize(HANDLE_SIZE, HANDLE_SIZE);
            h.setMinSize(HANDLE_SIZE, HANDLE_SIZE);
            h.setMaxSize(HANDLE_SIZE, HANDLE_SIZE);
            h.getStyleClass().add("selection-handle");
            h.setCursor(cursors[i]);
            h.setManaged(false);
            h.setVisible(false);
            handles[i] = h;
            container.getChildren().add(h);
            attachResize(h, dirs[i]);
        }
    }

    public void setOnResizeReleased(Consumer<Region> cb) {
        this.onResizeReleased = cb;
    }

    /** 지정된 패널에 핸들 표시. null 전달 시 숨김. */
    public void showFor(Region newTarget) {
        if (this.target == newTarget) {
            updatePositions();
            return;
        }
        if (this.target != null) detachListeners(this.target);
        this.target = newTarget;
        if (newTarget == null) {
            for (Region h : handles) h.setVisible(false);
            return;
        }
        attachListeners(newTarget);
        updatePositions();
        for (Region h : handles) {
            boolean wasHidden = !h.isVisible();
            h.setVisible(true);
            if (wasHidden) h.toFront(); // 새로 보이는 경우에만 z-order 조정
        }
    }

    public void hide() {
        showFor(null);
    }

    public Region getTarget() {
        return target;
    }

    private void attachListeners(Region t) {
        t.layoutXProperty().addListener(repositionListener);
        t.layoutYProperty().addListener(repositionListener);
        t.widthProperty().addListener(repositionListener);
        t.heightProperty().addListener(repositionListener);
        t.prefWidthProperty().addListener(repositionListener);
        t.prefHeightProperty().addListener(repositionListener);
    }

    private void detachListeners(Region t) {
        t.layoutXProperty().removeListener(repositionListener);
        t.layoutYProperty().removeListener(repositionListener);
        t.widthProperty().removeListener(repositionListener);
        t.heightProperty().removeListener(repositionListener);
        t.prefWidthProperty().removeListener(repositionListener);
        t.prefHeightProperty().removeListener(repositionListener);
    }

    private void updatePositions() {
        if (target == null) return;
        double x = target.getLayoutX();
        double y = target.getLayoutY();
        double w = target.getWidth() > 0 ? target.getWidth() : target.getPrefWidth();
        double h = target.getHeight() > 0 ? target.getHeight() : target.getPrefHeight();
        double half = HANDLE_SIZE / 2;

        // NW(0), N(1), NE(2)
        handles[0].setLayoutX(x - half);            handles[0].setLayoutY(y - half);
        handles[1].setLayoutX(x + w / 2 - half);    handles[1].setLayoutY(y - half);
        handles[2].setLayoutX(x + w - half);        handles[2].setLayoutY(y - half);
        // W(3), E(4)
        handles[3].setLayoutX(x - half);            handles[3].setLayoutY(y + h / 2 - half);
        handles[4].setLayoutX(x + w - half);        handles[4].setLayoutY(y + h / 2 - half);
        // SW(5), S(6), SE(7)
        handles[5].setLayoutX(x - half);            handles[5].setLayoutY(y + h - half);
        handles[6].setLayoutX(x + w / 2 - half);    handles[6].setLayoutY(y + h - half);
        handles[7].setLayoutX(x + w - half);        handles[7].setLayoutY(y + h - half);
    }

    private void attachResize(Region handle, Direction dir) {
        // [mouseSceneX, mouseSceneY, layoutX, layoutY, prefW, prefH]
        final double[] start = new double[6];

        handle.setOnMousePressed(e -> {
            if (!canInteract.getAsBoolean() || target == null) return;
            start[0] = e.getSceneX();
            start[1] = e.getSceneY();
            start[2] = target.getLayoutX();
            start[3] = target.getLayoutY();
            start[4] = target.getPrefWidth() > 0 ? target.getPrefWidth() : target.getWidth();
            start[5] = target.getPrefHeight() > 0 ? target.getPrefHeight() : target.getHeight();
            e.consume();
        });

        handle.setOnMouseDragged(e -> {
            if (!canInteract.getAsBoolean() || target == null) return;
            double dx = e.getSceneX() - start[0];
            double dy = e.getSceneY() - start[1];
            double x0 = start[2], y0 = start[3], w0 = start[4], h0 = start[5];

            switch (dir) {
                case NW -> {
                    double nx = clamp(snapper.applyAsDouble(x0 + dx), 0, x0 + w0 - MIN_WIDTH);
                    double ny = clamp(snapper.applyAsDouble(y0 + dy), 0, y0 + h0 - MIN_HEIGHT);
                    target.setLayoutX(nx);
                    target.setLayoutY(ny);
                    target.setPrefWidth(w0 + (x0 - nx));
                    target.setPrefHeight(h0 + (y0 - ny));
                }
                case N -> {
                    double ny = clamp(snapper.applyAsDouble(y0 + dy), 0, y0 + h0 - MIN_HEIGHT);
                    target.setLayoutY(ny);
                    target.setPrefHeight(h0 + (y0 - ny));
                }
                case NE -> {
                    double ny = clamp(snapper.applyAsDouble(y0 + dy), 0, y0 + h0 - MIN_HEIGHT);
                    target.setLayoutY(ny);
                    target.setPrefHeight(h0 + (y0 - ny));
                    target.setPrefWidth(Math.max(MIN_WIDTH, snapper.applyAsDouble(w0 + dx)));
                }
                case W -> {
                    double nx = clamp(snapper.applyAsDouble(x0 + dx), 0, x0 + w0 - MIN_WIDTH);
                    target.setLayoutX(nx);
                    target.setPrefWidth(w0 + (x0 - nx));
                }
                case E -> {
                    target.setPrefWidth(Math.max(MIN_WIDTH, snapper.applyAsDouble(w0 + dx)));
                }
                case SW -> {
                    double nx = clamp(snapper.applyAsDouble(x0 + dx), 0, x0 + w0 - MIN_WIDTH);
                    target.setLayoutX(nx);
                    target.setPrefWidth(w0 + (x0 - nx));
                    target.setPrefHeight(Math.max(MIN_HEIGHT, snapper.applyAsDouble(h0 + dy)));
                }
                case S -> {
                    target.setPrefHeight(Math.max(MIN_HEIGHT, snapper.applyAsDouble(h0 + dy)));
                }
                case SE -> {
                    target.setPrefWidth(Math.max(MIN_WIDTH, snapper.applyAsDouble(w0 + dx)));
                    target.setPrefHeight(Math.max(MIN_HEIGHT, snapper.applyAsDouble(h0 + dy)));
                }
            }
            e.consume();
        });

        handle.setOnMouseReleased(e -> {
            if (!canInteract.getAsBoolean() || target == null) return;
            if (onResizeReleased != null) onResizeReleased.accept(target);
            e.consume();
        });
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }
}
