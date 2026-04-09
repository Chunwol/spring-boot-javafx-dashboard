package com.example.frontend.controller.dashboard;

import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class DashboardCanvasModule {

    public interface DropPositionHandler {
        void handle(Long id, double x, double y);
    }

    private final BooleanSupplier editModeSupplier;
    private final Consumer<Node> saveNodePositionHandler;
    private final DropPositionHandler groupDropHandler;
    private final DropPositionHandler textDropHandler;
    private final DropPositionHandler imageDropHandler;

    private final Pane classroomContainer;
    private final Canvas gridCanvas;
    private final Rectangle selectionRect;
    private final Set<Node> selectedNodes = new HashSet<>();

    private double selectionStartX;
    private double selectionStartY;

    public DashboardCanvasModule(BooleanSupplier editModeSupplier,
                                 Consumer<Node> saveNodePositionHandler,
                                 DropPositionHandler groupDropHandler,
                                 DropPositionHandler textDropHandler,
                                 DropPositionHandler imageDropHandler) {
        this.editModeSupplier = editModeSupplier;
        this.saveNodePositionHandler = saveNodePositionHandler;
        this.groupDropHandler = groupDropHandler;
        this.textDropHandler = textDropHandler;
        this.imageDropHandler = imageDropHandler;

        this.classroomContainer = new Pane();
        this.classroomContainer.getStyleClass().add("dashboard-canvas-bg");
        this.classroomContainer.setPrefSize(1, 1);

        this.gridCanvas = new Canvas(1, 1);
        this.gridCanvas.setMouseTransparent(true);
        this.gridCanvas.setVisible(false);
        this.classroomContainer.getChildren().add(gridCanvas);

        this.selectionRect = new Rectangle();
        this.selectionRect.setFill(Color.rgb(0, 120, 215, 0.3));
        this.selectionRect.setStroke(Color.rgb(0, 120, 215, 0.8));
        this.selectionRect.getStrokeDashArray().addAll(5.0, 5.0);
        this.selectionRect.setVisible(false);
        this.classroomContainer.getChildren().add(selectionRect);

        bindEvents();
    }

    public Pane getClassroomContainer() {
        return classroomContainer;
    }

    public Canvas getGridCanvas() {
        return gridCanvas;
    }

    public Rectangle getSelectionRect() {
        return selectionRect;
    }

    public void applyEditMode(boolean enabled) {
        if (enabled) {
            if (!classroomContainer.getStyleClass().contains("dashboard-canvas-edit")) {
                classroomContainer.getStyleClass().add("dashboard-canvas-edit");
            }
        } else {
            classroomContainer.getStyleClass().remove("dashboard-canvas-edit");
            clearSelection();
            selectionRect.setVisible(false);
        }
    }

    public void drawGrid(boolean show) {
        gridCanvas.setVisible(show);
        if (!show) {
            return;
        }

        GraphicsContext gc = gridCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, gridCanvas.getWidth(), gridCanvas.getHeight());

        double gridSize = DashboardViewModel.GRID_SIZE;
        double majorStep = gridSize * 5;

        // 마이너 그리드: 부드러운 배경 가이드
        gc.setLineWidth(0.45);
        gc.setStroke(Color.rgb(148, 163, 184, 0.12));
        for (double x = 0; x < gridCanvas.getWidth(); x += gridSize) {
            gc.strokeLine(x, 0, x, gridCanvas.getHeight());
        }
        for (double y = 0; y < gridCanvas.getHeight(); y += gridSize) {
            gc.strokeLine(0, y, gridCanvas.getWidth(), y);
        }

        // 메이저 그리드: 주요 가이드 라인
        gc.setLineWidth(0.75);
        gc.setStroke(Color.rgb(100, 116, 139, 0.22));
        for (double x = 0; x < gridCanvas.getWidth(); x += majorStep) {
            gc.strokeLine(x, 0, x, gridCanvas.getHeight());
        }
        for (double y = 0; y < gridCanvas.getHeight(); y += majorStep) {
            gc.strokeLine(0, y, gridCanvas.getWidth(), y);
        }

        gridCanvas.toBack();
    }

    public void alignSelectedNodes(String type) {
        if (selectedNodes.size() < 2) {
            return;
        }

        double targetVal = 0;
        if (type.equals("LEFT")) {
            targetVal = selectedNodes.stream().mapToDouble(Node::getLayoutX).min().orElse(0);
        } else if (type.equals("RIGHT")) {
            targetVal = selectedNodes.stream().mapToDouble(n -> n.getLayoutX() + n.getBoundsInLocal().getWidth()).max().orElse(0);
        } else if (type.equals("TOP")) {
            targetVal = selectedNodes.stream().mapToDouble(Node::getLayoutY).min().orElse(0);
        } else if (type.equals("BOTTOM")) {
            targetVal = selectedNodes.stream().mapToDouble(n -> n.getLayoutY() + n.getBoundsInLocal().getHeight()).max().orElse(0);
        }

        for (Node node : selectedNodes) {
            if (type.equals("LEFT")) {
                node.setLayoutX(targetVal);
            } else if (type.equals("RIGHT")) {
                node.setLayoutX(targetVal - node.getBoundsInLocal().getWidth());
            } else if (type.equals("TOP")) {
                node.setLayoutY(targetVal);
            } else if (type.equals("BOTTOM")) {
                node.setLayoutY(targetVal - node.getBoundsInLocal().getHeight());
            }
            saveNodePositionHandler.accept(node);
        }
    }

    private void bindEvents() {
        classroomContainer.setOnMousePressed(e -> {
            if (!editModeSupplier.getAsBoolean()) {
                return;
            }
            if (!e.isShiftDown()) {
                selectionRect.setVisible(false);
                return;
            }
            Object target = e.getTarget();
            if (target != classroomContainer && target != gridCanvas) {
                selectionRect.setVisible(false);
                return;
            }
            if (!e.isControlDown()) {
                clearSelection();
            }
            selectionStartX = e.getX();
            selectionStartY = e.getY();
            selectionRect.setX(selectionStartX);
            selectionRect.setY(selectionStartY);
            selectionRect.setWidth(0);
            selectionRect.setHeight(0);
            selectionRect.setVisible(true);
            selectionRect.toFront();
        });

        classroomContainer.setOnMouseDragged(e -> {
            if (!editModeSupplier.getAsBoolean() || !selectionRect.isVisible()) {
                return;
            }
            double currentX = e.getX();
            double currentY = e.getY();
            selectionRect.setX(Math.min(selectionStartX, currentX));
            selectionRect.setY(Math.min(selectionStartY, currentY));
            selectionRect.setWidth(Math.abs(currentX - selectionStartX));
            selectionRect.setHeight(Math.abs(currentY - selectionStartY));
        });

        classroomContainer.setOnMouseReleased(e -> {
            if (!editModeSupplier.getAsBoolean() || !selectionRect.isVisible()) {
                return;
            }
            selectNodesInRect(selectionRect);
            selectionRect.setVisible(false);
        });

        classroomContainer.setOnDragOver(event -> {
            if (event.getGestureSource() != classroomContainer && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        classroomContainer.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasString()) {
                String content = db.getString();
                if (content.startsWith("GROUP_ADD:")) {
                    groupDropHandler.handle(Long.parseLong(content.split(":")[1]), event.getX(), event.getY());
                    success = true;
                } else if (content.startsWith("TEXT_ADD:")) {
                    textDropHandler.handle(Long.parseLong(content.split(":")[1]), event.getX(), event.getY());
                    success = true;
                } else if (content.startsWith("IMAGE_ADD:")) {
                    imageDropHandler.handle(Long.parseLong(content.split(":")[1]), event.getX(), event.getY());
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void clearSelection() {
        for (Node node : selectedNodes) {
            node.setEffect(null);
        }
        selectedNodes.clear();
    }

    private void selectNode(Node node, boolean add) {
        if (!add) {
            clearSelection();
        }
        if (selectedNodes.contains(node)) {
            selectedNodes.remove(node);
            node.setEffect(null);
        } else {
            selectedNodes.add(node);
            node.setEffect(new javafx.scene.effect.DropShadow(10, Color.web("#0078d7")));
        }
    }

    private void selectNodesInRect(Rectangle rect) {
        for (Node node : classroomContainer.getChildren()) {
            if (node == gridCanvas || node == selectionRect) {
                continue;
            }
            if (node.getBoundsInParent().intersects(rect.getBoundsInParent()) && !selectedNodes.contains(node)) {
                selectNode(node, true);
            }
        }
    }
}
