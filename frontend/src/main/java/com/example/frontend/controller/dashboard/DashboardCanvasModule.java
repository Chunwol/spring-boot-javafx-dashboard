package com.example.frontend.controller.dashboard;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;

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
    private Consumer<Set<Node>> onSelectionChanged;
    private DashboardSelectionHandles selectionHandles;

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

    public void setOnSelectionChanged(Consumer<Set<Node>> callback) {
        this.onSelectionChanged = callback;
    }

    /** 8방향 오버레이 핸들 시스템 초기화 (스냅 함수 주입). */
    public void initSelectionHandles(DoubleUnaryOperator snapper) {
        if (selectionHandles != null) return;
        this.selectionHandles = new DashboardSelectionHandles(
                classroomContainer, editModeSupplier, snapper);
        this.selectionHandles.setOnResizeReleased(node -> {
            if (saveNodePositionHandler != null) saveNodePositionHandler.accept(node);
        });
    }

    public Set<Node> getSelectedNodes() {
        return java.util.Collections.unmodifiableSet(selectedNodes);
    }

    public void selectNodeExternal(Node node, boolean addMode) {
        selectNode(node, addMode);
        if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
    }

    public void clearSelectionExternal() {
        clearSelection();
        if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
    }

    /** 캔버스의 모든 콘텐츠 패널 선택 (Ctrl+A). */
    public void selectAll() {
        clearSelection();
        for (Node n : classroomContainer.getChildren()) {
            if (n == gridCanvas || n == selectionRect) continue;
            if (!n.getProperties().containsKey("panelType")) continue;
            selectedNodes.add(n);
            if (!n.getStyleClass().contains("panel-selected")) {
                n.getStyleClass().add("panel-selected");
            }
        }
        updateOverlayHandles();
        if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
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
            // 우측 패널 상태 동기화
            if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
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
            if (!editModeSupplier.getAsBoolean()) return;
            if (!e.isShiftDown()) {
                selectionRect.setVisible(false);
                // 빈 영역 클릭: 선택 해제
                Object target = e.getTarget();
                if (target == classroomContainer || target == gridCanvas) {
                    clearSelection();
                    if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
                }
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
            if (!editModeSupplier.getAsBoolean() || !selectionRect.isVisible()) return;
            double currentX = e.getX();
            double currentY = e.getY();
            selectionRect.setX(Math.min(selectionStartX, currentX));
            selectionRect.setY(Math.min(selectionStartY, currentY));
            selectionRect.setWidth(Math.abs(currentX - selectionStartX));
            selectionRect.setHeight(Math.abs(currentY - selectionStartY));
        });

        classroomContainer.setOnMouseReleased(e -> {
            if (!editModeSupplier.getAsBoolean() || !selectionRect.isVisible()) return;
            selectNodesInRect(selectionRect);
            selectionRect.setVisible(false);
            if (onSelectionChanged != null) onSelectionChanged.accept(getSelectedNodes());
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
            node.getStyleClass().remove("panel-selected");
        }
        selectedNodes.clear();
        if (selectionHandles != null) selectionHandles.hide();
    }

    private void selectNode(Node node, boolean add) {
        if (!add) {
            clearSelection();
        }
        if (selectedNodes.contains(node)) {
            selectedNodes.remove(node);
            node.getStyleClass().remove("panel-selected");
        } else {
            selectedNodes.add(node);
            if (!node.getStyleClass().contains("panel-selected")) {
                node.getStyleClass().add("panel-selected");
            }
        }
        updateOverlayHandles();
    }

    /** 단일 선택 시에만 8방향 핸들 표시. 다중 선택이나 미선택 시 숨김. */
    private void updateOverlayHandles() {
        if (selectionHandles == null) return;
        if (selectedNodes.size() == 1) {
            Node only = selectedNodes.iterator().next();
            if (only instanceof javafx.scene.layout.Region r) {
                selectionHandles.showFor(r);
                return;
            }
        }
        selectionHandles.hide();
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

    // ─────────────────────────────────────────────────────────────
    // 자동 정렬 & 겹침 해소
    // ─────────────────────────────────────────────────────────────

    /**
     * 설정에 따라 캔버스의 모든 패널을 겹치지 않게 자동 배치한다.
     *
     * @param settings      Flow/Grid/Gap/Padding 설정
     * @param saveCallback  위치 변경 후 호출할 저장 콜백 (Node를 인자로 받음)
     */
    public void autoArrangeNodes(DashboardGridSettings settings, Consumer<Node> saveCallback) {
        List<Node> nodes = collectContentNodes();
        if (nodes.isEmpty()) return;

        // 현재 위치로 정렬 (읽기 순서: Y → X)
        nodes.sort(Comparator.comparingDouble(Node::getLayoutY)
                .thenComparingDouble(Node::getLayoutX));

        double padLeft = settings.getPaddingLeft();
        double padTop  = settings.getPaddingTop();
        double hGap    = settings.getHGap();
        double vGap    = settings.getVGap();

        double canvasW = classroomContainer.getPrefWidth() > 200 ? classroomContainer.getPrefWidth() : 0;
        switch (settings.getDirection()) {
            case ROW -> arrangeInRow(nodes, padLeft, padTop, hGap, vGap, canvasW, saveCallback);
            case COLUMN -> arrangeInColumn(nodes, padLeft, padTop, vGap, saveCallback);
            case GRID -> arrangeInGrid(nodes, settings.getColumns(), padLeft, padTop, hGap, vGap, saveCallback);
            default -> resolveOverlaps(settings, saveCallback); // FREE: 겹침만 해소
        }
    }

    /**
     * 겹치는 패널을 감지하여 겹치지 않도록 위치를 조정한다.
     *
     * @param settings      Gap/Padding 참조용
     * @param saveCallback  위치 변경 후 호출할 저장 콜백
     */
    public void resolveOverlaps(DashboardGridSettings settings, Consumer<Node> saveCallback) {
        List<Node> nodes = collectContentNodes();
        if (nodes.size() < 2) return;

        // Z-index 순(뒤에 있는 것이 먼저) → 낮은 z가 먼저 자리 잡음
        nodes.sort(Comparator.comparingInt(n -> classroomContainer.getChildren().indexOf(n)));

        double gap = Math.max(settings.getHGap(), 4.0);
        boolean changed = true;
        int maxIter = 20; // 무한 루프 방지

        while (changed && maxIter-- > 0) {
            changed = false;
            for (int i = 0; i < nodes.size(); i++) {
                Node a = nodes.get(i);
                Bounds ba = nodeBounds(a);
                for (int j = i + 1; j < nodes.size(); j++) {
                    Node b = nodes.get(j);
                    Bounds bb = nodeBounds(b);
                    if (!ba.intersects(bb)) continue;

                    // b를 a의 오른쪽 또는 아래로 밀어냄
                    double overlapX = ba.getMaxX() + gap - bb.getMinX();
                    double overlapY = ba.getMaxY() + gap - bb.getMinY();

                    if (overlapX > 0 && overlapY > 0) {
                        // 더 작은 방향으로 밀기
                        if (overlapX <= overlapY) {
                            b.setLayoutX(b.getLayoutX() + overlapX);
                        } else {
                            b.setLayoutY(b.getLayoutY() + overlapY);
                        }
                        if (saveCallback != null) saveCallback.accept(b);
                        bb = nodeBounds(b);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * 새 노드를 놓을 때 기존 노드들과 겹치지 않는 위치를 반환한다.
     *
     * @param preferredX 선호 X 위치
     * @param preferredY 선호 Y 위치
     * @param itemW      배치할 아이템 너비
     * @param itemH      배치할 아이템 높이
     * @param gap        노드 간 최소 간격
     * @return 겹치지 않는 위치 [x, y]
     */
    public double[] findFreePosition(double preferredX, double preferredY,
                                     double itemW, double itemH, double gap) {
        List<Node> existing = collectContentNodes();
        double x = preferredX;
        double y = preferredY;

        for (int attempt = 0; attempt < 50; attempt++) {
            Bounds candidate = new BoundingBox(x, y, itemW, itemH);
            boolean overlaps = false;
            for (Node n : existing) {
                Bounds nb = nodeBounds(n);
                Bounds expanded = new BoundingBox(
                        nb.getMinX() - gap, nb.getMinY() - gap,
                        nb.getWidth() + gap * 2, nb.getHeight() + gap * 2);
                if (candidate.intersects(expanded)) {
                    // 오른쪽 또는 아래로 이동
                    x = nb.getMaxX() + gap;
                    if (x + itemW > classroomContainer.getPrefWidth() - 40) {
                        x = preferredX;
                        y = nb.getMaxY() + gap;
                    }
                    candidate = new BoundingBox(x, y, itemW, itemH);
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) break;
        }
        return new double[]{x, y};
    }

    // ── private helpers ──────────────────────────────────────────

    private List<Node> collectContentNodes() {
        List<Node> result = new ArrayList<>();
        for (Node n : classroomContainer.getChildren()) {
            if (n != gridCanvas && n != selectionRect) {
                result.add(n);
            }
        }
        return result;
    }

    private static Bounds nodeBounds(Node n) {
        double w = n.getBoundsInLocal().getWidth();
        double h = n.getBoundsInLocal().getHeight();
        return new BoundingBox(n.getLayoutX(), n.getLayoutY(), w, h);
    }

    private void arrangeInRow(List<Node> nodes, double padLeft, double padTop,
                              double hGap, double vGap, double maxRowWidth, Consumer<Node> save) {
        double x = padLeft;
        double y = padTop;
        double rowMaxH = 0;
        for (Node n : nodes) {
            double w = n.getBoundsInLocal().getWidth();
            double h = n.getBoundsInLocal().getHeight();
            // Wrap to the next row when the panel would exceed canvas width
            if (maxRowWidth > padLeft + 1 && x + w > maxRowWidth && x > padLeft) {
                x = padLeft;
                y += rowMaxH + vGap;
                rowMaxH = 0;
            }
            n.setLayoutX(x);
            n.setLayoutY(y);
            if (save != null) save.accept(n);
            x += w + hGap;
            rowMaxH = Math.max(rowMaxH, h);
        }
    }

    private void arrangeInColumn(List<Node> nodes, double padLeft, double padTop,
                                 double vGap, Consumer<Node> save) {
        double y = padTop;
        for (Node n : nodes) {
            double h = n.getBoundsInLocal().getHeight();
            n.setLayoutX(padLeft);
            n.setLayoutY(y);
            if (save != null) save.accept(n);
            y += h + vGap;
        }
    }

    private void arrangeInGrid(List<Node> nodes, int cols,
                               double padLeft, double padTop,
                               double hGap, double vGap,
                               Consumer<Node> save) {
        // 각 열의 최대 너비, 각 행의 최대 높이를 미리 계산
        int rowCount = (int) Math.ceil((double) nodes.size() / cols);
        double[] colWidths  = new double[cols];
        double[] rowHeights = new double[rowCount];

        for (int i = 0; i < nodes.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            double w = nodes.get(i).getBoundsInLocal().getWidth();
            double h = nodes.get(i).getBoundsInLocal().getHeight();
            colWidths[col]   = Math.max(colWidths[col], w);
            rowHeights[row]  = Math.max(rowHeights[row], h);
        }

        // 각 셀 X/Y 오프셋 계산
        double[] colX = new double[cols];
        double[] rowY = new double[rowCount];
        colX[0] = padLeft;
        for (int c = 1; c < cols; c++) {
            colX[c] = colX[c - 1] + colWidths[c - 1] + hGap;
        }
        rowY[0] = padTop;
        for (int r = 1; r < rowCount; r++) {
            rowY[r] = rowY[r - 1] + rowHeights[r - 1] + vGap;
        }

        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            n.setLayoutX(colX[i % cols]);
            n.setLayoutY(rowY[i / cols]);
            if (save != null) save.accept(n);
        }
    }
}
