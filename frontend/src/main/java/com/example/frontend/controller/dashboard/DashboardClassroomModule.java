package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.ApiClient;
import com.example.frontend.model.panel.PanelInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
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
    /**
     * 강의실 그룹명 변경
     * @param groupId 그룹 ID
     * @param newName 새 이름
     * @param onComplete 완료 콜백
     */
    public void renameGroup(Long groupId, String newName, Runnable onComplete) {
        if (!ensureWritePermission("강의실 이름 변경")) {
            return;
        }
        if (groupId == null || newName == null || newName.isBlank()) return;
        apiClient.putJson("/groups/" + groupId, java.util.Map.of("name", newName))
                .thenAccept(resp -> Platform.runLater(() -> {
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "이름 변경 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                    return null;
                });
    }

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
    private static final String ORDER_BY_PC_NAME = "PC_NAME";
    private static final String ORDER_BY_IP = "IP";

    @FunctionalInterface
    public interface PanelLayoutCacheUpdater {
        void update(Long panelId, double x, double y, double width, double height);
    }

    public DashboardClassroomModule(
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
                .exceptionally(ex -> {
                    log.warn("Failed to load skipGroupCommandConfirm setting", ex);
                    return null;
                });
    }

    private boolean ensureWritePermission(String actionName) {
        if (!apiClient.isViewerRole()) {
            return true;
        }
        Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                ApiClient.viewerWriteDeniedMessage(actionName)).show());
        return false;
    }

    private String resolveErrorMessage(Throwable ex, String fallback) {
        return ApiClient.userFacingErrorMessage(ex, fallback);
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
        if (!ensureWritePermission("강의실 추가")) {
            return;
        }
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
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "강의실 추가 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                        return null;
                    });
        });
    }

    public void addClassroomPanel(ClassroomSummaryDto group, double x, double y, double width, double height) {
        if (!ensureWritePermission("강의실 패널 추가")) {
            return;
        }
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId <= 0) {
            log.warn("Skip addClassroomPanel because dashboardId is not selected");
            return;
        }

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("panelType", "CLASSROOM_HEARTBEAT");
        payload.put("refGroupId", group.getGroupId());
        payload.put("x", x);
        payload.put("y", y);
        payload.put("width", width);
        payload.put("height", height);
        payload.put("visible", true);
        payload.put("zIndex", 0);

        apiClient.postJson("/dashboards/" + dashboardId + "/panels", payload)
                .thenAccept(resp -> Platform.runLater(refreshPanelCallback))
                .exceptionally(ex -> {
                    log.warn("강의실 패널 추가 실패: {}", resolveErrorMessage(ex, "요청을 처리하지 못했습니다."), ex);
                    return null;
                });
    }

    public void deleteClassroomPanel(Long panelId) {
        if (!ensureWritePermission("강의실 패널 삭제")) {
            return;
        }
        long dashboardId = dashboardIdSupplier.getAsLong();
        if (dashboardId <= 0) {
            return;
        }
        apiClient.delete("/dashboards/" + dashboardId + "/panels/" + panelId)
                .thenAccept(resp -> Platform.runLater(refreshPanelCallback))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "강의실 패널 삭제 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).showAndWait());
                    return null;
                });
    }

    public void handleDeleteGroup(ClassroomSummaryDto group) {
        if (!ensureWritePermission("강의실 삭제")) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "강의실 [" + group.getGroupName() + "]을(를) 정말 삭제하시겠습니까?\n연결된 IP 대역 설정도 함께 삭제됩니다.",
                ButtonType.YES, ButtonType.NO);

        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
                apiClient.delete("/groups/" + group.getGroupId())
                        .thenAccept(resp -> Platform.runLater(refreshCallback::run));
            }
        });
    }

    public void toggleGroupVisibility(ClassroomSummaryDto group, boolean isVisible) {
        if (!ensureWritePermission("강의실 표시 변경")) {
            return;
        }
        apiClient.putJson("/groups/" + group.getGroupId(), new LinkedHashMap<>() {{
            put("name", group.getGroupName());
            put("description", "");
            put("visible", isVisible);
        }})
                .thenAccept(resp -> {
                    Platform.runLater(refreshCallback::run);
                })
                .exceptionally(ex -> {
                    log.warn("강의실 표시설정 변경 실패: groupId={}, message={}",
                            group.getGroupId(),
                            resolveErrorMessage(ex, "요청을 처리하지 못했습니다."),
                            ex);
                    return null;
                });
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

    // ─── 패널 내부 빌더 (private) ──────────────────────────────────────────

    private VBox createClassroomPanel(ClassroomSummaryDto summary, DashboardPanelDto panelDto) {
        VBox panel;
        Map<String, Object> namespace;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/classroom-panel-card.fxml"));
            panel = loader.load();
            namespace = loader.getNamespace();
        } catch (Exception e) {
            throw new IllegalStateException("강의실 카드 템플릿 로딩 실패", e);
        }

        panel.getProperties().put("dashboardPanelId", panelDto.getId());
        panel.getProperties().put("groupId", summary.getGroupId());
        panel.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 20.0);
        panel.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 20.0);
        panel.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : 360.0);
        panel.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : 280.0);

        Label nameLabel = (Label) namespace.get("nameLabel");
        StackPane titleBanner = (StackPane) namespace.get("titleBanner");
        Button deleteBtn = (Button) namespace.get("deleteBtn");
        Button gridSettingsBtn = (Button) namespace.get("gridSettingsBtn");
        Label totalLabel = (Label) namespace.get("totalLabel");
        Label onlineLabel = (Label) namespace.get("onlineLabel");
        Label offlineLabel = (Label) namespace.get("offlineLabel");
        GridPane pcGrid = (GridPane) namespace.get("pcGrid");
        Button shutdownAllBtn = (Button) namespace.get("shutdownAllBtn");
        Button wolAllBtn = (Button) namespace.get("wolAllBtn");
        HBox buttonBox = (HBox) namespace.get("buttonBox");
        StackPane panelNameSlot = (StackPane) namespace.get("panelNameSlot");
        HBox resizeHandleBox = (HBox) namespace.get("resizeHandleBox");
        Label resizeHandleLabel = (Label) namespace.get("resizeHandleLabel");

        nameLabel.setText(summary.getGroupName());
        nameLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
        nameLabel.setTextFill(Color.WHITE);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        deleteBtn.getStyleClass().add("btn-sm");
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
        gridSettingsBtn.getStyleClass().add("btn-sm");
        gridSettingsBtn.setVisible(editModeSupplier.getAsBoolean());
        gridSettingsBtn.setManaged(editModeSupplier.getAsBoolean());
        gridSettingsBtn.setOnAction(e -> {
            e.consume();
            GridPane pg = (GridPane) panel.getProperties().get("pcGrid");
            showGridSettingsDialog(panel, summary.getGroupId(), summary.getTotalPcs(), pg);
        });
        gridSettingsBtns.put(panelDto.getId(), gridSettingsBtn);

        totalLabel.setText(summary.getTotalPcs() + " 대");
        totalLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 16));
        totalLabel.setTextFill(Color.WHITE);

        onlineLabel.setText(summary.getOnlinePcs() + " 대");
        onlineLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 16));
        onlineLabel.setTextFill(Color.WHITE);

        int offlineCount = Math.max(0, summary.getTotalPcs() - summary.getOnlinePcs());
        offlineLabel.setText(offlineCount + " 대");
        offlineLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 16));
        offlineLabel.setTextFill(Color.WHITE);

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
        panel.getProperties().put("resizeHandleBox", resizeHandleBox);
        panel.getProperties().put("resizeHandleLabel", resizeHandleLabel);

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
            totalLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, cntFs));
            onlineLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, cntFs));
            offlineLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, cntFs));
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });
        panel.heightProperty().addListener((obs, ov, nv) -> {
            List<PcStatusDto> cached = pcCacheByGroupId.get(summary.getGroupId());
            renderPcGrid(summary.getTotalPcs(), cached, pcGrid, panel);
        });

        shutdownAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), summary.getGroupName(), "SHUTDOWN"));

        wolAllBtn.setOnAction(e -> sendGroupCommand(summary.getGroupId(), summary.getGroupName(), "WOL"));

        if (apiClient.isViewerRole()) {
            shutdownAllBtn.setManaged(false);
            shutdownAllBtn.setVisible(false);
            wolAllBtn.setManaged(false);
            wolAllBtn.setVisible(false);
            if (buttonBox != null) {
                Label readOnlyLabel = new Label("읽기 전용");
                readOnlyLabel.getStyleClass().add("viewer-readonly-badge");
                buttonBox.getChildren().setAll(readOnlyLabel);
            }
        }

        // 사용자 지정 패널 이름이 있으면 부제목으로 표시
        String customPanelName = panelDto.getPanelName();
        if (panelNameSlot != null && customPanelName != null && !customPanelName.isBlank()) {
            Label panelNameLabel = new Label(customPanelName);
            panelNameLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
            panelNameLabel.setTextFill(Color.WHITE);
            panelNameLabel.setMaxWidth(Double.MAX_VALUE);
            panelNameLabel.getStyleClass().addAll("classroom-stat-label", "classroom-stat-total");
            panelNameLabel.setPadding(new Insets(4, 10, 4, 10));
            panel.getProperties().put("panelNameLabel", panelNameLabel);
            panelNameSlot.getChildren().setAll(panelNameLabel);
            panelNameSlot.setManaged(true);
            panelNameSlot.setVisible(true);
        }

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
            nameLabel.setTextFill(Color.WHITE);
        }

        Label totalLabel = (Label) panel.getProperties().get("totalLabel");
        Label onlineLabel = (Label) panel.getProperties().get("onlineLabel");
        Label offlineLabel = (Label) panel.getProperties().get("offlineLabel");
        if (totalLabel != null) {
            totalLabel.setText(summary.getTotalPcs() + " 대");
            totalLabel.setTextFill(Color.WHITE);
        }
        if (onlineLabel != null) {
            onlineLabel.setText(summary.getOnlinePcs() + " 대");
            onlineLabel.setTextFill(Color.WHITE);
        }
        if (offlineLabel != null) {
            offlineLabel.setText(Math.max(0, summary.getTotalPcs() - summary.getOnlinePcs()) + " 대");
            offlineLabel.setTextFill(Color.WHITE);
        }

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
        if (!panel.getStyleClass().contains("classroom-panel-bg")) {
            panel.getStyleClass().add("classroom-panel-bg");
        }

        Node bannerNode = (Node) panel.getProperties().get("titleBanner");
        if (bannerNode instanceof StackPane banner) {
            boolean teacherOnline = "ONLINE".equalsIgnoreCase(summary.getTeacherPcStatus());
            banner.getStyleClass().removeAll("classroom-title-banner", "classroom-title-banner-offline");
            banner.getStyleClass().add(teacherOnline ? "classroom-title-banner" : "classroom-title-banner-offline");
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
                .exceptionally(ex -> {
                    log.warn("Failed to fetch PC statuses", ex);
                    return null;
                });
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
            String cellStyleClass = switch (status) {
                case "ONLINE" -> "pc-cell-online";
                case "BROKEN", "FAILED" -> "pc-cell-broken";
                default -> "pc-cell-offline";
            };

            Label cell = new Label(String.valueOf(i + 1));
            cell.setMinSize(cellSize, cellSize);
            cell.setPrefSize(cellSize, cellSize);
            cell.setAlignment(Pos.CENTER);
            cell.setTextFill(Color.web("#f3f4f6"));
            cell.setFont(Font.font("Segoe UI", FontWeight.BOLD, fontSize));
            cell.getStyleClass().add(cellStyleClass);
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

        GridPane form;
        Spinner<Integer> minSpinner;
        Spinner<Integer> maxSpinner;
        Spinner<Integer> colsSpinner;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/classroom-grid-settings-dialog.fxml"));
            form = loader.load();
            @SuppressWarnings("unchecked") Spinner<Integer> ms = (Spinner<Integer>) loader.getNamespace().get("minSpinner");
            @SuppressWarnings("unchecked") Spinner<Integer> xs = (Spinner<Integer>) loader.getNamespace().get("maxSpinner");
            @SuppressWarnings("unchecked") Spinner<Integer> cs = (Spinner<Integer>) loader.getNamespace().get("colsSpinner");
            minSpinner = ms;
            maxSpinner = xs;
            colsSpinner = cs;
        } catch (Exception e) {
            throw new IllegalStateException("그리드 설정 다이얼로그 FXML 로딩 실패", e);
        }

        minSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 80, curMin, 2));
        maxSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(16, 120, curMax, 2));
        colsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, curCols, 1));
        minSpinner.setEditable(true);
        maxSpinner.setEditable(true);
        colsSpinner.setEditable(true);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                panel.getProperties().put("gridMinCell", minSpinner.getValue());
                panel.getProperties().put("gridMaxCell", maxSpinner.getValue());
                panel.getProperties().put("gridMaxCols", colsSpinner.getValue());

                renderPcGrid(totalCount, pcCacheByGroupId.get(groupId), pcGrid, panel);
            }
        });
    }

    private List<PcStatusDto> sortPcList(List<PcStatusDto> source, String orderMode) {
        Comparator<PcStatusDto> byName = Comparator.comparing(
                dto -> dto.getPcName() == null ? "" : dto.getPcName(),
                String.CASE_INSENSITIVE_ORDER);

        Comparator<PcStatusDto> byIp = Comparator
                .comparingLong((PcStatusDto dto) -> ipToSortableLong(dto.getIpAddress()))
                .thenComparing(dto -> dto.getPcName() == null ? "" : dto.getPcName(), String.CASE_INSENSITIVE_ORDER);

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
        Label resizeHandle = (Label) panel.getProperties().get("resizeHandleLabel");
        HBox handleBox = (HBox) panel.getProperties().get("resizeHandleBox");
        if (resizeHandle == null || handleBox == null) {
            resizeHandle = new Label("◢");
            resizeHandle.setTextFill(Color.GRAY);
            resizeHandle.setCursor(Cursor.SE_RESIZE);
            resizeHandle.setAlignment(Pos.BOTTOM_RIGHT);
            handleBox = new HBox(resizeHandle);
            handleBox.setAlignment(Pos.BOTTOM_RIGHT);
            panel.getChildren().add(handleBox);
        }

        resizeHandle.setTextFill(Color.GRAY);
        resizeHandle.setCursor(Cursor.SE_RESIZE);
        resizeHandle.setAlignment(Pos.BOTTOM_RIGHT);
        handleBox.setVisible(editModeSupplier.getAsBoolean());
        handleBox.setManaged(editModeSupplier.getAsBoolean());
        resizeHandleNodes.put(dashboardPanelId, handleBox);

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
        if (!ensureWritePermission("패널 위치 변경")) {
            return;
        }
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
            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                    "패널 위치 저장 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).show());
            return null;
        });
    }

    private double applySnap(double value) {
        if (snapModeSupplier == null || !snapModeSupplier.getAsBoolean()) {
            return value;
        }
        return Math.round(value / DashboardViewModel.GRID_SIZE) * DashboardViewModel.GRID_SIZE;
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
        if (!ensureWritePermission("전체 명령 전송")) {
            return;
        }
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
                    log.warn("Failed to send group command {} for groupId={}, message={}",
                            type,
                            groupId,
                            resolveErrorMessage(ex, "요청을 처리하지 못했습니다."),
                            ex);
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            type + " 명령 전송 실패: " + resolveErrorMessage(ex, "요청을 처리하지 못했습니다.")).show());
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

        VBox content;
        Label message;
        CheckBox skipNext;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/group-command-confirm-content.fxml"));
            content = loader.load();
            message = (Label) loader.getNamespace().get("messageLabel");
            skipNext = (CheckBox) loader.getNamespace().get("skipNextCheck");
        } catch (Exception e) {
            throw new IllegalStateException("전체 명령 확인 다이얼로그 FXML 로딩 실패", e);
        }

        message.setText("강의실: " + (groupName == null ? "-" : groupName)
            + "\n명령: " + label
            + "\n\n실수로 실행하면 큰 영향이 있을 수 있습니다.");

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
