package com.example.frontend.controller;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.dto.dashboard.LayoutBackup;
import com.example.dto.dashboard.NetworkTrafficDto;
import com.example.frontend.controller.dashboard.DashboardCanvasModule;
import com.example.frontend.controller.dashboard.DashboardClassroomModule;
import com.example.frontend.controller.dashboard.DashboardInteractionService;
import com.example.frontend.controller.dashboard.DashboardManagementModule;
import com.example.frontend.controller.dashboard.DashboardDisconnectStatusPanelModule;
import com.example.frontend.controller.dashboard.DashboardRealtimeModule;
import com.example.frontend.controller.dashboard.DashboardResourcePanelModule;
import com.example.frontend.controller.dashboard.DashboardUIService;
import com.example.frontend.controller.dashboard.DashboardViewModel;
import com.example.frontend.controller.dashboard.DashboardWidgetModule;
import com.example.frontend.controller.dashboard.DashboardNetworkPanelModule;
import com.example.frontend.controller.dashboard.DashboardPcManagementModule;
import com.example.frontend.controller.panel.CommandStatusPanelController;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.ApiClientException;
import com.example.frontend.service.DashboardLayoutService;
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.DashboardService;
import com.example.frontend.util.AccessibilityHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DashboardController {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @FXML
    private VBox rootVBox;

    @FXML
    private HBox topBarRef;

    @FXML
    private Label dashboardTitleLabel;

    @FXML
    private Label dashboardSubtitleLabel;

    @FXML
    private MenuButton dashboardSettingsMenu;

    @FXML
    private Button logoutButton;

    @FXML
    private TabPane mainTabPane;

    @FXML
    private Tab dashboardTab;

    @FXML
    private SplitPane dashboardMainSplitPane;

    @FXML
    private StackPane leftPaneSlot;

    @FXML
    private Tab pcManagementTab;

    @FXML
    private StackPane pcManagementSlot;

    @FXML
    private Tab networkTab;

    @FXML
    private StackPane networkSlot;

    @FXML
    private Tab commandTab;

    @FXML
    private StackPane commandSlot;

    @FXML
    private Tab settingsTab;

    @FXML
    private StackPane settingsSlot;

    @FXML
    private ColorPicker bgColorPicker;

    @FXML
    private ToggleButton editModeButton;

    @FXML
    private ToggleButton focusModeButton;

    @FXML
    private ToggleButton dashboardOnlyButton;

    @FXML
    private Button fullScreenButton;

    @FXML
    private Button refreshButton;

    @FXML
    private ToggleButton gridToggleButton;

    @FXML
    private ToggleButton snapToggleButton;

    @FXML
    private MenuButton alignMenuButton;

    @FXML
    private MenuButton arrangeMenuButton;

    @FXML
    private Button zoomOutButton;

    @FXML
    private Button zoomResetButtonNode;

    @FXML
    private Button zoomInButton;

    @FXML
    private Button fitAllButton;

    @FXML
    private Group dashboardCanvasGroup;

    @FXML
    private ScrollPane dashboardScrollPane;

    @FXML
    private VBox toastContainer;

    @FXML
    private HBox editOnlyToolbar;

    @FXML
    private VBox rightToolPane;

    private DashboardCanvasModule canvasModule;
    private DashboardClassroomModule classroomModule;
    private DashboardManagementModule managementModule;
    private DashboardRealtimeModule realtimeModule;
    private DashboardWidgetModule widgetModule;
    private DashboardResourcePanelModule resourcePanelModule;
    private DashboardDisconnectStatusPanelModule disconnectStatusPanelModule;
    private DashboardViewModel dashboardViewModel;
    private DashboardUIService dashboardUIService;
    private DashboardLayoutService dashboardLayoutService;
    private final DashboardInteractionService dashboardInteractionService = new DashboardInteractionService();

    private Pane classroomContainer;
    private Label clockLabel;
    private Label statusSummaryLabel;
    private Label connectivityLabel;
    private SplitPane mainSplitPane;
    private VBox leftToolPane;
    private Node leftEditControls;
    private Button zoomResetButton;
    private boolean pendingInitialViewportAdjust = true;
    private final PauseTransition viewportAdjustDebounce = new PauseTransition(Duration.millis(90));
    private int viewportSnapshotCount = 0;
    private int fitAllSnapshotCount = 0;
    private boolean awaitingInitialWindowShow = true;
    private boolean initialFitApplied = false;
    private String lastFitSignature = "";
    private double lastFitScale = Double.NaN;
    private double lastFitHValue = Double.NaN;
    private double lastFitVValue = Double.NaN;
    private double lastFitContainerW = Double.NaN;
    private double lastFitContainerH = Double.NaN;
    private final ChangeListener<Number> sceneWidthListener = (o, oldW, newW) -> {
        if (mainSplitPane != null) {
            mainSplitPane.setDividerPositions(newW.doubleValue() < 1600 ? 0.20 : 0.18);
        }
        if (pendingInitialViewportAdjust) {
            viewportAdjustDebounce.playFromStart();
        }
    };
    private final ChangeListener<Number> sceneHeightListener = (o, oldH, newH) -> {
        if (pendingInitialViewportAdjust) {
            viewportAdjustDebounce.playFromStart();
        }
    };

    @FXML
    private ComboBox<DashboardInfoDto> dashboardSelector;

    private NetworkPanelController networkPanelController;
    private CommandStatusPanelController commandStatusPanelController;
    private DashboardPcManagementModule pcManagementModule;
    private final DashboardPanelManager panelManager = new DashboardPanelManager();
    private final Map<Long, VBox> textItemMap = new HashMap<>();
    private final Map<Long, VBox> imageItemMap = new HashMap<>();
    private DashboardNetworkPanelModule networkPanelModule;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DashboardService dashboardService = new DashboardService();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dashboard-scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean shutdownRequested = false;
    private final AtomicBoolean snapshotRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean serverDisconnectHandled = new AtomicBoolean(false);

    private boolean isViewerMode() {
        return ApiClient.getInstance().isViewerRole();
    }

    private boolean ensureWritePermission(String actionName) {
        if (!isViewerMode()) {
            return true;
        }
        String message = "뷰어 계정은 " + actionName + " 권한이 없습니다.";
        if (dashboardUIService != null) {
            dashboardUIService.showToast(message, true);
        } else {
            log.info(message);
        }
        return false;
    }

    private void removeTabIfPresent(Tab tab) {
        if (mainTabPane == null || tab == null) {
            return;
        }
        mainTabPane.getTabs().remove(tab);
    }

    private void applyViewerUiRestrictions() {
        if (!isViewerMode()) {
            return;
        }

        dashboardViewModel.setEditMode(false);

        if (dashboardSubtitleLabel != null) {
            String subtitle = dashboardSubtitleLabel.getText();
            if (subtitle != null && !subtitle.contains("읽기 전용")) {
                dashboardSubtitleLabel.setText(subtitle + " | 읽기 전용");
            }
        }

        if (dashboardSettingsMenu != null) {
            dashboardSettingsMenu.setDisable(true);
            dashboardSettingsMenu.setManaged(false);
            dashboardSettingsMenu.setVisible(false);
        }

        if (editModeButton != null) {
            editModeButton.setSelected(false);
            editModeButton.setDisable(true);
            editModeButton.setManaged(false);
            editModeButton.setVisible(false);
        }

        if (bgColorPicker != null) {
            bgColorPicker.setDisable(true);
        }

        if (leftPaneSlot != null) {
            leftPaneSlot.setManaged(false);
            leftPaneSlot.setVisible(false);
        }
        if (dashboardMainSplitPane != null) {
            dashboardMainSplitPane.setDividerPositions(0.0);
        }

        removeTabIfPresent(pcManagementTab);
        removeTabIfPresent(commandTab);
        removeTabIfPresent(settingsTab);

        if (mainTabPane != null && dashboardTab != null) {
            mainTabPane.getSelectionModel().select(dashboardTab);
        }
    }

    @FXML
    public void initialize() {
        dashboardViewModel = new DashboardViewModel();
        ApiClient.getInstance().setConnectionFailureHandler(this::handleServerConnectionLost);
        ApiClient.getInstance().setAuthFailureHandler(this::handleSessionExpired);

        if (rootVBox == null) {
            return;
        }

        rootVBox.setSpacing(10);
        if (!rootVBox.getStyleClass().contains("dashboard-root-bg")) {
            rootVBox.getStyleClass().add("dashboard-root-bg");
        }

        initializeTopBar();
        initializeTabs();
        applyViewerUiRestrictions();

        // Bind zoom property to UI updates
        dashboardViewModel.zoomScaleProperty().addListener((obs, oldV, newV) -> {
            if (dashboardUIService != null) {
                dashboardUIService.applyZoom(newV.doubleValue());
            }
            updateZoomButtonLabel(newV.doubleValue());
        });

        // Bind edit mode property to UI updates
        dashboardViewModel.isEditModeProperty().addListener((obs, oldV, newV) -> {
            applyEditModeUiState();
        });

        viewportAdjustDebounce.setOnFinished(e -> {
            if (dashboardScrollPane == null || classroomContainer == null) {
                return;
            }
            if (pendingInitialViewportAdjust && !isDashboardWindowShowing()) {
                logViewportSnapshot("debounce-wait-window-shown");
                return;
            }
            if (!pendingInitialViewportAdjust) {
                return;
            }

            if (!resizeCanvasToViewport()) {
                // Viewport가 아직 준비되지 않았으면 다음 pulse에서 재시도
                Platform.runLater(() -> {
                    if (pendingInitialViewportAdjust) {
                        viewportAdjustDebounce.playFromStart();
                    }
                });
                return;
            }
            expandCanvasToContentBounds();
            pendingInitialViewportAdjust = false;
            triggerInitialFitIfNeeded();
        });

        rootVBox.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.widthProperty().removeListener(sceneWidthListener);
                oldScene.heightProperty().removeListener(sceneHeightListener);
            }
            if (newScene != null) {
                // Setup keyboard accessibility shortcuts
                newScene.setOnKeyPressed(event -> handleKeyboardShortcuts(event));

                awaitingInitialWindowShow = true;
                newScene.widthProperty().addListener(sceneWidthListener);
                newScene.heightProperty().addListener(sceneHeightListener);
                newScene.windowProperty().addListener((wo, oldWindow, newWindow) -> registerWindowLifecycleHooks(newWindow));
                registerWindowLifecycleHooks(newScene.getWindow());
                if (oldScene == null) {
                    pendingInitialViewportAdjust = true;
                }
                if (isDashboardWindowShowing()) {
                    viewportAdjustDebounce.playFromStart();
                }
            }
        });

        scheduler.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            if (clockLabel != null) {
                clockLabel.setText(LocalTime.now().format(CLOCK_FORMAT));
            }
        }), 0, 1, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> Platform.runLater(this::refreshDashboardSnapshot), 0, 30, TimeUnit.SECONDS);

        connectWebSocket();
        loadDashboards();
    }

    private void loadStatusBarFromFxml() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/dashboard-statusbar.fxml"));
            loader.load();
            var ns = loader.getNamespace();
            statusSummaryLabel = (Label) ns.get("statusSummaryLabel");
            connectivityLabel = (Label) ns.get("connectivityLabel");
            clockLabel = (Label) ns.get("clockLabel");
        } catch (Exception e) {
            // fallback: use simple labels
            statusSummaryLabel = new Label("강의실 0, 온라인 0/0");
            connectivityLabel = new Label("연결 대기 중");
            clockLabel = new Label();
        }
    }

    private void initializeTopBar() {
        if (dashboardTitleLabel != null) {
            AccessibilityHelper.makeAccessible(dashboardTitleLabel, "통합 강의실 관제");
        }
        if (dashboardSubtitleLabel != null) {
            AccessibilityHelper.makeAccessible(dashboardSubtitleLabel, "실시간 모니터링, 원격 제어, 리포팅");
        }
        if (dashboardSelector == null) {
            return;
        }

        dashboardSelector.setPromptText("대시보드 선택");
        dashboardSelector.setPrefWidth(200);
        AccessibilityHelper.makeAccessible(dashboardSelector, "대시보드 선택", "사용할 대시보드를 선택합니다");
        dashboardSelector.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(DashboardInfoDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        dashboardSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(DashboardInfoDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        dashboardSelector.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                setCurrentDashboardId(newValue.getId());
                loadDashboardPanels();
            }
        });

        configureDashboardSettingsMenu(dashboardSettingsMenu);

        if (logoutButton != null) {
            logoutButton.getStyleClass().add("btn-icon-logout");
            logoutButton.setOnAction(e -> handleLogout());
            FontIcon icon = new FontIcon("mdi2l-logout");
            icon.setIconSize(14);
            icon.setIconColor(Color.WHITE);
            logoutButton.setGraphic(icon);
            AccessibilityHelper.makeAccessible(logoutButton, "로그아웃", "현재 사용자 세션을 종료합니다");
        }

        statusSummaryLabel = new Label();
        connectivityLabel = new Label();
        clockLabel = new Label();
        loadStatusBarFromFxml();

        // 설정 메뉴의 삭제 항목 초기화 및 리스너 등록
        MenuItem deleteMenuItem = dashboardSettingsMenu == null
                ? null
                : (MenuItem) dashboardSettingsMenu.getProperties().get("deleteMenuItem");
        if (deleteMenuItem != null) {
            updateDashboardMenuState(deleteMenuItem);
            dashboardSelector.getItems().addListener((javafx.collections.ListChangeListener<DashboardInfoDto>) c -> {
                updateDashboardMenuState(deleteMenuItem);
            });
            dashboardSelector.valueProperty().addListener((obs, ov, nv) -> {
                updateDashboardMenuState(deleteMenuItem);
            });
        }
    }

    // 설정 메뉴 초기화
    private void configureDashboardSettingsMenu(MenuButton settingsMenu) {
        if (settingsMenu == null) {
            return;
        }
        if (isViewerMode()) {
            settingsMenu.getItems().clear();
            settingsMenu.setDisable(true);
            settingsMenu.setManaged(false);
            settingsMenu.setVisible(false);
            return;
        }
        settingsMenu.getItems().clear();

        // 대시보드 추가
        MenuItem addItem = new MenuItem("+ 대시보드 추가");
        addItem.setOnAction(e -> handleCreateDashboard());

        // 대시보드 삭제
        MenuItem deleteItem = new MenuItem("🗑 대시보드 삭제");
        deleteItem.setOnAction(e -> handleDeleteDashboard());

        // 레이아웃 백업
        MenuItem backupItem = new MenuItem("💾 레이아웃 백업");
        backupItem.setOnAction(e -> handleLayoutBackup());

        // 레이아웃 복원
        MenuItem restoreItem = new MenuItem("↩ 레이아웃 복원");
        restoreItem.setOnAction(e -> handleLayoutRestore());

        settingsMenu.getItems().addAll(addItem, deleteItem, new SeparatorMenuItem(), backupItem, restoreItem);

        // 메뉴 참조 저장 (나중에 updateDashboardMenuState 호출용)
        settingsMenu.getProperties().put("deleteMenuItem", deleteItem);
    }

    // 설정 메뉴 삭제 항목 활성화/비활성화 로직
    private void updateDashboardMenuState(MenuItem deleteItem) {
        List<DashboardInfoDto> dashboards = dashboardSelector.getItems();
        DashboardInfoDto selected = dashboardSelector.getValue();
        deleteItem.setDisable(
            dashboards == null || dashboards.size() <= 1 || selected == null
        );
    }

    // 대시보드 삭제 처리
    private void handleDeleteDashboard() {
        if (!ensureWritePermission("대시보드 삭제")) {
            return;
        }
        List<DashboardInfoDto> dashboards = dashboardSelector.getItems();
        DashboardInfoDto selected = dashboardSelector.getValue();
        if (dashboards == null || dashboards.size() <= 1 || selected == null) {
            dashboardUIService.showToast("대시보드는 최소 1개 이상 존재해야 합니다.", true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "정말로 대시보드 [" + selected.getName() + "]을(를) 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("대시보드 삭제");
        confirm.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
                dashboardService.deleteDashboardAsync(selected.getId())
                        .thenAccept(resp -> Platform.runLater(() -> {
                            dashboardUIService.showToast("대시보드가 삭제되었습니다.", false);
                            loadDashboards();
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> dashboardUIService.showToast("대시보드 삭제 실패: " + ex.getMessage(), true));
                            return null;
                        });
            }
        });
    }

    private void handleLogout() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "로그아웃 하시겠습니까?",
                javafx.scene.control.ButtonType.YES,
                javafx.scene.control.ButtonType.NO);
        confirm.showAndWait().ifPresent(type -> {
            if (type != javafx.scene.control.ButtonType.YES) {
                return;
            }
            try {
                moveToLoginScreen(null, false);
                // 상태바 재생성
                loadStatusBarFromFxml();

                // 하단 상태바 고정
                HBox statusBar = (HBox) statusSummaryLabel.getParent();
                rootVBox.getChildren().add(statusBar);
            } catch (Exception ex) {
                dashboardUIService.showToast("로그아웃 처리 실패: " + ex.getMessage(), true);
            }
        });
    }

    /**
     * 로그인 화면으로 이동
     * @param alertMessage null이면 alert 미표시, 값이 있으면 warning alert 표시
     * @param shouldShutdown true면 Dashboard 정리 작업 수행
     */
    private void moveToLoginScreen(String alertMessage, boolean shouldShutdown) throws Exception {
        ApiClient.getInstance().setBearerToken(null);

        if (shouldShutdown) {
            shutdown();
        }

        var fxmlUrl = getClass().getResource("/fxml/login.fxml");
        if (fxmlUrl == null) {
            dashboardUIService.showToast("로그인 화면을 찾을 수 없습니다.", true);
            return;
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent loginRoot = loader.load();
        LoginController loginController = loader.getController();

        javafx.stage.Window window = rootVBox != null && rootVBox.getScene() != null
            ? rootVBox.getScene().getWindow()
            : null;

        if (!(window instanceof Stage stage)) {
            dashboardUIService.showToast("윈도우 핸들을 찾을 수 없습니다.", true);
            return;
        }

        loginController.setPrimaryStage(stage);
        Scene loginScene = new Scene(loginRoot, 420, 320);
        var css = getClass().getResource("/dashboard-theme.css");
        if (css != null) {
            loginScene.getStylesheets().add(css.toExternalForm());
        }

        stage.setScene(loginScene);
        stage.setTitle("스마트 강의실 대시보드 - 로그인");
        stage.setMinWidth(420);
        stage.setMinHeight(320);
        stage.setMaximized(false);
        stage.setWidth(460);
        stage.setHeight(360);
        stage.centerOnScreen();

        // alertMessage가 있으면 Warning Alert 표시
        if (alertMessage != null && !alertMessage.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("서버 연결 실패");
            alert.setHeaderText("서버 연결이 끊어졌습니다.");
            alert.setContentText(alertMessage);
            alert.show();
        }
    }

    private void handleLayoutBackup() {
        if (!ensureWritePermission("레이아웃 백업")) {
            return;
        }
        Long dashboardId = currentDashboardId();
        if (dashboardId == null) {
            dashboardUIService.showToast("대시보드를 선택해주세요.", true);
            return;
        }

        TextInputDialog dialog = new TextInputDialog("백업_" + System.currentTimeMillis());
        dialog.setTitle("백업");
        dialog.setHeaderText("레이아웃 백업 이름 입력");
        dialog.setContentText("백업이름:");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) {
                dashboardUIService.showToast("백업 이름을 입력해주세요.", true);
                return;
            }

            Object layoutData = getCurrentLayoutData();
            dashboardLayoutService.backupCurrentLayout(dashboardId, name.trim(), layoutData)
                .thenAccept(path -> Platform.runLater(() -> {
                    dashboardUIService.showToast("백업 완료: " + new java.io.File(path).getName(), false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> dashboardUIService.showToast("백업 실패: " + ex.getMessage(), true));
                    return null;
                });
        });
    }

    private void handleLayoutRestore() {
        if (!ensureWritePermission("레이아웃 복원")) {
            return;
        }
        Long dashboardId = currentDashboardId();
        if (dashboardId == null) {
            dashboardUIService.showToast("대시보드를 선택해주세요.", true);
            return;
        }

        Window owner = rootVBox.getScene() != null ? rootVBox.getScene().getWindow() : null;
        dashboardLayoutService.restoreLayoutFromFile(owner, dashboardId)
            .thenAccept(backup -> {
                if (backup == null) {
                    return;
                }
                Platform.runLater(() -> {
                    applyRestoredLayout(backup);
                    dashboardUIService.showToast("레이아웃이 복원되었습니다.", false);
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> dashboardUIService.showToast("복원 실패: " + ex.getMessage(), true));
                return null;
            });
    }

    private Object getCurrentLayoutData() {
        Map<String, Object> layout = new HashMap<>();
        layout.put("zoom", dashboardViewModel.getZoomScale());
        layout.put("snapToGrid", dashboardViewModel.isSnapToGrid());
        layout.put("gridSize", DashboardViewModel.GRID_SIZE);
        layout.put("timestamp", java.time.LocalDateTime.now());
        return layout;
    }

    @SuppressWarnings("unchecked")
    private void applyRestoredLayout(LayoutBackup backup) {
        if (backup == null || backup.getLayoutData() == null) {
            return;
        }
        try {
            Map<String, ?> layout = (Map<String, ?>) backup.getLayoutData();
            Object zoomObj = layout.get("zoom");
            Object snapObj = layout.get("snapToGrid");

            if (zoomObj instanceof Number) {
                double zoom = ((Number) zoomObj).doubleValue();
                dashboardViewModel.setZoom(zoom);
            }

            if (snapObj instanceof Boolean) {
                boolean snapToGrid = (Boolean) snapObj;
                if (snapToGrid != dashboardViewModel.isSnapToGrid()) {
                    dashboardViewModel.toggleSnapToGrid();
                }
            }
        } catch (Exception ex) {
            log.error("Failed to apply restored layout", ex);
            dashboardUIService.showToast("레이아웃 적용 실패: " + ex.getMessage(), true);
        }
    }

    private void handleKeyboardShortcuts(javafx.scene.input.KeyEvent event) {
        if (isViewerMode() && event.isControlDown()
                && (event.getCode() == KeyCode.S || event.getCode() == KeyCode.Z)) {
            event.consume();
            return;
        }
        // Escape: Toggle edit mode off if active
        if (event.getCode() == KeyCode.ESCAPE) {
            if (dashboardViewModel.isEditMode()) {
                dashboardViewModel.toggleEditMode();
            }
            event.consume();
        }
        // Ctrl+S: Save/Backup current layout
        else if (event.isControlDown() && event.getCode() == KeyCode.S) {
            handleLayoutBackup();
            event.consume();
        }
        // Ctrl+Z: Undo (restore from last backup)
        else if (event.isControlDown() && event.getCode() == KeyCode.Z) {
            handleLayoutRestore();
            event.consume();
        }
    }

    private void initializeTabs() {
        if (mainTabPane == null) {
            return;
        }
        boolean viewerMode = isViewerMode();

        if (dashboardTab != null) {
            dashboardTab.setContent(createMainDashboardView());
        }

        if (pcManagementTab != null) {
            if (viewerMode) {
                removeTabIfPresent(pcManagementTab);
            } else {
                if (pcManagementSlot != null) {
                    pcManagementSlot.getChildren().setAll(createPcManagementView());
                }
                pcManagementTab.setOnSelectionChanged(event -> {
                    if (pcManagementTab.isSelected() && pcManagementModule != null) {
                        pcManagementModule.reloadData();
                    }
                });
            }
        }

        networkPanelController = new NetworkPanelController(null);
        if (networkTab != null) {
            if (networkSlot != null) {
                networkSlot.getChildren().setAll(networkPanelController.getPanel());
            }
            networkTab.setOnSelectionChanged(event -> {
                if (networkTab.isSelected()) {
                    refreshNetworkTabData();
                }
            });
        }

        if (commandTab != null) {
            if (viewerMode) {
                removeTabIfPresent(commandTab);
            } else {
                commandStatusPanelController = new CommandStatusPanelController();
                if (commandSlot != null) {
                    commandSlot.getChildren().setAll(commandStatusPanelController.getPanel());
                }
            }
        }

        if (settingsTab != null) {
            if (viewerMode) {
                removeTabIfPresent(settingsTab);
            } else {
                if (settingsSlot != null) {
                    settingsSlot.getChildren().setAll(createManagementView());
                }
                settingsTab.setOnSelectionChanged(event -> {
                    if (settingsTab.isSelected() && managementModule != null) {
                        managementModule.reloadData();
                    }
                });
            }
        }
    }

    private Node createManagementView() {
        managementModule = new DashboardManagementModule(
            currentApiBaseUrl(),
                () -> rootVBox.getScene()
        );
        return managementModule.createView();
    }

    private Node createPcManagementView() {
        pcManagementModule = new DashboardPcManagementModule(
                objectMapper
        );
        return pcManagementModule.createView();
    }

    private Node createMainDashboardView() {
        this.mainSplitPane = dashboardMainSplitPane;
        initializeMainDashboardShell();

        widgetModule = new DashboardWidgetModule(
                objectMapper,
            currentApiBaseUrl(),
                classroomContainer,
                textItemMap,
                imageItemMap,
            this::isEditModeActive,
            this::isSnapToGridEnabled,
                this::refreshPanelListAndDashboard
        );
        widgetModule.setDashboardIdSupplier(() -> currentDashboardId() == null ? -1L : currentDashboardId());
        // 텍스트/이미지는 loadDashboardPanels()에서 대시보드 컨텍스트와 함께 로드됩니다.

        classroomModule = new DashboardClassroomModule(
                objectMapper,
            currentApiBaseUrl(),
                classroomContainer,
                panelManager,
            this::isEditModeActive,
            this::refreshDashboardSnapshot,
            () -> currentDashboardId() == null ? -1L : currentDashboardId(),
                this::loadDashboardPanels,
            this::updateCachedPanelPosition,
            this::isSnapToGridEnabled
        );

        resourcePanelModule = new DashboardResourcePanelModule(
            dashboardViewModel,
            classroomModule,
            widgetModule,
            dashboardService,
            this::isEditModeActive,
            this::currentDashboardId,
            this::getResponsiveDefaultSize,
            this::loadDashboardPanels
        );

        VBox leftPane = buildLeftPane();
        this.leftToolPane = leftPane;
        if (leftPaneSlot != null) {
            leftPaneSlot.getChildren().setAll(leftPane);
        }
        if (dashboardMainSplitPane != null) {
            dashboardMainSplitPane.setDividerPositions(0.20);
        }

            dashboardUIService = new DashboardUIService(
                toastContainer,
                classroomContainer,
                dashboardScrollPane,
                canvasModule,
                editOnlyToolbar,
                leftEditControls
            );

            dashboardLayoutService = new DashboardLayoutService(objectMapper, ApiClient.getInstance().getBaseUrl());

            networkPanelModule = new DashboardNetworkPanelModule(
                dashboardService,
                classroomContainer,
                dashboardInteractionService,
                this::isEditModeActive,
                this::applySnap,
                this::currentDashboardId,
                (msg, err) -> dashboardUIService.showToast(msg, err),
                this::updateCachedPanelPosition,
                this::getResponsiveDefaultSize,
                this::loadDashboardPanels
            );

            disconnectStatusPanelModule = new DashboardDisconnectStatusPanelModule(
                dashboardService,
                classroomContainer,
                dashboardInteractionService,
                this::isEditModeActive,
                this::applySnap,
                this::currentDashboardId,
                (msg, err) -> dashboardUIService.showToast(msg, err),
                this::updateCachedPanelPosition,
                this::getResponsiveDefaultSize,
                this::loadDashboardPanels
            );

        applyEditModeUiState();
        return dashboardMainSplitPane;
    }

    private VBox buildLeftPane() {
        VBox leftPane = resourcePanelModule.buildView();
        leftEditControls = resourcePanelModule.getEditControls();
        return leftPane;
    }

        private void initializeMainDashboardShell() {
        canvasModule = new DashboardCanvasModule(
                this::isEditModeActive,
                this::saveNodePosition,
                this::handleGroupDrop,
                this::handleTextDrop,
                this::handleImageDrop
        );

        classroomContainer = canvasModule.getClassroomContainer();
        classroomContainer.getStyleClass().add("dashboard-canvas-bg");

        if (dashboardCanvasGroup != null) {
            dashboardCanvasGroup.getChildren().setAll(classroomContainer);
        }
        if (dashboardScrollPane == null) {
            return;
        }
        dashboardScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            if (pendingInitialViewportAdjust && newBounds != null && newBounds.getWidth() > 1 && newBounds.getHeight() > 1) {
                logViewportSnapshot("viewport-bounds-changed");
                viewportAdjustDebounce.playFromStart();
            }
        });

        if (bgColorPicker != null) {
            configureBackgroundColorPicker(bgColorPicker);
        }
        if (editModeButton != null) {
            configureEditModeToggleButton(editModeButton);
        }
        if (focusModeButton != null) {
            configureFocusModeToggleButton(focusModeButton);
        }
        if (dashboardOnlyButton != null) {
            configureDashboardOnlyToggleButton(dashboardOnlyButton);
        }
        if (fullScreenButton != null) {
            configureFullScreenButton(fullScreenButton);
        }
        if (refreshButton != null) {
            configureRefreshButton(refreshButton);
        }
        if (gridToggleButton != null) {
            configureGridToggleButton(gridToggleButton);
        }
        if (snapToggleButton != null) {
            configureSnapToggleButton(snapToggleButton);
        }
        if (alignMenuButton != null) {
            configureAlignMenu(alignMenuButton);
        }
        if (arrangeMenuButton != null) {
            configureArrangeMenu(arrangeMenuButton);
        }
        if (zoomOutButton != null) {
            configureZoomOutButton(zoomOutButton);
        }
        if (zoomResetButtonNode != null) {
            configureZoomResetButton(zoomResetButtonNode);
        }
        if (zoomInButton != null) {
            configureZoomInButton(zoomInButton);
        }
        if (fitAllButton != null) {
            configureFitAllButton(fitAllButton);
        }
    }

    private void configureEditModeToggleButton(ToggleButton editModeBtn) {
        if (isViewerMode()) {
            editModeBtn.setSelected(false);
            editModeBtn.setDisable(true);
            return;
        }
        editModeBtn.setOnAction(e -> {
            dashboardViewModel.setEditMode(editModeBtn.isSelected());
            applyEditModeUiState();
            dashboardUIService.showToast(isEditModeActive() ? "편집 모드 켜짐" : "편집 모드 꺼짐", false);
        });
    }

    private void configureFocusModeToggleButton(ToggleButton focusModeBtn) {
        focusModeBtn.setOnAction(e -> {
            dashboardViewModel.setFocusMode(focusModeBtn.isSelected());
            if (leftToolPane != null) {
                leftToolPane.setManaged(!isFocusModeActive());
                leftToolPane.setVisible(!isFocusModeActive());
            }
            if (mainSplitPane != null) {
                mainSplitPane.setDividerPositions(isFocusModeActive() ? 0.0 : 0.23);
            }
        });
    }

    private void configureDashboardOnlyToggleButton(ToggleButton dashboardOnlyBtn) {
        dashboardOnlyBtn.setSelected(isDashboardOnlyModeActive());
        dashboardOnlyBtn.setOnAction(e -> {
            dashboardViewModel.setDashboardOnlyMode(dashboardOnlyBtn.isSelected());
            if (leftPaneSlot != null) {
                leftPaneSlot.setManaged(!isDashboardOnlyModeActive());
                leftPaneSlot.setVisible(!isDashboardOnlyModeActive());
            }
            if (topBarRef != null) {
                topBarRef.setManaged(!isDashboardOnlyModeActive());
                topBarRef.setVisible(!isDashboardOnlyModeActive());
            }
            if (dashboardMainSplitPane != null && !isDashboardOnlyModeActive()) {
                dashboardMainSplitPane.setDividerPositions(0.20);
            }
            dashboardUIService.showToast(isDashboardOnlyModeActive() ? "대시보드만 모드" : "일반 모드", false);
        });
    }

    private void configureFullScreenButton(Button fullScreenBtn) {
        fullScreenBtn.getStyleClass().add("btn-icon-fullscreen");
        FontIcon icon = new FontIcon("mdi2f-fullscreen");
        icon.setIconSize(14);
        icon.setIconColor(Color.WHITE);
        fullScreenBtn.setGraphic(icon);
        fullScreenBtn.setOnAction(e -> {
            if (rootVBox != null && rootVBox.getScene() != null && rootVBox.getScene().getWindow() instanceof Stage stage) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });
    }

    private void configureRefreshButton(Button refreshBtn) {
        refreshBtn.getStyleClass().add("btn-icon-refresh");
        FontIcon icon = new FontIcon("mdi2r-refresh");
        icon.setIconSize(14);
        icon.setIconColor(Color.WHITE);
        refreshBtn.setGraphic(icon);
        refreshBtn.setOnAction(e -> {
            RotateTransition rotate = new RotateTransition(Duration.millis(450), refreshBtn);
            rotate.setByAngle(360);
            rotate.play();
            refreshDashboardSnapshot();
        });
    }

    private void configureBackgroundColorPicker(ColorPicker bgColorPicker) {
        bgColorPicker.setValue(Color.web("#f0f2f5"));
        if (isViewerMode()) {
            bgColorPicker.setDisable(true);
            return;
        }
        bgColorPicker.setOnAction(e -> {
            if (classroomContainer == null) {
                return;
            }
            Color color = bgColorPicker.getValue();
            String hex = String.format("#%02X%02X%02X",
                    (int) (color.getRed() * 255),
                    (int) (color.getGreen() * 255),
                    (int) (color.getBlue() * 255));
            classroomContainer.setStyle("-fx-background-color: " + hex + ";");
            if (dashboardScrollPane != null) {
                dashboardScrollPane.setStyle("-fx-background: " + hex + "; -fx-background-color: " + hex + ";");
            }
        });
    }

    private void configureGridToggleButton(ToggleButton gridToggleBtn) {
        gridToggleBtn.setOnAction(e -> {
            if (canvasModule != null) {
                canvasModule.drawGrid(gridToggleBtn.isSelected());
            }
        });
    }

    private void configureSnapToggleButton(ToggleButton snapToggleBtn) {
        snapToggleBtn.setSelected(isSnapToGridEnabled());
        snapToggleBtn.setOnAction(e -> dashboardViewModel.setSnapToGrid(snapToggleBtn.isSelected()));
    }

    private void configureAlignMenu(MenuButton alignMenu) {
        alignMenu.getItems().clear();
        MenuItem alignLeft = new MenuItem("왼쪽");
        alignLeft.setOnAction(e -> canvasModule.alignSelectedNodes("LEFT"));
        MenuItem alignRight = new MenuItem("오른쪽");
        alignRight.setOnAction(e -> canvasModule.alignSelectedNodes("RIGHT"));
        MenuItem alignTop = new MenuItem("위");
        alignTop.setOnAction(e -> canvasModule.alignSelectedNodes("TOP"));
        MenuItem alignBottom = new MenuItem("아래");
        alignBottom.setOnAction(e -> canvasModule.alignSelectedNodes("BOTTOM"));
        alignMenu.getItems().addAll(alignLeft, alignRight, alignTop, alignBottom);
    }

    private void configureArrangeMenu(MenuButton arrangeMenu) {
        arrangeMenu.getItems().clear();
        MenuItem gridArrange = new MenuItem("격자");
        gridArrange.setOnAction(e -> arrangePanels("GRID"));
        MenuItem rowArrange = new MenuItem("행");
        rowArrange.setOnAction(e -> arrangePanels("ROW"));
        MenuItem colArrange = new MenuItem("열");
        colArrange.setOnAction(e -> arrangePanels("COLUMN"));
        arrangeMenu.getItems().addAll(gridArrange, rowArrange, colArrange);
    }

    private void configureZoomOutButton(Button zoomOutBtn) {
        zoomOutBtn.setOnAction(e -> dashboardViewModel.zoomOut());
    }

    private void configureZoomResetButton(Button zoomResetBtn) {
        zoomResetBtn.setOnAction(e -> dashboardViewModel.resetZoom());
        zoomResetButton = zoomResetBtn;
    }

    private void configureZoomInButton(Button zoomInBtn) {
        zoomInBtn.setOnAction(e -> dashboardViewModel.zoomIn());
    }

    private void configureFitAllButton(Button fitAllBtn) {
        fitAllBtn.setOnAction(e -> dashboardUIService.fitAllPanelsToViewport(new ArrayList<>(classroomContainer.getChildren())));
    }

    private void applyEditModeUiState() {
        if (dashboardUIService != null) {
            dashboardUIService.applyEditModeUiState(isEditModeActive());
        } else if (canvasModule != null) {
            canvasModule.applyEditMode(isEditModeActive());
        }
        if (widgetModule != null) {
            widgetModule.applyEditMode(isEditModeActive());
        }
        if (classroomModule != null) {
            classroomModule.applyEditMode(isEditModeActive());
        }
        if (networkPanelModule != null) {
            networkPanelModule.applyEditMode(isEditModeActive());
        }
        if (disconnectStatusPanelModule != null) {
            disconnectStatusPanelModule.applyEditMode(isEditModeActive());
        }
        if (resourcePanelModule != null) {
            resourcePanelModule.refreshListView();
        }
    }

    private void connectWebSocket() {
        if (realtimeModule == null) {
            realtimeModule = new DashboardRealtimeModule(ApiClient.getInstance().getWsUrl());
        }
        realtimeModule.start(
                () -> Platform.runLater(this::refreshDashboardSnapshot),
                alertPayload -> dashboardUIService.showToast("실시간 알림 수신", false),
                () -> handleServerConnectionLost(new RuntimeException("WebSocket disconnected"))
        );
    }

    private void handleServerConnectionLost(Throwable throwable) {
        if (!serverDisconnectHandled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            if (shutdownRequested) {
                return;
            }
            if (connectivityLabel != null) {
                connectivityLabel.setText("연결 끊김");
                connectivityLabel.setTextFill(Color.web("#fca5a5"));
            }
            forceMoveToLoginWithAlert("서버 연결이 끊어졌습니다. 로그인 페이지로 이동합니다.");
        });
    }

    private void handleSessionExpired(ApiClientException exception) {
        if (!serverDisconnectHandled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            if (shutdownRequested) {
                return;
            }
            if (connectivityLabel != null) {
                connectivityLabel.setText("세션 만료");
                connectivityLabel.setTextFill(Color.web("#fca5a5"));
            }
            forceMoveToLoginWithAlert("로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
        });
    }

    private void forceMoveToLoginWithAlert(String message) {
        try {
            moveToLoginScreen(message, true);
        } catch (Exception ex) {
            dashboardUIService.showToast("로그인 화면 이동 실패: " + ex.getMessage(), true);
        }
    }

    private String currentApiBaseUrl() {
        return ApiClient.getInstance().getBaseUrl();
    }

    private void saveNodePosition(Node node) {
        if (node == null) {
            return;
        }
        if (isViewerMode()) {
            return;
        }

        double x = node.getLayoutX();
        double y = node.getLayoutY();
        // prefWidth(-1)은 setPrefWidth가 없으면 -1 반환 → boundsInParent로 대체
        double pw = node.prefWidth(-1);
        double ph = node.prefHeight(-1);
        double width  = pw  > 0 ? pw  : node.getBoundsInParent().getWidth();
        double height = ph > 0 ? ph : node.getBoundsInParent().getHeight();
        boolean visible = node.isVisible();

        Object dashboardPanelIdObj = node.getProperties().get("dashboardPanelId");
        Long dashboardId = currentDashboardId();
        if (dashboardPanelIdObj != null && dashboardId != null) {
            Long panelId;
            try {
                panelId = Long.valueOf(dashboardPanelIdObj.toString());
            } catch (Exception ex) {
                panelId = null;
            }
            if (panelId != null) {
                dashboardService.updatePanelPositionAsync(dashboardId, panelId, x, y, width, height);
                updateCachedPanelPosition(panelId, x, y, width, height);
                expandCanvasToContentBounds();
                return;
            }
        }

        Object userData = node.getUserData();
        if (userData instanceof Map<?, ?> rawMap) {
            Object typeObj = rawMap.get("type");
            Object idObj = rawMap.get("id");
            if (typeObj != null && idObj != null) {
                String type = typeObj.toString();
                Long id;
                try {
                    id = Long.valueOf(idObj.toString());
                } catch (Exception ex) {
                    id = null;
                }
                if (id != null && widgetModule != null) {
                    if ("text".equals(type)) {
                        widgetModule.updateTextItemPosition(id, x, y, width, height, visible);
                    } else if ("image".equals(type)) {
                        widgetModule.updateImageItemPosition(id, x, y, width, height, visible);
                    }
                }
            }
        }
        expandCanvasToContentBounds();
    }

    private void refreshDashboardSnapshot() {
        if (!snapshotRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        dashboardViewModel.fetchRefreshSnapshotAsync(snapshot -> Platform.runLater(() -> applyRefreshSnapshot(snapshot)));
    }

    private void applyRefreshSnapshot(DashboardRefreshDto snapshot) {
        try {
            if (snapshot == null) {
                return;
            }

            // 강의실 목록을 FX 스레드에서 캐싱 (스레드 안전성 보장)
            List<ClassroomSummaryDto> classrooms = snapshot.getClassrooms();
            if (classrooms != null) {
                log.debug("applyRefreshSnapshot: {} classrooms received", classrooms.size());
                dashboardViewModel.setCachedClassrooms(classrooms);
            } else {
                log.warn("applyRefreshSnapshot: classrooms is null in snapshot");
            }

            // 텍스트/이미지는 대시보드별 스코핑: 현재 대시보드 ID로 직접 로드
            if (widgetModule != null) {
                widgetModule.loadTextItems();
                widgetModule.loadImageItems();
            }

            NetworkTrafficDto traffic = snapshot.getTraffic();
            if (traffic != null) {
                if (networkPanelController != null) {
                    networkPanelController.updateNetworkChart(traffic.getInboundMbps(), traffic.getOutboundMbps());
                }
                if (networkPanelModule != null) {
                    networkPanelModule.updateNetworkTraffic(traffic.getInboundMbps(), traffic.getOutboundMbps());
                    networkPanelModule.refreshPcTrafficRows();
                }
            }

            refreshPanelListAndDashboard();
            refreshAuxiliaryTabs();
        } finally {
            snapshotRefreshInFlight.set(false);
        }
    }

    private void refreshAuxiliaryTabs() {
        if (pcManagementModule != null) {
            pcManagementModule.reloadData();
        }
        if (managementModule != null) {
            managementModule.reloadData();
        }
        refreshNetworkTabData();
        if (commandStatusPanelController != null) {
            commandStatusPanelController.refresh();
        }
    }

    private void refreshNetworkTabData() {
        if (networkPanelController == null) {
            return;
        }
        networkPanelController.updateNetworkChart();
        networkPanelController.refreshAllPcTraffic();
    }

    private void loadDashboards() {
        dashboardService.fetchDashboardsAsync(dashboards -> Platform.runLater(() -> {
            dashboardViewModel.setCachedDashboards(dashboards);
            if (dashboardSelector == null) {
                return;
            }
            dashboardSelector.getItems().setAll(dashboards);
            if (dashboards.isEmpty()) {
                setCurrentDashboardId(null);
                return;
            }
            DashboardInfoDto selected = dashboards.stream()
                    .filter(d -> d.getId() != null && d.getId().equals(currentDashboardId()))
                    .findFirst()
                    .orElse(dashboards.get(0));
            dashboardSelector.getSelectionModel().select(selected);
            setCurrentDashboardId(selected.getId());
            loadDashboardPanels();
        }));
    }

    private void loadDashboardPanels() {
        if (currentDashboardId() == null) {
            dashboardViewModel.setCachedDashboardPanels(List.of());
            Platform.runLater(this::refreshPanelListAndDashboard);
            return;
        }
        dashboardService.fetchDashboardPanelsAsync(currentDashboardId(), panels -> Platform.runLater(() -> {
            dashboardViewModel.setCachedDashboardPanels(panels);
            refreshPanelListAndDashboard();
            // 대시보드가 바뀌었으므로 해당 대시보드의 텍스트/이미지 위젯을 다시 로드
            if (widgetModule != null) {
                widgetModule.loadTextItems();
                widgetModule.loadImageItems();
            }
            pendingInitialViewportAdjust = true;
            scheduleInitialViewportAdjust();
            triggerInitialFitIfNeeded();
        }));
    }

    private void handleCreateDashboard() {
        if (!ensureWritePermission("대시보드 생성")) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("새 대시보드");
        dialog.setTitle("대시보드 생성");
        dialog.setHeaderText("대시보드 이름 입력");
        dialog.setContentText("이름:");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            dashboardService.createDashboardAsync(trimmed, "사용자가 생성한 대시보드", created -> Platform.runLater(() -> {
                if (created == null) {
                    showAlert("대시보드 생성 실패", "대시보드를 생성할 수 없습니다.");
                    return;
                }
                setCurrentDashboardId(created.getId());
                loadDashboards();
            }));
        });
    }

    private void refreshPanelListAndDashboard() {
        List<ClassroomSummaryDto> classrooms = dashboardViewModel.getCachedClassrooms();
        List<DashboardPanelDto> panels = dashboardViewModel.getCachedDashboardPanels();

        if (resourcePanelModule != null) {
            resourcePanelModule.updateItems(classrooms, panels);
        }

        if (classroomModule != null) {
            classroomModule.updateDashboardClassrooms(classrooms, panels);
        }
        if (networkPanelModule != null) {
            networkPanelModule.update(classrooms, panels);
            networkPanelModule.refreshPcTrafficRows();
        }
        if (disconnectStatusPanelModule != null) {
            disconnectStatusPanelModule.update(classrooms, panels);
        }
        Platform.runLater(this::expandCanvasToContentBounds);
        updateTopSummary();
    }

    private void updateCachedPanelPosition(Long panelId, double x, double y, double width, double height) {
        if (panelId == null || dashboardViewModel == null) {
            return;
        }
        List<DashboardPanelDto> panels = dashboardViewModel.getCachedDashboardPanels();
        if (panels == null || panels.isEmpty()) {
            return;
        }
        for (DashboardPanelDto panel : panels) {
            if (panel != null && panelId.equals(panel.getId())) {
                panel.setX(x);
                panel.setY(y);
                panel.setWidth(width);
                panel.setHeight(height);
                break;
            }
        }
        expandCanvasToContentBounds();
    }

    private void fitAllPanelsToViewport() {
        fitAllPanelsToViewport(true);
    }

    private void fitAllPanelsToViewport(boolean showUserToast) {
        fitAllPanelsToViewport(showUserToast, false);
    }

    private boolean fitAllPanelsToViewport(boolean showUserToast, boolean markInitialApplied) {
        if (dashboardScrollPane == null || classroomContainer == null) {
            return false;
        }

        List<Node> nodes = classroomContainer.getChildren().stream()
            .filter(node -> node != null)
            .filter(node -> node != canvasModule.getGridCanvas())
            .filter(node -> node != canvasModule.getSelectionRect())
            .filter(Node::isVisible)
            .filter(Node::isManaged)
            .toList();
        List<DashboardPanelDto> cachedPanels = dashboardViewModel != null
                ? dashboardViewModel.getCachedDashboardPanels()
                : List.of();
        java.util.Set<Long> activePanelIds = new java.util.HashSet<>();
        for (DashboardPanelDto panel : cachedPanels) {
            if (panel != null && panel.getId() != null) {
                activePanelIds.add(panel.getId());
            }
        }
        if (nodes.isEmpty() && cachedPanels.isEmpty()) {
            dashboardViewModel.setZoom(1.0);
            if (markInitialApplied) {
                initialFitApplied = true;
            }
            return true;
        }

        if (dashboardScrollPane.getViewportBounds().getWidth() <= 1 || dashboardScrollPane.getViewportBounds().getHeight() <= 1) {
            Platform.runLater(this::fitAllPanelsToViewport);
            return false;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        int counted = 0;

        // 1) 실제 컨테이너 자식 노드 경계를 기준으로 계산
        // (panelManager/맵 누락으로 인한 fit 대상 누락 방지)
        for (Node node : nodes) {
            if (node == null || node.getParent() == null) {
                continue;
            }
            Object panelIdObj = node.getProperties().get("dashboardPanelId");
            // 현재 대시보드 패널 id가 붙은 노드는 active 집합에 없는 경우 제외 (stale/잔존 노드 방지)
            if (panelIdObj != null) {
                try {
                    Long nodePanelId = Long.valueOf(panelIdObj.toString());
                    if (!activePanelIds.contains(nodePanelId)) {
                        continue;
                    }
                } catch (Exception ex) {
                    continue;
                }
            }
            javafx.geometry.Bounds b = node.getBoundsInParent();
            double x = b.getMinX();
            double y = b.getMinY();
            double w = Math.max(1, b.getWidth());
            double h = Math.max(1, b.getHeight());
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
            counted++;
        }

        // 2) 아직 노드가 없으면 서버 패널 좌표로 fallback
        if (counted == 0) {
            for (DashboardPanelDto panel : cachedPanels) {
                // visible 플래그는 응답 누락 시 false 기본값이 될 수 있어 위치 계산에서는 제외 조건으로 쓰지 않는다.
                if (panel == null || panel.getX() == null || panel.getY() == null) {
                    continue;
                }
                double x = panel.getX();
                double y = panel.getY();
                double w = panel.getWidth() != null && panel.getWidth() > 0 ? panel.getWidth() : 320;
                double h = panel.getHeight() != null && panel.getHeight() > 0 ? panel.getHeight() : 220;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
                counted++;
            }
        }

        if (counted == 0) {
            dashboardViewModel.setZoom(1.0);
            if (markInitialApplied) {
                initialFitApplied = true;
            }
            return true;
        }

        // 콘텐츠 경계 기반으로만 맞춤 계산한다. (뷰포트 배율 기반 가짜 캔버스 확장 제거)
        final double padding = 20.0;
        double contentW = Math.max(1, (maxX - minX) + padding * 2.0);
        double contentH = Math.max(1, (maxY - minY) + padding * 2.0);
        double viewportW = Math.max(1, dashboardScrollPane.getViewportBounds().getWidth());
        double viewportH = Math.max(1, dashboardScrollPane.getViewportBounds().getHeight());

        double targetScale = normalizeFitValue(Math.min(viewportW / contentW, viewportH / contentH));

        String fitSignature = String.format(
                "%.3f|%.3f|%.3f|%.3f|%.3f|%.3f|%d",
                normalizeFitValue(minX),
                normalizeFitValue(minY),
                normalizeFitValue(maxX),
                normalizeFitValue(maxY),
                normalizeFitValue(viewportW),
                normalizeFitValue(viewportH),
                counted
        );
        if (fitSignature.equals(lastFitSignature)
                && Double.isFinite(lastFitScale)
                && Double.isFinite(lastFitHValue)
                && Double.isFinite(lastFitVValue)) {
            dashboardViewModel.setZoom(lastFitScale);
            if (Double.isFinite(lastFitContainerW) && Double.isFinite(lastFitContainerH)) {
                classroomContainer.setPrefSize(lastFitContainerW, lastFitContainerH);
                if (canvasModule != null && canvasModule.getGridCanvas() != null) {
                    canvasModule.getGridCanvas().setWidth(lastFitContainerW);
                    canvasModule.getGridCanvas().setHeight(lastFitContainerH);
                }
            }
            applyViewportPosition(lastFitHValue, lastFitVValue);
            if (markInitialApplied) {
                initialFitApplied = true;
            }
            if (showUserToast) {
                dashboardUIService.showToast("전체 맞춤 적용", false);
            }
            return true;
        }

        dashboardViewModel.setZoom(targetScale);

        double currentScale = dashboardViewModel.getZoomScale();
        if (currentScale <= 0) {
            return false;
        }

        // 콘텐츠 중심이 뷰포트 중심에 오도록 캔버스를 대칭 크기로 설정한다.
        // 캔버스를 콘텐츠 중심 기준으로 좌우/상하 대칭으로 잡으면
        // scroll hVal=0.5, vVal=0.5 로 정확히 중앙 정렬할 수 있다.
        double contentCenterX = (minX + maxX) / 2.0;
        double contentCenterY = (minY + maxY) / 2.0;

        // 캔버스 크기: 콘텐츠 중심 기준 대칭 + 모든 콘텐츠를 포함 + 뷰포트 채우기
        double contentBaseW = Math.max(maxX + padding, contentCenterX * 2.0);
        double contentBaseH = Math.max(maxY + padding, contentCenterY * 2.0);
        // 캔버스 * zoom > viewport 보장하여 스크롤이 실제로 동작하도록 함
        contentBaseW = Math.max(contentBaseW, viewportW / currentScale + 1.0);
        contentBaseH = Math.max(contentBaseH, viewportH / currentScale + 1.0);

        // 비정상적인 대형 레이아웃으로 UI가 버벅이는 것을 방지하기 위한 안전 상한.
        double hardMaxW = Math.max(viewportW / currentScale * 6.0, 12000.0);
        double hardMaxH = Math.max(viewportH / currentScale * 6.0, 9000.0);
        contentBaseW = normalizeFitValue(Math.min(contentBaseW, hardMaxW));
        contentBaseH = normalizeFitValue(Math.min(contentBaseH, hardMaxH));

        classroomContainer.setPrefSize(contentBaseW, contentBaseH);
        if (canvasModule != null && canvasModule.getGridCanvas() != null) {
            canvasModule.getGridCanvas().setWidth(contentBaseW);
            canvasModule.getGridCanvas().setHeight(contentBaseH);
        }

        double contentScaledW = contentBaseW * currentScale;
        double contentScaledH = contentBaseH * currentScale;
        double hRangePx = contentScaledW - viewportW;
        double vRangePx = contentScaledH - viewportH;

        // 콘텐츠 중심을 뷰포트 중심에 맞추는 스크롤 값 계산
        final double hVal = normalizeScrollValue(hRangePx > 1 ? Math.max(0, Math.min(1, (contentCenterX * currentScale - viewportW / 2.0) / hRangePx)) : 0.0);
        final double vVal = normalizeScrollValue(vRangePx > 1 ? Math.max(0, Math.min(1, (contentCenterY * currentScale - viewportH / 2.0) / vRangePx)) : 0.0);

        logFitAllSnapshot(
            counted,
            minX,
            minY,
            maxX,
            maxY,
            contentW,
            contentH,
            viewportW,
            viewportH,
            targetScale,
            currentScale,
            contentBaseW,
            contentBaseH,
            contentScaledW,
            contentScaledH,
            hRangePx,
            vRangePx,
            hVal,
            vVal
        );

        applyViewportPosition(hVal, vVal);

        lastFitSignature = fitSignature;
        lastFitScale = currentScale;
        lastFitHValue = hVal;
        lastFitVValue = vVal;
        lastFitContainerW = contentBaseW;
        lastFitContainerH = contentBaseH;
        if (markInitialApplied) {
            initialFitApplied = true;
        }

        if (showUserToast) {
            dashboardUIService.showToast("전체 맞춤 적용", false);
        }
        return true;
    }

    private void triggerInitialFitIfNeeded() {
        if (initialFitApplied || !isDashboardWindowShowing()) {
            return;
        }
        PauseTransition fitDelay = new PauseTransition(Duration.millis(180));
        fitDelay.setOnFinished(ev -> {
            boolean applied = fitAllPanelsToViewport(false, true);
            if (!applied && !initialFitApplied) {
                PauseTransition retry = new PauseTransition(Duration.millis(220));
                retry.setOnFinished(retryEv -> triggerInitialFitIfNeeded());
                retry.play();
            }
        });
        fitDelay.play();
    }

    private void applyViewportPosition(double hVal, double vVal) {
        Platform.runLater(() -> {
            dashboardScrollPane.setHvalue(hVal);
            dashboardScrollPane.setVvalue(vVal);
            Platform.runLater(() -> {
                dashboardScrollPane.setHvalue(hVal);
                dashboardScrollPane.setVvalue(vVal);
            });
        });
    }

    private double normalizeFitValue(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private double normalizeScrollValue(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private void scheduleInitialViewportAdjust() {
        if (!pendingInitialViewportAdjust || dashboardScrollPane == null || classroomContainer == null) {
            return;
        }
        viewportAdjustDebounce.playFromStart();
        // 로그인 직후 첫 pulse 타이밍에서 viewport가 아직 0인 경우가 있어
        // 다음 pulse들에서도 재시도하여 창 리사이즈 없이 초기 크기가 잡히도록 보장한다.
        Platform.runLater(() -> {
            if (pendingInitialViewportAdjust) {
                viewportAdjustDebounce.playFromStart();
            }
            Platform.runLater(() -> {
                if (pendingInitialViewportAdjust) {
                    viewportAdjustDebounce.playFromStart();
                }
            });
        });
    }

    private void expandCanvasToContentBounds() {
        if (classroomContainer == null || canvasModule == null) {
            return;
        }

        List<Node> nodes = getAllCanvasPanels();

        double maxX = 0.0;
        double maxY = 0.0;
        for (Node node : nodes) {
            if (node == null || !node.isVisible()) {
                continue;
            }
            javafx.geometry.Bounds b = node.getBoundsInParent();
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }

        double viewportW = dashboardScrollPane != null ? dashboardScrollPane.getViewportBounds().getWidth() : 0.0;
        double viewportH = dashboardScrollPane != null ? dashboardScrollPane.getViewportBounds().getHeight() : 0.0;
        double padding = 140.0;

        // 최소 viewport 크기는 항상 보장하고, 콘텐츠가 넘으면 확장
        double minW = Math.max(1.0, viewportW / Math.max(0.2, currentZoomScale()));
        double minH = Math.max(1.0, viewportH / Math.max(0.2, currentZoomScale()));
        double targetW = Math.max(maxX + padding, minW);
        double targetH = Math.max(maxY + padding, minH);

        // 기존 크기보다 작아지지는 않되, 콘텐츠에 맞게 확장
        double newW = Math.max(classroomContainer.getPrefWidth(), targetW);
        double newH = Math.max(classroomContainer.getPrefHeight(), targetH);

        if (Math.abs(newW - classroomContainer.getPrefWidth()) > 1
                || Math.abs(newH - classroomContainer.getPrefHeight()) > 1) {
            classroomContainer.setPrefSize(newW, newH);
            if (canvasModule.getGridCanvas() != null) {
                canvasModule.getGridCanvas().setWidth(newW);
                canvasModule.getGridCanvas().setHeight(newH);
            }
        }
    }

    private void updateZoomButtonLabel(double scale) {
        if (zoomResetButton == null) {
            return;
        }
        zoomResetButton.setText(String.format("%.0f%%", scale * 100.0));
    }

    private double[] getResponsiveDefaultSize(String type) {
        double sceneWidth = rootVBox != null && rootVBox.getScene() != null ? rootVBox.getScene().getWidth() : 1600;
        if (dashboardUIService != null) {
            return dashboardUIService.getResponsiveDefaultSize(type, sceneWidth);
        }
        boolean large = sceneWidth >= 1800;
        boolean medium = sceneWidth >= 1400;

        if ("NETWORK".equals(type)) {
            return large ? new double[]{720, 360}
                    : medium ? new double[]{620, 320}
                    : new double[]{520, 280};
        }
        if ("DISCONNECT".equals(type)) {
            return large ? new double[]{680, 360}
                : medium ? new double[]{600, 320}
                : new double[]{500, 280};
        }
        return large ? new double[]{440, 320}
                : medium ? new double[]{380, 300}
                : new double[]{320, 260};
    }

    private double applySnap(double value) {
        if (!isSnapToGridEnabled()) {
            return value;
        }
        return Math.round(value / DashboardViewModel.GRID_SIZE) * DashboardViewModel.GRID_SIZE;
    }

    private void handleGroupDrop(Long groupId, double x, double y) {
        if (!ensureWritePermission("패널 배치 변경")) {
            return;
        }
        if (groupId == null || classroomModule == null) {
            return;
        }
        ClassroomSummaryDto selected = dashboardViewModel.getCachedClassrooms().stream()
                .filter(c -> c.getGroupId() != null && c.getGroupId().equals(groupId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            dashboardUIService.showToast("강의실 정보를 찾을 수 없습니다.", true);
            return;
        }

        List<DashboardPanelDto> panels = dashboardViewModel.getCachedDashboardPanels();
        DashboardPanelDto existing = panels.stream()
                .filter(p -> "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType()))
                .filter(p -> p.getRefGroupId() != null && p.getRefGroupId().equals(groupId))
                .findFirst()
                .orElse(null);

        if (existing != null && currentDashboardId() != null) {
            double w = existing.getWidth() != null ? existing.getWidth() : 360.0;
            double h = existing.getHeight() != null ? existing.getHeight() : 280.0;
            dashboardService.updatePanelPositionAsync(currentDashboardId(), existing.getId(), x, y, w, h);
            updateCachedPanelPosition(existing.getId(), x, y, w, h);
            refreshPanelListAndDashboard();
            return;
        }

        double[] size = getResponsiveDefaultSize("CLASSROOM");
        classroomModule.addClassroomPanel(selected, x, y, size[0], size[1]);
    }

    private void handleTextDrop(Long id, double x, double y) {
        if (!ensureWritePermission("텍스트 패널 이동")) {
            return;
        }
        if (id == null || widgetModule == null) {
            return;
        }
        widgetModule.updateTextItemPosition(id, x, y, 280.0, 180.0, true);
        widgetModule.loadTextItems();
    }

    private void handleImageDrop(Long id, double x, double y) {
        if (!ensureWritePermission("이미지 패널 이동")) {
            return;
        }
        if (id == null || widgetModule == null) {
            return;
        }
        widgetModule.updateImageItemPosition(id, x, y, 360.0, 240.0, true);
        widgetModule.loadImageItems();
    }

    private void arrangePanels(String mode) {
        if (!ensureWritePermission("패널 배열")) {
            return;
        }
        if (!isEditModeActive()) {
            dashboardUIService.showToast("편집 모드에서만 배열 기능을 사용할 수 있습니다.", true);
            return;
        }

        List<Node> nodes = getAllCanvasPanels();
        if (nodes.isEmpty()) {
            return;
        }

        double startX = 40;
        double startY = 40;
        double gap = 24;

        if ("GRID".equals(mode)) {
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(nodes.size())));
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                int row = i / cols;
                int col = i % cols;
                double x = startX + col * (Math.max(260, node.prefWidth(-1)) + gap);
                double y = startY + row * (Math.max(180, node.prefHeight(-1)) + gap);
                node.setLayoutX(applySnap(x));
                node.setLayoutY(applySnap(y));
                saveNodePosition(node);
            }
        } else if ("ROW".equals(mode)) {
            double x = startX;
            for (Node node : nodes) {
                node.setLayoutX(applySnap(x));
                node.setLayoutY(applySnap(startY));
                x += Math.max(260, node.prefWidth(-1)) + gap;
                saveNodePosition(node);
            }
        } else {
            double y = startY;
            for (Node node : nodes) {
                node.setLayoutX(applySnap(startX));
                node.setLayoutY(applySnap(y));
                y += Math.max(180, node.prefHeight(-1)) + gap;
                saveNodePosition(node);
            }
        }

        dashboardUIService.showToast("배열 완료: " + mode, false);
    }

    private List<Node> getAllCanvasPanels() {
        java.util.LinkedHashSet<Node> nodes = new java.util.LinkedHashSet<>();

        if (classroomContainer != null) {
            for (Node child : classroomContainer.getChildren()) {
                if (child == null) {
                    continue;
                }
                if (canvasModule != null && (child == canvasModule.getGridCanvas() || child == canvasModule.getSelectionRect())) {
                    continue;
                }
                nodes.add(child);
            }
        }

        // Legacy registries are kept as fallback/dedup support.
        panelManager.getPanels().forEach(p -> nodes.add(p.getPanel()));
        nodes.addAll(textItemMap.values());
        nodes.addAll(imageItemMap.values());
        if (networkPanelModule != null) {
            nodes.addAll(networkPanelModule.getPanelViews());
        }
        if (disconnectStatusPanelModule != null) {
            nodes.addAll(disconnectStatusPanelModule.getPanelViews());
        }
        return new ArrayList<>(nodes);
    }

    private boolean resizeCanvasToViewport() {
        if (canvasModule == null || dashboardScrollPane == null) {
            return false;
        }

        double viewportW = dashboardScrollPane.getViewportBounds().getWidth();
        double viewportH = dashboardScrollPane.getViewportBounds().getHeight();
        if (viewportW <= 1 || viewportH <= 1) {
            return false;
        }

        // 초기 기준 크기는 현재 viewport와 동일하게 설정한다.
        double targetW = Math.max(1.0, viewportW);
        double targetH = Math.max(1.0, viewportH);
        canvasModule.getClassroomContainer().setPrefSize(targetW, targetH);
        if (canvasModule.getGridCanvas() != null) {
            canvasModule.getGridCanvas().setWidth(targetW);
            canvasModule.getGridCanvas().setHeight(targetH);
        }
        return true;
    }

    private void logViewportSnapshot(String reason) {
        // Diagnostic logging disabled for production
        if (true) return;
        if (!log.isInfoEnabled() || viewportSnapshotCount >= 12) {
            return;
        }
        viewportSnapshotCount++;

        double sceneW = rootVBox != null && rootVBox.getScene() != null ? rootVBox.getScene().getWidth() : -1;
        double sceneH = rootVBox != null && rootVBox.getScene() != null ? rootVBox.getScene().getHeight() : -1;
        double rootW = rootVBox != null ? rootVBox.getWidth() : -1;
        double rootH = rootVBox != null ? rootVBox.getHeight() : -1;
        double splitW = mainSplitPane != null ? mainSplitPane.getWidth() : -1;
        double splitH = mainSplitPane != null ? mainSplitPane.getHeight() : -1;
        double rightW = rightToolPane != null ? rightToolPane.getWidth() : -1;
        double rightH = rightToolPane != null ? rightToolPane.getHeight() : -1;
        double viewportW = dashboardScrollPane != null ? dashboardScrollPane.getViewportBounds().getWidth() : -1;
        double viewportH = dashboardScrollPane != null ? dashboardScrollPane.getViewportBounds().getHeight() : -1;
        double canvasPrefW = classroomContainer != null ? classroomContainer.getPrefWidth() : -1;
        double canvasPrefH = classroomContainer != null ? classroomContainer.getPrefHeight() : -1;
        double canvasParentW = classroomContainer != null ? classroomContainer.getBoundsInParent().getWidth() : -1;
        double canvasParentH = classroomContainer != null ? classroomContainer.getBoundsInParent().getHeight() : -1;

        log.info(
                "[dashboard-layout] reason={} scene={}x{} root={}x{} split={}x{} rightPane={}x{} viewport={}x{} canvasPref={}x{} canvasParent={}x{} zoom={} pendingInitial={}",
                reason,
                fmt(sceneW), fmt(sceneH),
                fmt(rootW), fmt(rootH),
                fmt(splitW), fmt(splitH),
                fmt(rightW), fmt(rightH),
                fmt(viewportW), fmt(viewportH),
                fmt(canvasPrefW), fmt(canvasPrefH),
                fmt(canvasParentW), fmt(canvasParentH),
                fmt(currentZoomScale()),
                pendingInitialViewportAdjust
        );
    }

    private void logFitAllSnapshot(
            int counted,
            double minX,
            double minY,
            double maxX,
            double maxY,
            double contentW,
            double contentH,
            double viewportW,
            double viewportH,
            double targetScale,
            double currentScale,
            double contentBaseW,
            double contentBaseH,
            double contentScaledW,
            double contentScaledH,
            double hRangePx,
            double vRangePx,
            double hVal,
            double vVal
    ) {
        if (!log.isInfoEnabled() || fitAllSnapshotCount >= 10) {
            return;
        }
        fitAllSnapshotCount++;

        log.info(
                "[dashboard-fitAll] count={} bounds=({},{})->({},{}) content={}x{} viewport={}x{} targetScale={} appliedScale={} base={}x{} scaled={}x{} ranges={}x{} scroll={}x{}",
                counted,
                fmt(minX), fmt(minY), fmt(maxX), fmt(maxY),
                fmt(contentW), fmt(contentH),
                fmt(viewportW), fmt(viewportH),
                fmt(targetScale), fmt(currentScale),
                fmt(contentBaseW), fmt(contentBaseH),
                fmt(contentScaledW), fmt(contentScaledH),
                fmt(hRangePx), fmt(vRangePx),
                fmt(hVal), fmt(vVal)
        );
    }

    private String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        return String.format("%.1f", value);
    }

    private void updateTopSummary() {
        if (statusSummaryLabel == null || connectivityLabel == null) {
            return;
        }
        List<ClassroomSummaryDto> classrooms = dashboardViewModel.getCachedClassrooms();
        int classroomCount = classrooms.size();
        long online = classrooms.stream().mapToLong(ClassroomSummaryDto::getOnlinePcs).sum();
        long total = classrooms.stream().mapToLong(ClassroomSummaryDto::getTotalPcs).sum();
        statusSummaryLabel.setText("강의실 " + classroomCount + ", 온라인 " + online + "/" + total);
        connectivityLabel.setText("연결됨");
        connectivityLabel.setTextFill(Color.web("#86efac"));
    }

    private DashboardViewModel viewModel() {
        if (dashboardViewModel == null) {
            dashboardViewModel = new DashboardViewModel();
        }
        return dashboardViewModel;
    }

    private Long currentDashboardId() {
        return viewModel().getSelectedDashboardId();
    }

    private void setCurrentDashboardId(Long id) {
        viewModel().setSelectedDashboardId(id);
    }

    private boolean isEditModeActive() {
        return viewModel().isEditMode();
    }

    private boolean isSnapToGridEnabled() {
        return viewModel().isSnapToGrid();
    }

    private double currentZoomScale() {
        return viewModel().getZoomScale();
    }

    private boolean isDashboardOnlyModeActive() {
        return viewModel().isDashboardOnlyMode();
    }

    private boolean isFocusModeActive() {
        return viewModel().isFocusMode();
    }

    @FXML
    private void handleRefreshButtonAction() {
        refreshDashboardSnapshot();
    }

    @FXML
    private void handleGeneratePdf() {
        try {
            new com.example.frontend.service.DashboardPdfService().generateTrafficPdf(rootVBox.getScene().getWindow());
        } catch (Exception e) {
            showAlert("PDF 생성 실패", "PDF 생성 중 오류가 발생했습니다.\n" + e.getMessage());
        }
    }

    private void showAlert(String title, String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(msg);
            alert.showAndWait();
        });
    }

    public void shutdown() {
        if (shutdownRequested) {
            return;
        }
        shutdownRequested = true;
        ApiClient.getInstance().setConnectionFailureHandler(null);
        ApiClient.getInstance().setAuthFailureHandler(null);
        scheduler.shutdownNow();
        if (realtimeModule != null) {
            realtimeModule.stop();
        }
        if (commandStatusPanelController != null) {
            commandStatusPanelController.shutdown();
        }
    }

    private boolean isDashboardWindowShowing() {
        return rootVBox != null
                && rootVBox.getScene() != null
                && rootVBox.getScene().getWindow() != null
                && rootVBox.getScene().getWindow().isShowing();
    }

    private void triggerInitialFitAfterWindowShown() {
        if (!awaitingInitialWindowShow) {
            return;
        }
        awaitingInitialWindowShow = false;
        pendingInitialViewportAdjust = true;
        // 다중 pulse에 걸쳐 레이아웃 안정화 보장
        Platform.runLater(() -> {
            if (rootVBox != null) {
                rootVBox.applyCss();
                rootVBox.requestLayout();
                rootVBox.layout();
            }
            viewportAdjustDebounce.playFromStart();
            // 두 번째 pulse: 일부 환경에서 첫 pulse에서 viewport 크기가 아직 0인 경우 대비
            Platform.runLater(() -> {
                if (rootVBox != null) {
                    rootVBox.requestLayout();
                }
                if (pendingInitialViewportAdjust) {
                    viewportAdjustDebounce.playFromStart();
                }
            });
        });
    }

    private void registerWindowLifecycleHooks(Window window) {
        if (window == null) {
            return;
        }
        if (!Boolean.TRUE.equals(window.getProperties().get("dashboardWindowHooksRegistered"))) {
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> shutdown());
            window.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> triggerInitialFitAfterWindowShown());
            window.getProperties().put("dashboardWindowHooksRegistered", true);
        }
        if (window.isShowing()) {
            triggerInitialFitAfterWindowShown();
        }
    }
}
