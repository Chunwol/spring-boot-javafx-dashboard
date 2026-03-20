package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.controller.dashboard.DashboardInteractionService;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.ApiClient;
import com.example.frontend.model.panel.PanelInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 강의실 패널의 생성/업데이트/삭제, 드래그/리사이즈,
 * 강의실 CRUD API 호출을 담당하는 모듈.
 */
public class DashboardClassroomModule {

    private static final Logger log = LoggerFactory.getLogger(DashboardClassroomModule.class);

    private final ApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Pane classroomContainer;
    private final DashboardPanelManager panelManager;
    private final BooleanSupplier editModeSupplier;
    private final Runnable refreshCallback;
    private final LongSupplier dashboardIdSupplier;
    private final Runnable refreshPanelCallback;
    private final BooleanSupplier snapModeSupplier;
    private final DashboardInteractionService dashboardInteractionService = new DashboardInteractionService();
    private final Map<Long, Button> classroomDeleteBtns = new HashMap<>();
    private final Map<Long, List<PcStatusDto>> pcCacheByGroupId = new HashMap<>();
    private static final double GRID_SIZE = 20.0;

    public DashboardClassroomModule(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String apiBaseUrl,
            Pane classroomContainer,
            DashboardPanelManager panelManager,
            BooleanSupplier editModeSupplier,
            Runnable refreshCallback,
            LongSupplier dashboardIdSupplier,
            Runnable refreshPanelCallback,
            BooleanSupplier snapModeSupplier) {
        this.apiClient = ApiClient.getInstance();
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.classroomContainer = classroomContainer;
        this.panelManager = panelManager;
        this.editModeSupplier = editModeSupplier;
        this.refreshCallback = refreshCallback;
        this.dashboardIdSupplier = dashboardIdSupplier;
        this.refreshPanelCallback = refreshPanelCallback;
        this.snapModeSupplier = snapModeSupplier;
    }

    // ─── 패널 갱신 (public) ────────────────────────────────────────────────

