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
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
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
        nameLabel.setFont(Font.font("Arial", FontWeight.BOLD, 16));

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button deleteBtn = new Button("삭제");
        deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        deleteBtn.setOnAction(e -> {
            e.consume();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "이 강의실 패널을 대시보드에서 제거하시겠습니까?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(type -> {
                if (type == ButtonType.YES) {
                    deleteClassroomPanel(panelDto.getId());
                }
            });
        });
        HBox header = new HBox(8, nameLabel, headerSpacer, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        Label ipLabel = new Label(summary.getIpRanges() != null && !summary.getIpRanges().isEmpty()
                ? String.join(", ", summary.getIpRanges()) : "IP 대역 미설정");
        ipLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");

        HBox statusBox = new HBox(10);
        Label onlineLabel = new Label(String.format("ON: %d / %d",
                summary.getOnlinePcs(), summary.getTotalPcs()));
        onlineLabel.setTextFill(Color.web("#2ecc71"));
        statusBox.getChildren().add(onlineLabel);

        Label teacherStatus = new Label("교사: " + summary.getTeacherPcStatus());
        teacherStatus.setTextFill("ONLINE".equals(summary.getTeacherPcStatus()) ? Color.BLUE : Color.GRAY);

        ListView<PcStatusDto> pcListView = new ListView<>();
        VBox.setVgrow(pcListView, Priority.ALWAYS);
        pcListView.setPrefHeight(150);
        pcListView.setPlaceholder(new Label("클릭하여 PC 목록 보기"));
        pcListView.setCellFactory(lv -> new ListCell<>() {
            private final Circle statusCircle = new Circle(5);
            private final Label pcInfoLabel = new Label();
            private final HBox content = new HBox(5, statusCircle, pcInfoLabel);
            { content.setAlignment(Pos.CENTER_LEFT); }

            @Override
            protected void updateItem(PcStatusDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    statusCircle.setFill("ONLINE".equalsIgnoreCase(item.getStatus())
                            ? Color.LIMEGREEN : Color.LIGHTGRAY);
                    pcInfoLabel.setText(String.format("%s (%s)", item.getHostname(), item.getIpAddress()));
                    setGraphic(content);
                }
            }
        });

        panel.setOnMouseClicked(e -> {
            if (!editModeSupplier.getAsBoolean()) {
                if (e.getClickCount() == 1) {
                    if (pcListView.getItems().isEmpty()) {
                        fetchAndDisplayPcs(summary.getGroupId(),
                                pcs -> pcListView.getItems().setAll(pcs),
                                text -> pcListView.setPlaceholder(new Label(text)));
                    }
                } else if (e.getClickCount() == 2) {
                    showClassroomDetailModal(summary);
                }
            }
        });

        HBox buttonBox = new HBox(5);
        Button shutdownAllBtn = new Button("전체 종료");
        shutdownAllBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        shutdownAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), "SHUTDOWN"));

        Button wolAllBtn = new Button("전체 켜기(WOL)");
        wolAllBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        wolAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), "WOL"));

        buttonBox.getChildren().addAll(shutdownAllBtn, wolAllBtn);
        panel.getChildren().addAll(header, ipLabel, statusBox, teacherStatus, pcListView, buttonBox);

        makeDraggable(panel, summary, panelDto.getId());
        makeResizable(panel, summary, panelDto.getId());
        updatePanelStyle(panel, summary);
        addZIndexContextMenu(panel);

        return panel;
    }

    private void updateClassroomPanel(VBox panel, ClassroomSummaryDto summary, DashboardPanelDto panelDto) {
        if (panel.getChildren().size() < 4) return;

        HBox header = (HBox) panel.getChildren().get(0);
        if (!header.getChildren().isEmpty() && header.getChildren().get(0) instanceof Label titleLabel) {
            titleLabel.setText(summary.getGroupName());
        }

        panel.setLayoutX(panelDto.getX() != null ? panelDto.getX() : panel.getLayoutX());
        panel.setLayoutY(panelDto.getY() != null ? panelDto.getY() : panel.getLayoutY());
        panel.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : panel.getPrefWidth());
        panel.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : panel.getPrefHeight());

        Label ipLabel = (Label) panel.getChildren().get(1);
        ipLabel.setText(summary.getIpRanges() != null && !summary.getIpRanges().isEmpty()
                ? String.join(", ", summary.getIpRanges()) : "IP 대역 미설정");

        HBox statusBox = (HBox) panel.getChildren().get(2);
        if (!statusBox.getChildren().isEmpty()) {
            Label onlineLabel = (Label) statusBox.getChildren().get(0);
            onlineLabel.setText(String.format("ON: %d / %d",
                    summary.getOnlinePcs(), summary.getTotalPcs()));
        }

        Label teacherLabel = (Label) panel.getChildren().get(3);
        teacherLabel.setText("교사: " + summary.getTeacherPcStatus());
        teacherLabel.setTextFill("ONLINE".equals(summary.getTeacherPcStatus()) ? Color.BLUE : Color.GRAY);

        updatePanelStyle(panel, summary);

        Tooltip tooltip = (Tooltip) panel.getProperties().get("customTooltip");
        if (tooltip != null) {
            tooltip.setText(buildClassroomTooltipText(summary));
        }
    }

    private void updatePanelStyle(VBox panel, ClassroomSummaryDto summary) {
        String baseStyle = "-fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 10, 0, 0, 0);";
        String bgColor = "white";

        boolean teacherOnline = "ONLINE".equalsIgnoreCase(summary.getTeacherPcStatus());
        boolean allOnline = summary.getTotalPcs() > 0 && summary.getOnlinePcs() == summary.getTotalPcs();
        boolean someOffline = summary.getOnlinePcs() < summary.getTotalPcs();
        boolean allOffline = summary.getOnlinePcs() == 0;

        if (allOffline) {
            bgColor = "#f8f9fa";
        } else if (!teacherOnline) {
            bgColor = "#fff3cd";
        } else if (someOffline) {
            bgColor = "#e8f0fe";
        } else if (allOnline) {
            bgColor = "#d4edda";
        }
        panel.setStyle(baseStyle + " -fx-background-color: " + bgColor + ";");
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
        }}).exceptionally(ex -> {
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
