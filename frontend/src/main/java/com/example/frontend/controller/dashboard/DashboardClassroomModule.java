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
    private static final double LAYOUT_EPSILON = 0.5;

    private final ApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Pane classroomContainer;
    private final DashboardPanelManager panelManager;
    private final BooleanSupplier editModeSupplier;
    private final Runnable refreshCallback;
    private final LongSupplier dashboardIdSupplier;
    private final Runnable refreshPanelCallback;
    private final PanelLayoutCacheUpdater panelLayoutCacheUpdater;
    private final BooleanSupplier snapModeSupplier;
    private final DashboardInteractionService dashboardInteractionService = new DashboardInteractionService();
    private final Map<Long, Button> classroomDeleteBtns = new HashMap<>();
    private final Map<Long, Button> gridSettingsBtns = new HashMap<>();
    private final Map<Long, Node> resizeHandleNodes = new HashMap<>();
    private final Map<Long, List<PcStatusDto>> pcCacheByGroupId = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean skipGroupCommandConfirm = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final double GRID_SIZE = 20.0;
    private static final String ORDER_BY_PC_NAME = "PC_NAME";
    private static final String ORDER_BY_IP = "IP";

    @FunctionalInterface
    public interface PanelLayoutCacheUpdater {
        void update(Long panelId, double x, double y, double width, double height);
    }

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
            PanelLayoutCacheUpdater panelLayoutCacheUpdater,
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
        this.panelLayoutCacheUpdater = panelLayoutCacheUpdater;
        this.snapModeSupplier = snapModeSupplier;

        // DB에서 skipGroupCommandConfirm 초기값 로드
        apiClient.getJson("/settings/skipGroupCommandConfirm", new TypeReference<Map<String, Object>>() {})
                .thenAccept(resp -> {
                    if ("true".equalsIgnoreCase(String.valueOf(resp.getOrDefault("value", "")))) {
                            skipGroupCommandConfirm.set(true);
                    }
                })
                .exceptionally(ex -> null);
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
                gridSettingsBtns.remove(panelId);
                resizeHandleNodes.remove(panelId);
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

        TextInputDialog nameDialog = new TextInputDialog();
        nameDialog.setTitle("패널 이름 설정");
        nameDialog.setHeaderText("강의실 [" + group.getGroupName() + "] 패널의 표시 이름을 입력하세요.");
        nameDialog.setContentText("이름 (비워두면 강의실명 사용):");
        java.util.Optional<String> nameResult = nameDialog.showAndWait();
        if (nameResult.isEmpty()) return; // 취소

        String panelName = nameResult.get().trim();

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("panelType", "CLASSROOM_HEARTBEAT");
        payload.put("refGroupId", group.getGroupId());
        payload.put("x", x);
        payload.put("y", y);
        payload.put("width", width);
        payload.put("height", height);
        payload.put("visible", true);
        payload.put("zIndex", 0);
        if (!panelName.isEmpty()) payload.put("panelName", panelName);

        apiClient.postJson("/dashboards/" + dashboardId + "/panels", payload)
                .thenAccept(resp -> Platform.runLater(refreshPanelCallback))
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
        gridSettingsBtns.values().forEach(btn -> {
            btn.setVisible(editMode);
            btn.setManaged(editMode);
        });
        resizeHandleNodes.values().forEach(node -> {
            node.setVisible(editMode);
            node.setManaged(editMode);
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
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(18, 16, 16, 16));
        panel.getProperties().put("dashboardPanelId", panelDto.getId());
        panel.getProperties().put("groupId", summary.getGroupId());
        panel.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 20.0);
        panel.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 20.0);
        panel.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : 360.0);
        panel.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : 280.0);

        Label nameLabel = new Label(summary.getGroupName());
        nameLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
        nameLabel.setTextFill(Color.web("#e5e7eb"));
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        StackPane titleBanner = new StackPane(nameLabel);
        titleBanner.setPadding(new Insets(12, 10, 12, 10));
        VBox.setVgrow(titleBanner, Priority.NEVER);
        titleBanner.setStyle("-fx-background-color: #1f7a42; -fx-background-radius: 10;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button deleteBtn = new Button("삭제");
        deleteBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
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
        Button gridSettingsBtn = new Button("⚙");
        gridSettingsBtn.setStyle("-fx-background-color: #718096; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        gridSettingsBtn.setVisible(editModeSupplier.getAsBoolean());
        gridSettingsBtn.setManaged(editModeSupplier.getAsBoolean());
        gridSettingsBtn.setOnAction(e -> {
            e.consume();
            GridPane pg = (GridPane) panel.getProperties().get("pcGrid");
            showGridSettingsDialog(panel, summary.getGroupId(), summary.getTotalPcs(), pg);
        });
        gridSettingsBtns.put(panelDto.getId(), gridSettingsBtn);
        HBox header = new HBox(4, headerSpacer, gridSettingsBtn, deleteBtn);
        header.setAlignment(Pos.CENTER_RIGHT);

        Label totalLabel = new Label(summary.getTotalPcs() + " 대");
        totalLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        totalLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane totalBox = new StackPane(totalLabel);
        totalBox.setPadding(new Insets(9, 8, 9, 8));
        totalBox.setStyle("-fx-background-color: #64748b; -fx-background-radius: 6;");
        HBox.setHgrow(totalBox, Priority.ALWAYS);

        Label onlineLabel = new Label(summary.getOnlinePcs() + " 대");
        onlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        onlineLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane onlineBox = new StackPane(onlineLabel);
        onlineBox.setPadding(new Insets(9, 8, 9, 8));
        onlineBox.setStyle("-fx-background-color: #15803d; -fx-background-radius: 6;");
        HBox.setHgrow(onlineBox, Priority.ALWAYS);

        int offlineCount = Math.max(0, summary.getTotalPcs() - summary.getOnlinePcs());
        Label offlineLabel = new Label(offlineCount + " 대");
        offlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        offlineLabel.setTextFill(Color.web("#f3f4f6"));
        StackPane offlineBox = new StackPane(offlineLabel);
        offlineBox.setPadding(new Insets(9, 8, 9, 8));
        offlineBox.setStyle("-fx-background-color: #b4535f; -fx-background-radius: 6;");
        HBox.setHgrow(offlineBox, Priority.ALWAYS);

        HBox countStrip = new HBox(4, totalBox, onlineBox, offlineBox);

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
        panel.getProperties().put("gridMinCell", 16);
        panel.getProperties().put("gridMaxCell", 56);
        panel.getProperties().put("gridMaxCols", 20);

        // configJson에서 gridOrderMode 복원 (DB 영속)
        String savedOrderMode = ORDER_BY_PC_NAME;
        String baseConfigJson = "{}";
        try {
            String cfgJson = panelDto.getConfigJson();
            if (cfgJson != null && !cfgJson.isBlank()) {
                baseConfigJson = cfgJson;
                Map<String, Object> cfg = objectMapper.readValue(cfgJson, new TypeReference<Map<String, Object>>() {});
                Object mode = cfg.get("gridOrderMode");
                if (mode != null && !mode.toString().isBlank()) savedOrderMode = mode.toString();
            }
        } catch (Exception e) {
            log.debug("패널 configJson 파싱 실패", e);
        }
        panel.getProperties().put("gridOrderMode", savedOrderMode);
        panel.getProperties().put("configJsonBase", baseConfigJson);

        renderPcGrid(summary.getTotalPcs(), pcCacheByGroupId.get(summary.getGroupId()), pcGrid, panel);
        fetchPcStatuses(summary.getGroupId(), summary.getTotalPcs(), pcGrid);

        panel.widthProperty().addListener((obs, ov, nv) -> {
            double w = nv.doubleValue();
            nameLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, (int) Math.max(12, Math.min(36, w * 0.09))));
            int cntFs = (int) Math.max(10, Math.min(22, w * 0.05));
            totalLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, cntFs));
            onlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, cntFs));
            offlineLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, cntFs));
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });
        panel.heightProperty().addListener((obs, ov, nv) -> {
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });

        HBox buttonBox = new HBox(5);
        Button shutdownAllBtn = new Button("전체 종료");
        shutdownAllBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white;");
        shutdownAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), summary.getGroupName(), "SHUTDOWN"));

        Button wolAllBtn = new Button("전체 켜기(WOL)");
        wolAllBtn.setStyle("-fx-background-color: #3182ce; -fx-text-fill: white;");
        wolAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), summary.getGroupName(), "WOL"));

        buttonBox.getChildren().addAll(shutdownAllBtn, wolAllBtn);
        panel.getChildren().addAll(header, titleBanner);

        // 사용자 지정 패널 이름이 있으면 부제목으로 표시
        String customPanelName = panelDto.getPanelName();
        if (customPanelName != null && !customPanelName.isBlank()) {
            Label panelNameLabel = new Label(customPanelName);
            panelNameLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
            panelNameLabel.setTextFill(Color.web("#94a3b8"));
            panelNameLabel.setMaxWidth(Double.MAX_VALUE);
            panelNameLabel.setStyle("-fx-padding: 2 8 0 8;");
            panel.getProperties().put("panelNameLabel", panelNameLabel);
            panel.getChildren().add(panelNameLabel);
        }

        panel.getChildren().addAll(countStrip, pcGrid, buttonBox);

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
        if (panelDto.getX() != null) {
            applyIfChanged(panel.getLayoutX(), panelDto.getX(), panel::setLayoutX);
        }
        if (panelDto.getY() != null) {
            applyIfChanged(panel.getLayoutY(), panelDto.getY(), panel::setLayoutY);
        }
        if (panelDto.getWidth() != null) {
            applyIfChanged(panel.getPrefWidth(), panelDto.getWidth(), panel::setPrefWidth);
        }
        if (panelDto.getHeight() != null) {
            applyIfChanged(panel.getPrefHeight(), panelDto.getHeight(), panel::setPrefHeight);
        }

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

    private void applyIfChanged(double current, double next, java.util.function.DoubleConsumer setter) {
        if (Math.abs(current - next) > LAYOUT_EPSILON) {
            setter.accept(next);
        }
    }

    private void updatePanelStyle(VBox panel, ClassroomSummaryDto summary) {
        String baseStyle = "-fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.12), 16, 0, 0, 4); -fx-border-color: #d8dee8; -fx-border-radius: 12;";
        panel.setStyle(baseStyle + " -fx-background-color: #f8fafc;");

        Node bannerNode = (Node) panel.getProperties().get("titleBanner");
        if (bannerNode instanceof StackPane banner) {
            boolean teacherOnline = "ONLINE".equalsIgnoreCase(summary.getTeacherPcStatus());
            banner.setStyle("-fx-background-color: " + (teacherOnline ? "#1f7a42" : "#55657c") + "; -fx-background-radius: 10;");
        }
    }

    private void fetchPcStatuses(Long groupId, int totalCount, GridPane pcGrid) {
        if (groupId == null || pcGrid == null) {
            return;
        }
        apiClient.getPagedList("/dashboard/classrooms/" + groupId + "/pcs", new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(pcs -> Platform.runLater(() -> {
                pcCacheByGroupId.put(groupId, pcs);
                    Node parent = pcGrid.getParent();
                    VBox panel = parent instanceof VBox v ? v : null;
                renderPcGrid(totalCount, pcs, pcGrid, panel);
                }))
                .exceptionally(ex -> null);
    }

    private void renderPcGrid(int totalCount, List<PcStatusDto> pcs, GridPane pcGrid, VBox panel) {
        pcGrid.getChildren().clear();
        int count = Math.max(0, totalCount);
        List<PcStatusDto> list = pcs == null ? List.of() : pcs;
        String orderMode = panel != null
            ? (String) panel.getProperties().getOrDefault("gridOrderMode", ORDER_BY_PC_NAME)
            : ORDER_BY_PC_NAME;
        list = sortPcList(list, orderMode);

        int minCell = panel != null ? (int) panel.getProperties().getOrDefault("gridMinCell", 16) : 16;
        int maxCell = panel != null ? (int) panel.getProperties().getOrDefault("gridMaxCell", 56) : 56;
        int maxColsLimit = panel != null ? (int) panel.getProperties().getOrDefault("gridMaxCols", 20) : 20;

        double gap = 6.0;
        double panelW = panel != null ? panel.getWidth() : 0;
        if (panelW <= 0 && panel != null) panelW = panel.getPrefWidth();
        if (panelW <= 0) panelW = 420;

        double panelH = panel != null ? panel.getHeight() : 0;
        if (panelH <= 0 && panel != null) panelH = panel.getPrefHeight();
        if (panelH <= 0) panelH = 320;

        double availableW = Math.max(120, panelW - 32);
        double availableH = Math.max(90, panelH - 260);

        int maxCols = Math.max(1, Math.min(count, maxColsLimit));
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
        double cellSize = Math.max(minCell, Math.min(maxCell, Math.min(cellW, cellH)));
        int fontSize = (int) Math.max(10, Math.min(22, cellSize * 0.34));

        for (int i = 0; i < count; i++) {
            PcStatusDto pc = i < list.size() ? list.get(i) : null;
            String status = pc != null && pc.getStatus() != null ? pc.getStatus().toUpperCase() : "OFFLINE";
            String bg = switch (status) {
                case "ONLINE" -> "#2f855a";
                case "BROKEN", "FAILED" -> "#c95f6a";
                default -> "#94a3b8";
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

    private void showGridSettingsDialog(VBox panel, Long groupId, int totalCount, GridPane pcGrid) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("PC 그리드 설정");
        dialog.setHeaderText(null);

        int curMin = (int) panel.getProperties().getOrDefault("gridMinCell", 16);
        int curMax = (int) panel.getProperties().getOrDefault("gridMaxCell", 56);
        int curCols = (int) panel.getProperties().getOrDefault("gridMaxCols", 20);
        String curOrderMode = (String) panel.getProperties().getOrDefault("gridOrderMode", ORDER_BY_PC_NAME);

        Spinner<Integer> minSpinner = new Spinner<>(8, 80, curMin, 2);
        minSpinner.setEditable(true);
        minSpinner.setPrefWidth(85);
        Spinner<Integer> maxSpinner = new Spinner<>(16, 120, curMax, 2);
        maxSpinner.setEditable(true);
        maxSpinner.setPrefWidth(85);
        Spinner<Integer> colsSpinner = new Spinner<>(1, 50, curCols, 1);
        colsSpinner.setEditable(true);
        colsSpinner.setPrefWidth(85);
        ComboBox<String> orderCombo = new ComboBox<>();
        orderCombo.getItems().addAll("PC 이름 순", "IP 순");
        orderCombo.setValue(ORDER_BY_IP.equals(curOrderMode) ? "IP 순" : "PC 이름 순");
        orderCombo.setPrefWidth(120);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.setPadding(new Insets(12));
        form.add(new Label("셀 최소 크기 (px):"), 0, 0);
        form.add(minSpinner, 1, 0);
        form.add(new Label("셀 최대 크기 (px):"), 0, 1);
        form.add(maxSpinner, 1, 1);
        form.add(new Label("최대 열 수:"), 0, 2);
        form.add(colsSpinner, 1, 2);
        form.add(new Label("순번 정렬:"), 0, 3);
        form.add(orderCombo, 1, 3);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                panel.getProperties().put("gridMinCell", minSpinner.getValue());
                panel.getProperties().put("gridMaxCell", maxSpinner.getValue());
                panel.getProperties().put("gridMaxCols", colsSpinner.getValue());
                String newOrderMode = "IP 순".equals(orderCombo.getValue()) ? ORDER_BY_IP : ORDER_BY_PC_NAME;
                panel.getProperties().put("gridOrderMode", newOrderMode);

                // gridOrderMode를 dashboard_panels.config_json 에 영속
                Long panelId = (Long) panel.getProperties().get("dashboardPanelId");
                long dashId = dashboardIdSupplier.getAsLong();
                if (panelId != null && dashId > 0) {
                    try {
                        String base = (String) panel.getProperties().getOrDefault("configJsonBase", "{}");
                        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
                        if (base != null && !base.isBlank()) {
                            cfg = objectMapper.readValue(base, new TypeReference<Map<String, Object>>() {});
                        }
                        cfg.put("gridOrderMode", newOrderMode);
                        String newJson = objectMapper.writeValueAsString(cfg);
                        panel.getProperties().put("configJsonBase", newJson);
                           Map<String, Object> payload = new java.util.LinkedHashMap<>();
                           payload.put("configJson", newJson);
                           apiClient.putJson("/dashboards/" + dashId + "/panels/" + panelId, payload)
                                .exceptionally(ex -> null);
                    } catch (Exception e) {
                        log.debug("정렬 설정 저장 실패", e);
                    }
                }

                renderPcGrid(totalCount, pcCacheByGroupId.get(groupId), pcGrid, panel);
            }
        });
    }

    private List<PcStatusDto> sortPcList(List<PcStatusDto> source, String orderMode) {
        Comparator<PcStatusDto> byName = Comparator.comparing(
                dto -> dto.getHostname() == null ? "" : dto.getHostname(),
                String.CASE_INSENSITIVE_ORDER);

        Comparator<PcStatusDto> byIp = Comparator
                .comparingLong((PcStatusDto dto) -> ipToSortableLong(dto.getIpAddress()))
                .thenComparing(dto -> dto.getHostname() == null ? "" : dto.getHostname(), String.CASE_INSENSITIVE_ORDER);

        return source.stream()
                .sorted(ORDER_BY_IP.equals(orderMode) ? byIp : byName)
                .toList();
    }

    private long ipToSortableLong(String ip) {
        if (ip == null || ip.isBlank()) {
            return Long.MAX_VALUE;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return Long.MAX_VALUE;
        }
        long result = 0;
        for (String p : parts) {
            int octet;
            try {
                octet = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
            if (octet < 0 || octet > 255) {
                return Long.MAX_VALUE;
            }
            result = (result << 8) | octet;
        }
        return result;
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
        handleBox.setVisible(editModeSupplier.getAsBoolean());
        handleBox.setManaged(editModeSupplier.getAsBoolean());
        resizeHandleNodes.put(dashboardPanelId, handleBox);
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
        if (panelLayoutCacheUpdater != null) {
            panelLayoutCacheUpdater.update(dashboardPanelId, x, y, width, height);
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

    private void sendGroupCommand(Long groupId, String groupName, String type) {
        if (!confirmGroupCommand(groupName, type)) {
            return;
        }
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

    private boolean confirmGroupCommand(String groupName, String type) {
        if (skipGroupCommandConfirm.get()) {
            return true;
        }

        String label = switch (type) {
            case "SHUTDOWN" -> "전체 종료";
            case "WOL" -> "전체 켜기(WOL)";
            default -> type;
        };

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("전체 명령 확인");
        confirm.setHeaderText("정말 전체 명령을 실행하시겠습니까?");

        Label message = new Label("강의실: " + (groupName == null ? "-" : groupName)
                + "\n명령: " + label
                + "\n\n실수로 실행하면 큰 영향이 있을 수 있습니다.");
        message.setWrapText(true);

        CheckBox skipNext = new CheckBox("다시 묻지 않기");
        VBox content = new VBox(10, message, skipNext);
        content.setPadding(new Insets(8, 4, 4, 4));

        confirm.getDialogPane().setContent(content);
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.CANCEL);

        var result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            skipGroupCommandConfirm.set(skipNext.isSelected());
            if (skipGroupCommandConfirm.get()) {
                // DB에 영속
                apiClient.putJson("/settings/skipGroupCommandConfirm",
                        Map.of("value", "true")).exceptionally(ex -> null);
            }
            return true;
        }
        return false;
    }
}