    /** 강의실 목록을 받아 대시보드 패널을 추가/업데이트/제거한다. */
    public void updateDashboardClassrooms(List<ClassroomSummaryDto> summaries, List<DashboardPanelDto> dashboardPanels) {
        Map<Long, ClassroomSummaryDto> summaryByGroupId = new HashMap<>();
        for (ClassroomSummaryDto summary : summaries) {
            summaryByGroupId.put(summary.getGroupId(), summary);
        }

        List<DashboardPanelDto> classroomPanels = dashboardPanels.stream()
                .filter(p -> "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType()))
                .toList();

        List<Long> activePanelIds = classroomPanels.stream().map(DashboardPanelDto::getId).toList();
        List<Long> existingPanelIds = panelManager.getPanels().stream()
                .filter(p -> p.getType() == PanelInfo.PanelType.CLASSROOM)
                .map(PanelInfo::getId)
                .toList();

        for (Long panelId : existingPanelIds) {
            if (!activePanelIds.contains(panelId)) {
                VBox panel = panelManager.getPanels().stream()
                        .filter(p -> p.getType() == PanelInfo.PanelType.CLASSROOM && p.getId() == panelId)
                        .map(PanelInfo::getPanel)
                        .findFirst().orElse(null);
                if (panel != null) {
                    classroomContainer.getChildren().remove(panel);
                }
                classroomDeleteBtns.remove(panelId);
                panelManager.removePanel(panelId);
            }
        }

        for (DashboardPanelDto panelDto : classroomPanels) {
            ClassroomSummaryDto summary = summaryByGroupId.get(panelDto.getRefGroupId());
            if (summary == null) {
                continue;
            }

            PanelInfo info = panelManager.getPanels().stream()
                    .filter(p -> p.getType() == PanelInfo.PanelType.CLASSROOM && p.getId() == panelDto.getId())
                    .findFirst().orElse(null);

            VBox panel;
            if (info != null) {
                panel = info.getPanel();
                updateClassroomPanel(panel, summary, panelDto);
            } else {
                panel = createClassroomPanel(summary, panelDto);
                panelManager.addPanel(panelDto.getId(), PanelInfo.PanelType.CLASSROOM, panel);
                classroomContainer.getChildren().add(panel);
            }
        }
    }

    // ─── 강의실 CRUD API (public) ──────────────────────────────────────────

    public void handleAddClassroom() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("새 강의실 추가");
        dialog.setHeaderText("새로운 강의실의 이름을 입력하세요.");
        dialog.setContentText("이름:");

        dialog.showAndWait().ifPresent(name -> {
            if (name == null || name.isBlank()) {
                new Alert(Alert.AlertType.WARNING, "강의실 이름을 입력해야 합니다.").showAndWait();
                return;
            }
            apiClient.postJson("/groups", java.util.Map.of("name", name))
                    .thenAccept(response -> Platform.runLater(() -> {
                        refreshCallback.run();
                        new Alert(Alert.AlertType.INFORMATION, "강의실이 성공적으로 추가되었습니다.").showAndWait();
                    }))
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "네트워크 오류 또는 서버 응답 실패: " + cause.getMessage()).showAndWait());
                        return null;
                    });
        });
    }

    public void addClassroomPanel(ClassroomSummaryDto group, double x, double y, double width, double height) {
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId <= 0) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "먼저 대시보드를 선택하세요.").showAndWait());
            return;
        }

        apiClient.postJson("/dashboards/" + dashboardId + "/panels", Map.of(
                "panelType", "CLASSROOM_HEARTBEAT",
                "refGroupId", group.getGroupId(),
                "x", x,
                "y", y,
                "width", width,
                "height", height,
                "visible", true,
                "zIndex", 0
        )).thenAccept(resp -> Platform.runLater(refreshPanelCallback))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "강의실 패널 추가 실패: " + cause.getMessage()).showAndWait());
                    return null;
                });
    }

    public void deleteClassroomPanel(Long panelId) {
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId <= 0) {
            return;
        }
        apiClient.delete("/dashboards/" + dashboardId + "/panels/" + panelId)
                .thenAccept(resp -> Platform.runLater(refreshPanelCallback))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "강의실 패널 삭제 실패: " + cause.getMessage()).showAndWait());
                    return null;
                });
    }

    public void handleDeleteGroup(ClassroomSummaryDto group) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "강의실 [" + group.getGroupName() + "]을(를) 정말 삭제하시겠습니까?\n연결된 IP 대역 설정도 함께 삭제됩니다.",
                ButtonType.YES, ButtonType.NO);

        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
                apiClient.delete("/groups/" + group.getGroupId())
                        .thenAccept(resp -> refreshCallback.run());
            }
        });
    }

    public void toggleGroupVisibility(ClassroomSummaryDto group, boolean isVisible) {
        apiClient.putJson("/groups/" + group.getGroupId(), new LinkedHashMap<>() {{
            put("name", group.getGroupName());
            put("description", "");
            put("visible", isVisible);
        }})
                .thenAccept(resp -> {
                    refreshCallback.run();
                });
    }

    public void updateGroupPosition(Long groupId, double x, double y,
                                     double width, double height, boolean visible) {
        // legacy no-op: classroom panel layout is now persisted via dashboard panels
    }

    /** editMode 연동: 삭제 버튼 visibility 토글 */
    public void applyEditMode(boolean editMode) {
        classroomDeleteBtns.values().forEach(btn -> {
            btn.setVisible(editMode);
            btn.setManaged(editMode);
        });
    }

    public void handleAddNetworkPanel(ClassroomSummaryDto selected) {
        NetworkPanelController controller = new NetworkPanelController(selected);
        VBox networkPanel = controller.getPanel();
        classroomContainer.getChildren().add(networkPanel);
        panelManager.addPanel(PanelInfo.PanelType.NETWORK, networkPanel);
    }

    // ─── 패널 내부 빌더 (private) ──────────────────────────────────────────

    private VBox createClassroomPanel(ClassroomSummaryDto summary, DashboardPanelDto panelDto) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        panel.getProperties().put("dashboardPanelId", panelDto.getId());
        panel.getProperties().put("groupId", summary.getGroupId());
        panel.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 20.0);
        panel.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 20.0);
        panel.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : 360.0);
        panel.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : 280.0);

        Label nameLabel = new Label(summary.getGroupName());
        nameLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 56));
        nameLabel.setTextFill(Color.web("#e5e7eb"));
        StackPane titleBanner = new StackPane(nameLabel);
        titleBanner.setPadding(new Insets(20, 8, 20, 8));
        titleBanner.setStyle("-fx-background-color: #4d8a3a; -fx-background-radius: 8;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button deleteBtn = new Button("삭제");
        deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        deleteBtn.setVisible(editModeSupplier.getAsBoolean());
        deleteBtn.setManaged(editModeSupplier.getAsBoolean());
        deleteBtn.setOnAction(e -> {
            e.consume();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "이 강의실 패널을 대시보드에서 제거하시겠습니까?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(type -> {
                if (type == ButtonType.YES) {
                    classroomDeleteBtns.remove(panelDto.getId());
                    deleteClassroomPanel(panelDto.getId());
                }
            });
        });
        classroomDeleteBtns.put(panelDto.getId(), deleteBtn);
        HBox header = new HBox(8, nameLabel, headerSpacer, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        Label totalLabel = new Label(summary.getTotalPcs() + " 대");
        totalLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 54));
        totalLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane totalBox = new StackPane(totalLabel);
        totalBox.setPadding(new Insets(16));
        totalBox.setStyle("-fx-background-color: #979ca3; -fx-background-radius: 4;");
        HBox.setHgrow(totalBox, Priority.ALWAYS);

        Label onlineLabel = new Label(summary.getOnlinePcs() + " 대");
        onlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 54));
        onlineLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane onlineBox = new StackPane(onlineLabel);
        onlineBox.setPadding(new Insets(16));
        onlineBox.setStyle("-fx-background-color: #6da657; -fx-background-radius: 4;");
        HBox.setHgrow(onlineBox, Priority.ALWAYS);

        int offlineCount = Math.max(0, summary.getTotalPcs() - summary.getOnlinePcs());
        Label offlineLabel = new Label(offlineCount + " 대");
        offlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 54));
        offlineLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane offlineBox = new StackPane(offlineLabel);
        offlineBox.setPadding(new Insets(16));
        offlineBox.setStyle("-fx-background-color: #cf404a; -fx-background-radius: 4;");
        HBox.setHgrow(offlineBox, Priority.ALWAYS);

        HBox countStrip = new HBox(2, totalBox, onlineBox, offlineBox);

        GridPane pcGrid = new GridPane();
        pcGrid.setHgap(6);
        pcGrid.setVgap(6);
        VBox.setVgrow(pcGrid, Priority.ALWAYS);
        panel.getProperties().put("pcGrid", pcGrid);
        panel.getProperties().put("titleBanner", titleBanner);
        panel.getProperties().put("nameLabel", nameLabel);
        panel.getProperties().put("totalLabel", totalLabel);
        panel.getProperties().put("onlineLabel", onlineLabel);
        panel.getProperties().put("offlineLabel", offlineLabel);
        panel.getProperties().put("groupId", summary.getGroupId());

        renderPcGrid(summary.getTotalPcs(), pcCacheByGroupId.get(summary.getGroupId()), pcGrid, panel);
        fetchPcStatuses(summary.getGroupId(), summary.getTotalPcs(), pcGrid);

        panel.widthProperty().addListener((obs, ov, nv) -> {
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });
        panel.heightProperty().addListener((obs, ov, nv) -> {
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });

        HBox buttonBox = new HBox(5);
        Button shutdownAllBtn = new Button("전체 종료");
        shutdownAllBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        shutdownAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), "SHUTDOWN"));

        Button wolAllBtn = new Button("전체 켜기(WOL)");
        wolAllBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        wolAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), "WOL"));

        buttonBox.getChildren().addAll(shutdownAllBtn, wolAllBtn);
    panel.getChildren().addAll(header, titleBanner, countStrip, pcGrid, buttonBox);

        makeDraggable(panel, summary, panelDto.getId());
        makeResizable(panel, summary, panelDto.getId());
        updatePanelStyle(panel, summary);
        addZIndexContextMenu(panel);

        return panel;
    }

    private void updateClassroomPanel(VBox panel, ClassroomSummaryDto summary, DashboardPanelDto panelDto) {
        if (panel.getChildren().isEmpty()) {
            return;
        }

        HBox header = (HBox) panel.getChildren().get(0);
        if (!header.getChildren().isEmpty() && header.getChildren().get(0) instanceof Label titleLabel) {
            titleLabel.setText(summary.getGroupName());
        }

        panel.setLayoutX(panelDto.getX() != null ? panelDto.getX() : panel.getLayoutX());
        panel.setLayoutY(panelDto.getY() != null ? panelDto.getY() : panel.getLayoutY());
        panel.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : panel.getPrefWidth());
        panel.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : panel.getPrefHeight());

        Label nameLabel = (Label) panel.getProperties().get("nameLabel");
        if (nameLabel != null) {
            nameLabel.setText(summary.getGroupName());
        }

        Label totalLabel = (Label) panel.getProperties().get("totalLabel");
        Label onlineLabel = (Label) panel.getProperties().get("onlineLabel");
        Label offlineLabel = (Label) panel.getProperties().get("offlineLabel");
        if (totalLabel != null) totalLabel.setText(summary.getTotalPcs() + " 대");
        if (onlineLabel != null) onlineLabel.setText(summary.getOnlinePcs() + " 대");
        if (offlineLabel != null) offlineLabel.setText(Math.max(0, summary.getTotalPcs() - summary.getOnlinePcs()) + " 대");

        GridPane pcGrid = (GridPane) panel.getProperties().get("pcGrid");
        if (pcGrid != null) {
            renderPcGrid(summary.getTotalPcs(), pcCacheByGroupId.get(summary.getGroupId()), pcGrid, panel);
            fetchPcStatuses(summary.getGroupId(), summary.getTotalPcs(), pcGrid);
        }

        updatePanelStyle(panel, summary);

        Tooltip tooltip = (Tooltip) panel.getProperties().get("customTooltip");
        if (tooltip != null) {
            tooltip.setText(buildClassroomTooltipText(summary));
        }
    }

    private void updatePanelStyle(VBox panel, ClassroomSummaryDto summary) {
        String baseStyle = "-fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.10), 10, 0, 0, 0);";
        panel.setStyle(baseStyle + " -fx-background-color: #d9d9d9;");

        Node bannerNode = (Node) panel.getProperties().get("titleBanner");
        if (bannerNode instanceof StackPane banner) {
            boolean teacherOnline = "ONLINE".equalsIgnoreCase(summary.getTeacherPcStatus());
            banner.setStyle("-fx-background-color: " + (teacherOnline ? "#4d8a3a" : "#8f949b") + "; -fx-background-radius: 8;");
        }
    }

    private void fetchPcStatuses(Long groupId, int totalCount, GridPane pcGrid) {
        if (groupId == null || pcGrid == null) {
            return;
        }
        apiClient.getPagedList("/dashboard/classrooms/" + groupId + "/pcs", new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(pcs -> Platform.runLater(() -> {
                    List<PcStatusDto> ordered = pcs.stream()
                            .sorted(Comparator.comparing(PcStatusDto::getHostname, Comparator.nullsLast(String::compareToIgnoreCase)))
                            .toList();
                    pcCacheByGroupId.put(groupId, ordered);
                    Node parent = pcGrid.getParent();
                    VBox panel = parent instanceof VBox v ? v : null;
                    renderPcGrid(totalCount, ordered, pcGrid, panel);
                }))
                .exceptionally(ex -> null);
    }

    private void renderPcGrid(int totalCount, List<PcStatusDto> pcs, GridPane pcGrid, VBox panel) {
        pcGrid.getChildren().clear();
        int count = Math.max(0, totalCount);
        List<PcStatusDto> list = pcs == null ? List.of() : pcs;

        double gap = 6.0;
        double panelW = panel != null ? panel.getWidth() : 0;
        if (panelW <= 0 && panel != null) panelW = panel.getPrefWidth();
        if (panelW <= 0) panelW = 420;

        double panelH = panel != null ? panel.getHeight() : 0;
        if (panelH <= 0 && panel != null) panelH = panel.getPrefHeight();
        if (panelH <= 0) panelH = 320;

        double availableW = Math.max(120, panelW - 32);
        double availableH = Math.max(90, panelH - 260);

        int maxCols = Math.max(1, Math.min(count, 20));
        int bestCols = 1;
        double bestCell = 0;
        for (int cols = 1; cols <= maxCols; cols++) {
            int rows = (int) Math.ceil((double) count / cols);
            double cellW = (availableW - (cols - 1) * gap) / cols;
            double cellH = (availableH - (rows - 1) * gap) / rows;
            double cell = Math.min(cellW, cellH);
            if (cell > bestCell) {
                bestCell = cell;
                bestCols = cols;
            }
        }

        int columns = Math.max(1, bestCols);
        int rows = (int) Math.ceil((double) Math.max(1, count) / columns);
        double cellW = (availableW - (columns - 1) * gap) / columns;
        double cellH = (availableH - (rows - 1) * gap) / rows;
        double cellSize = Math.max(16, Math.min(56, Math.min(cellW, cellH)));
        int fontSize = (int) Math.max(10, Math.min(22, cellSize * 0.34));

        for (int i = 0; i < count; i++) {
            PcStatusDto pc = i < list.size() ? list.get(i) : null;
            String status = pc != null && pc.getStatus() != null ? pc.getStatus().toUpperCase() : "OFFLINE";
            String bg = switch (status) {
                case "ONLINE" -> "#4d8a3a";
                case "BROKEN", "FAILED" -> "#cf404a";
                default -> "#a5a7ab";
            };

            Label cell = new Label(String.valueOf(i + 1));
            cell.setMinSize(cellSize, cellSize);
            cell.setPrefSize(cellSize, cellSize);
            cell.setAlignment(Pos.CENTER);
            cell.setTextFill(Color.web("#f3f4f6"));
            cell.setFont(Font.font("Segoe UI", FontWeight.BOLD, fontSize));
            cell.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 4;");
            pcGrid.add(cell, i % columns, i / columns);
        }
    }

    private void showClassroomDetailModal(ClassroomSummaryDto summary) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("강의실 상세 정보");
        alert.setHeaderText(summary.getGroupName());
        alert.setContentText("상세 정보\n총 PC: " + summary.getTotalPcs() + "대\n켜짐: " + summary.getOnlinePcs() + "대");
        alert.showAndWait();
    }

    private String buildClassroomTooltipText(ClassroomSummaryDto summary) {
        return summary.getGroupName() + " - " + summary.getOnlinePcs() + "/" + summary.getTotalPcs();
    }

    // ─── 드래그 / 리사이즈 ────────────────────────────────────────────────

    private void makeDraggable(VBox panel, ClassroomSummaryDto summary, Long dashboardPanelId) {
        dashboardInteractionService.attachDragHandlers(
                panel,
                editModeSupplier,
                this::applySnap,
                () -> {
            panel.setOpacity(1.0);
            updateDashboardPanelPosition(dashboardPanelId,
                    panel.getLayoutX(), panel.getLayoutY(),
                    panel.getPrefWidth(), panel.getPrefHeight(), true);
                }
        );
    }

    private void makeResizable(VBox panel, ClassroomSummaryDto summary, Long dashboardPanelId) {
        Label resizeHandle = new Label("◢");
        resizeHandle.setTextFill(Color.GRAY);
        resizeHandle.setCursor(Cursor.SE_RESIZE);
        resizeHandle.setAlignment(Pos.BOTTOM_RIGHT);

        HBox handleBox = new HBox(resizeHandle);
        handleBox.setAlignment(Pos.BOTTOM_RIGHT);
        panel.getChildren().add(handleBox);

        dashboardInteractionService.attachResizeHandlers(
                panel,
                resizeHandle,
                200,
                200,
                editModeSupplier,
                this::applySnap,
                () -> {
            updateDashboardPanelPosition(dashboardPanelId,
                    panel.getLayoutX(), panel.getLayoutY(),
                    panel.getPrefWidth(), panel.getPrefHeight(), true);
                }
        );
    }

    private void updateDashboardPanelPosition(Long dashboardPanelId, double x, double y, double width, double height, boolean visible) {
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId <= 0 || dashboardPanelId == null) {
            return;
        }
        apiClient.putJson("/dashboards/" + dashboardId + "/panels/" + dashboardPanelId, new LinkedHashMap<>() {{
            put("x", x);
            put("y", y);
            put("width", width);
            put("height", height);
            put("visible", visible);
        }}).thenAccept(resp -> Platform.runLater(refreshPanelCallback)).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "패널 위치 저장 실패: " + cause.getMessage()).show());
            return null;
        });
    }

    private double applySnap(double value) {
        if (snapModeSupplier == null || !snapModeSupplier.getAsBoolean()) {
            return value;
        }
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    // ─── 컨텍스트 메뉴 ───────────────────────────────────────────────────

    private void addZIndexContextMenu(Node node) {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem toFrontItem = new MenuItem("맨 앞으로 가져오기");
        toFrontItem.setOnAction(e -> node.toFront());
        MenuItem toBackItem = new MenuItem("맨 뒤로 보내기");
        toBackItem.setOnAction(e -> node.toBack());
        contextMenu.getItems().addAll(toFrontItem, toBackItem);
        node.setOnContextMenuRequested(e -> contextMenu.show(node, e.getScreenX(), e.getScreenY()));
    }

    // ─── PC 목록 / 명령 ──────────────────────────────────────────────────

    private void fetchAndDisplayPcs(Long groupId,
                                     Consumer<List<PcStatusDto>> uiUpdater,
                                     Consumer<String> placeholderUpdater) {
        placeholderUpdater.accept("로딩 중...");
        apiClient.getPagedList("/dashboard/classrooms/" + groupId + "/pcs", new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(pcs -> Platform.runLater(() -> {
                    uiUpdater.accept(pcs);
                    if (pcs.isEmpty()) {
                        placeholderUpdater.accept("이 강의실에 등록된 PC가 없습니다.");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> placeholderUpdater.accept("로드 실패"));
                    return null;
                });
    }

    private void sendGroupCommand(Long groupId, String type) {
        apiClient.postJson("/commands", java.util.Map.of(
                "scope", "GROUP",
                "groupId", groupId,
                "type", type,
                "role", "ALL"
        ))
                .thenAccept(response -> {
                    log.info("Group command sent successfully: {} for groupId={}", type, groupId);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("Failed to send group command {} for groupId={}", type, groupId, cause);
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            type + " 명령 전송 실패: " + cause.getMessage()).show());
                    return null;
                });
    }
}
