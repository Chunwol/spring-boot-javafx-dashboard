package com.example.frontend.controller;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.DashboardRefreshDto;
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
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.DashboardService;
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
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Scale;
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

    private static final double GRID_SIZE = 20.0;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @FXML
    private VBox rootVBox;

    private DashboardCanvasModule canvasModule;
    private DashboardClassroomModule classroomModule;
    private DashboardManagementModule managementModule;
    private DashboardRealtimeModule realtimeModule;
    private DashboardWidgetModule widgetModule;
    private DashboardResourcePanelModule resourcePanelModule;
    private DashboardDisconnectStatusPanelModule disconnectStatusPanelModule;
    private DashboardViewModel dashboardViewModel;
    private DashboardUIService dashboardUIService;
    private final DashboardInteractionService dashboardInteractionService = new DashboardInteractionService();

    private Pane classroomContainer;
    private ScrollPane dashboardScrollPane;
    private Label clockLabel;
    private Label statusSummaryLabel;
    private Label connectivityLabel;
    private VBox toastContainer;
    private HBox editOnlyToolbar;
    private HBox topBarRef;
    private SplitPane mainSplitPane;
    private VBox leftToolPane;
    private VBox rightToolPane;
    private Node leftEditControls;
    private Button zoomResetButton;
    private boolean pendingInitialViewportAdjust = true;
    private final PauseTransition viewportAdjustDebounce = new PauseTransition(Duration.millis(90));
    private int viewportSnapshotCount = 0;
    private int fitAllSnapshotCount = 0;
    private boolean awaitingInitialWindowShow = true;
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

    @FXML
    public void initialize() {
        dashboardViewModel = new DashboardViewModel();

        if (rootVBox == null) {
            return;
        }

        rootVBox.getChildren().clear();
        rootVBox.setSpacing(10);
        rootVBox.setPadding(new Insets(8, 8, 8, 8));
        rootVBox.setStyle("-fx-background-color: linear-gradient(to bottom, #f0f4ff, #f8fbff 45%, #ffffff 100%);");

        rootVBox.getChildren().add(buildTopBar());
        rootVBox.getChildren().add(buildTabs());

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
            // 패널 CSS/폰트 렌더링이 완료된 후 전체맞춤 적용.
            // 즉시 실행하면 bounds가 부정확하므로 짧은 딜레이 후 실행 + 검증 패스 추가.
            PauseTransition fitDelay = new PauseTransition(Duration.millis(150));
            fitDelay.setOnFinished(ev -> {
                fitAllPanelsToViewport(false);
                // 검증 패스: CSS/폰트 late-layout 후 bounds가 바뀌었을 수 있으므로 재계산
                PauseTransition verifyPass = new PauseTransition(Duration.millis(250));
                verifyPass.setOnFinished(ev2 -> fitAllPanelsToViewport(false));
                verifyPass.play();
            });
            fitDelay.play();
        });

        rootVBox.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.widthProperty().removeListener(sceneWidthListener);
                oldScene.heightProperty().removeListener(sceneHeightListener);
            }
            if (newScene != null) {
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

        scheduler.scheduleAtFixedRate(this::refreshDashboardSnapshot, 0, 15, TimeUnit.SECONDS);

        connectWebSocket();
        loadDashboards();
    }

    private Node buildTopBar() {
        HBox topBar = new HBox(14);
        this.topBarRef = topBar;
        topBar.setPadding(new Insets(13, 18, 13, 18));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: linear-gradient(to right, #1e3a8a, #1f2937); -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.15), 12, 0, 0, 2);");

        Label title = new Label("통합 강의실 관제");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 20));

        Label subtitle = new Label("실시간 모니터링, 원격 제어, 리포팅");
        subtitle.setTextFill(Color.web("#cbd5e0"));
        subtitle.setFont(Font.font("Segoe UI", 12));
        VBox titleBox = new VBox(2, title, subtitle);

        dashboardSelector = new ComboBox<>();
        dashboardSelector.setPromptText("대시보드 선택");
        dashboardSelector.setPrefWidth(240);
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

        Button addDashboardBtn = createIconActionButton("+ 대시보드", "mdi2p-plus", "#15803d", "white");
        addDashboardBtn.setOnAction(e -> handleCreateDashboard());

        Button logoutBtn = createIconActionButton("로그아웃", "mdi2l-logout", "#b4535f", "white");
        logoutBtn.setOnAction(e -> handleLogout());

        statusSummaryLabel = new Label("강의실 0, 온라인 0/0");
        statusSummaryLabel.setTextFill(Color.web("#cbd5e0"));
        statusSummaryLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));

        connectivityLabel = new Label("연결 대기 중");
        connectivityLabel.setTextFill(Color.web("#cbd5e0"));
        connectivityLabel.setFont(Font.font("Segoe UI", 12));

        clockLabel = new Label();
        clockLabel.setTextFill(Color.WHITE);
        clockLabel.setFont(Font.font("Consolas", FontWeight.BOLD, 16));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBar.getChildren().addAll(
                titleBox,
                dashboardSelector,
                addDashboardBtn,
                logoutBtn,
                statusSummaryLabel,
                connectivityLabel,
                spacer,
                clockLabel
        );
        return topBar;
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
                ApiClient.getInstance().setBearerToken(null);
                var fxmlUrl = getClass().getResource("/fxml/login.fxml");
                if (fxmlUrl == null) {
                    showToast("로그인 화면을 찾을 수 없습니다.", true);
                    return;
                }

                FXMLLoader loader = new FXMLLoader(fxmlUrl);
                Parent loginRoot = loader.load();
                LoginController loginController = loader.getController();

                if (rootVBox == null || rootVBox.getScene() == null || !(rootVBox.getScene().getWindow() instanceof Stage stage)) {
                    showToast("윈도우 핸들을 찾을 수 없습니다.", true);
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
            } catch (Exception ex) {
                showToast("로그아웃 처리 실패: " + ex.getMessage(), true);
            }
        });
    }

    private Node buildTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-insets: 0; -fx-padding: 0 6 6 6; -fx-background-color: #f8fafc;");

        Tab mainTab = new Tab("메인 대시보드", createMainDashboardView());

        networkPanelController = new NetworkPanelController(null);
        Tab networkTab = new Tab("네트워크 트래픽", networkPanelController.getPanel());

        commandStatusPanelController = new CommandStatusPanelController();
        Tab commandTab = new Tab("명령 상태", commandStatusPanelController.getPanel());

        Tab manageTab = new Tab("관리", createManagementView());
        manageTab.setOnSelectionChanged(event -> {
            if (manageTab.isSelected() && managementModule != null) {
                managementModule.reloadData();
            }
        });

        Tab pcManageTab = new Tab("PC 관리", createPcManagementView());
        pcManageTab.setOnSelectionChanged(event -> {
            if (pcManageTab.isSelected() && pcManagementModule != null) {
                pcManagementModule.reloadData();
            }
        });

        tabPane.getTabs().addAll(mainTab, networkTab, commandTab, manageTab, pcManageTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        return tabPane;
    }

    private Node createManagementView() {
        managementModule = new DashboardManagementModule(
                httpClient,
                objectMapper,
            currentApiBaseUrl(),
                () -> rootVBox.getScene()
        );
        return managementModule.createView();
    }

    private Node createPcManagementView() {
        pcManagementModule = new DashboardPcManagementModule(
                httpClient,
                objectMapper,
            currentApiBaseUrl()
        );
        return pcManagementModule.createView();
    }

    private Node createMainDashboardView() {
        SplitPane splitPane = new SplitPane();
        this.mainSplitPane = splitPane;
        splitPane.setStyle("-fx-background-color: transparent;");
        VBox rightPane = buildRightPane();
        this.rightToolPane = rightPane;

        widgetModule = new DashboardWidgetModule(
                httpClient,
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
                httpClient,
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

        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.20);

            dashboardUIService = new DashboardUIService(
                toastContainer,
                classroomContainer,
                dashboardScrollPane,
                canvasModule,
                editOnlyToolbar,
                leftEditControls
            );

            networkPanelModule = new DashboardNetworkPanelModule(
                dashboardService,
                classroomContainer,
                dashboardInteractionService,
                this::isEditModeActive,
                this::applySnap,
                this::currentDashboardId,
                this::showToast,
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
                this::showToast,
                this::getResponsiveDefaultSize,
                this::loadDashboardPanels
            );

        applyEditModeUiState();
        return splitPane;
    }

    private VBox buildLeftPane() {
        VBox leftPane = resourcePanelModule.buildView();
        leftEditControls = resourcePanelModule.getEditControls();
        return leftPane;
    }

    private VBox buildRightPane() {
        VBox rightPane = new VBox(10);
        rightPane.setPadding(new Insets(4, 8, 8, 8));

        ToggleButton editModeBtn = createEditModeToggleButton();
        ToggleButton focusModeBtn = createFocusModeToggleButton();
        ToggleButton dashboardOnlyBtn = createDashboardOnlyToggleButton();
        Button fullScreenBtn = createFullScreenButton();
        Button refreshBtn = createRefreshButton();
        ColorPicker bgColorPicker = createBackgroundColorPicker();

        ToggleButton gridToggleBtn = createGridToggleButton();
        ToggleButton snapToggleBtn = createSnapToggleButton();
        MenuButton alignMenu = createAlignMenu();
        MenuButton arrangeMenu = createArrangeMenu();
        Button zoomOutBtn = createZoomOutButton();
        Button zoomResetBtn = createZoomResetButton();
        Button zoomInBtn = createZoomInButton();
        Button fitAllBtn = createFitAllButton();

        editOnlyToolbar = new HBox(
                8,
                gridToggleBtn,
                snapToggleBtn,
                alignMenu,
                arrangeMenu
        );
        editOnlyToolbar.setManaged(false);
        editOnlyToolbar.setVisible(false);

        HBox zoomToolbar = new HBox(4, zoomOutBtn, zoomResetBtn, zoomInBtn, fitAllBtn);
        zoomToolbar.setAlignment(Pos.CENTER_RIGHT);

        HBox rightToolbar = new HBox(
                10,
                bgColorPicker,
                editModeBtn,
                focusModeBtn,
                fullScreenBtn,
                dashboardOnlyBtn,
                editOnlyToolbar,
                zoomToolbar,
                refreshBtn
        );
        rightToolbar.setAlignment(Pos.CENTER_RIGHT);
        rightToolbar.setPadding(new Insets(8, 8, 8, 8));
        rightToolbar.setMinHeight(Region.USE_PREF_SIZE);
        rightToolbar.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 12;");

        canvasModule = new DashboardCanvasModule(
                this::isEditModeActive,
                this::saveNodePosition,
                this::handleGroupDrop,
                this::handleTextDrop,
                this::handleImageDrop
        );

        classroomContainer = canvasModule.getClassroomContainer();
        classroomContainer.setStyle("-fx-background-color: linear-gradient(to bottom right, #f8fbff, #f0f7ff);");
        applyZoom(currentZoomScale());

        // Group 래퍼: Scale(0,0) 트랜스폼이 적용된 classroomContainer의 실제 시각적 크기를
        // ScrollPane이 인식하도록 Group으로 감쌉니다.
        Group canvasGroup = new Group(classroomContainer);
        ScrollPane canvasScroll = new ScrollPane(canvasGroup);
        dashboardScrollPane = canvasScroll;
        canvasScroll.setFitToWidth(false);
        canvasScroll.setFitToHeight(false);
        canvasScroll.setPannable(true);
        canvasScroll.setStyle("-fx-background: #f8fbff; -fx-background-color: #f8fbff;");
        canvasScroll.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            if (pendingInitialViewportAdjust && newBounds != null && newBounds.getWidth() > 1 && newBounds.getHeight() > 1) {
                logViewportSnapshot("viewport-bounds-changed");
                viewportAdjustDebounce.playFromStart();
            }
        });
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);

        toastContainer = new VBox(8);
        toastContainer.setPickOnBounds(false);
        toastContainer.setMouseTransparent(true);
        toastContainer.setPadding(new Insets(12));
        StackPane.setAlignment(toastContainer, Pos.TOP_RIGHT);

        StackPane canvasStack = new StackPane(canvasScroll, toastContainer);
        VBox.setVgrow(canvasStack, Priority.ALWAYS);

        rightPane.getChildren().addAll(rightToolbar, canvasStack);
        return rightPane;
    }

    private ToggleButton createEditModeToggleButton() {
        ToggleButton editModeBtn = new ToggleButton("편집 모드");
        editModeBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);");
        editModeBtn.selectedProperty().addListener((obs, ov, nv) -> editModeBtn.setStyle(nv ? "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #1e3a8a; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(30,58,138,0.35), 6, 0, 0, 2);" : "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);"));
        editModeBtn.setOnAction(e -> {
            dashboardViewModel.setEditMode(editModeBtn.isSelected());
            applyEditModeUiState();
            showToast(isEditModeActive() ? "편집 모드 켜짐" : "편집 모드 꺼짐", false);
        });
        return editModeBtn;
    }

    private ToggleButton createFocusModeToggleButton() {
        ToggleButton focusModeBtn = new ToggleButton("포커스 모드");
        focusModeBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);");
        focusModeBtn.selectedProperty().addListener((obs, ov, nv) -> focusModeBtn.setStyle(nv ? "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #1e3a8a; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(30,58,138,0.35), 6, 0, 0, 2);" : "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);"));
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
        return focusModeBtn;
    }

    private ToggleButton createDashboardOnlyToggleButton() {
        ToggleButton dashboardOnlyBtn = new ToggleButton("대시보드만");
        dashboardOnlyBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);");
        dashboardOnlyBtn.selectedProperty().addListener((obs, ov, nv) -> dashboardOnlyBtn.setStyle(nv ? "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #1e3a8a; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(30,58,138,0.35), 6, 0, 0, 2);" : "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 8 12 8 12; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);"));
        dashboardOnlyBtn.setSelected(isDashboardOnlyModeActive());
        dashboardOnlyBtn.setOnAction(e -> {
            dashboardViewModel.setDashboardOnlyMode(dashboardOnlyBtn.isSelected());
            if (leftToolPane != null) {
                leftToolPane.setManaged(!isDashboardOnlyModeActive());
                leftToolPane.setVisible(!isDashboardOnlyModeActive());
            }
            if (topBarRef != null) {
                topBarRef.setManaged(!isDashboardOnlyModeActive());
                topBarRef.setVisible(!isDashboardOnlyModeActive());
            }
            showToast(isDashboardOnlyModeActive() ? "대시보드만 모드" : "일반 모드", false);
        });
        return dashboardOnlyBtn;
    }

    private Button createFullScreenButton() {
        Button fullScreenBtn = createIconActionButton("전체 화면", "mdi2f-fullscreen", "#0891b2", "white");
        fullScreenBtn.setOnAction(e -> {
            if (rootVBox != null && rootVBox.getScene() != null && rootVBox.getScene().getWindow() instanceof Stage stage) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });
        return fullScreenBtn;
    }

    private Button createRefreshButton() {
        Button refreshBtn = createIconActionButton("새로고침", "mdi2r-refresh", "#0d9488", "white");
        refreshBtn.setOnAction(e -> {
            RotateTransition rotate = new RotateTransition(Duration.millis(450), refreshBtn);
            rotate.setByAngle(360);
            rotate.play();
            refreshDashboardSnapshot();
        });
        return refreshBtn;
    }

    private ColorPicker createBackgroundColorPicker() {
        ColorPicker bgColorPicker = new ColorPicker(Color.web("#f0f2f5"));
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
        return bgColorPicker;
    }

    private ToggleButton createGridToggleButton() {
        ToggleButton gridToggleBtn = new ToggleButton("격자");
        gridToggleBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);");
        gridToggleBtn.selectedProperty().addListener((obs, ov, nv) -> gridToggleBtn.setStyle(nv ? "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #64748b; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(100,116,139,0.3), 4, 0, 0, 1);" : "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);"));
        gridToggleBtn.setOnAction(e -> {
            if (canvasModule != null) {
                canvasModule.drawGrid(gridToggleBtn.isSelected());
            }
        });
        return gridToggleBtn;
    }

    private ToggleButton createSnapToggleButton() {
        ToggleButton snapToggleBtn = new ToggleButton("스냅");
        snapToggleBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);");
        snapToggleBtn.selectedProperty().addListener((obs, ov, nv) -> snapToggleBtn.setStyle(nv ? "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #64748b; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(100,116,139,0.3), 4, 0, 0, 1);" : "-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 600; -fx-padding: 6 10 6 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.08), 2, 0, 0, 1);"));
        snapToggleBtn.setSelected(isSnapToGridEnabled());
        snapToggleBtn.setOnAction(e -> dashboardViewModel.setSnapToGrid(snapToggleBtn.isSelected()));
        return snapToggleBtn;
    }

    private MenuButton createAlignMenu() {
        MenuButton alignMenu = new MenuButton("정렬");
        alignMenu.setStyle("-fx-font-size: 10px; -fx-padding: 5 10 5 10; -fx-background-radius: 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-font-weight: 600;");
        MenuItem alignLeft = new MenuItem("왼쪽");
        alignLeft.setOnAction(e -> canvasModule.alignSelectedNodes("LEFT"));
        MenuItem alignRight = new MenuItem("오른쪽");
        alignRight.setOnAction(e -> canvasModule.alignSelectedNodes("RIGHT"));
        MenuItem alignTop = new MenuItem("위");
        alignTop.setOnAction(e -> canvasModule.alignSelectedNodes("TOP"));
        MenuItem alignBottom = new MenuItem("아래");
        alignBottom.setOnAction(e -> canvasModule.alignSelectedNodes("BOTTOM"));
        alignMenu.getItems().addAll(alignLeft, alignRight, alignTop, alignBottom);
        return alignMenu;
    }

    private MenuButton createArrangeMenu() {
        MenuButton arrangeMenu = new MenuButton("배열");
        arrangeMenu.setStyle("-fx-font-size: 10px; -fx-padding: 5 10 5 10; -fx-background-radius: 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-font-weight: 600;");
        MenuItem gridArrange = new MenuItem("격자");
        gridArrange.setOnAction(e -> arrangePanels("GRID"));
        MenuItem rowArrange = new MenuItem("행");
        rowArrange.setOnAction(e -> arrangePanels("ROW"));
        MenuItem colArrange = new MenuItem("열");
        colArrange.setOnAction(e -> arrangePanels("COLUMN"));
        arrangeMenu.getItems().addAll(gridArrange, rowArrange, colArrange);
        return arrangeMenu;
    }

    private Button createZoomOutButton() {
        Button zoomOutBtn = new Button("-");
        zoomOutBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 5 8 5 8; -fx-background-color: #e2e8f0; -fx-text-fill: #334155;");
        zoomOutBtn.setOnAction(e -> applyZoom(currentZoomScale() - 0.1));
        return zoomOutBtn;
    }

    private Button createZoomResetButton() {
        Button zoomResetBtn = new Button("100%");
        zoomResetBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 5 10 5 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155;");
        zoomResetBtn.setOnAction(e -> applyZoom(1.0));
        zoomResetButton = zoomResetBtn;
        return zoomResetBtn;
    }

    private Button createZoomInButton() {
        Button zoomInBtn = new Button("+");
        zoomInBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 5 8 5 8; -fx-background-color: #e2e8f0; -fx-text-fill: #334155;");
        zoomInBtn.setOnAction(e -> applyZoom(currentZoomScale() + 0.1));
        return zoomInBtn;
    }

    private Button createFitAllButton() {
        Button fitAllBtn = new Button("전체 맞춤");
        fitAllBtn.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 5 10 5 10; -fx-background-color: #e2e8f0; -fx-text-fill: #334155;");
        fitAllBtn.setOnAction(e -> fitAllPanelsToViewport(true));
        return fitAllBtn;
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
                alertPayload -> showToast("실시간 알림 수신", false)
        );
    }

    private String currentApiBaseUrl() {
        return ApiClient.getInstance().getBaseUrl();
    }

    private void saveNodePosition(Node node) {
        if (node == null) {
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
        } finally {
            snapshotRefreshInFlight.set(false);
        }
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
            // 패널 노드 렌더링 후 추가 fitAll 보장 (위젯 로드 완료 대기)
            PauseTransition panelFitDelay = new PauseTransition(Duration.millis(400));
            panelFitDelay.setOnFinished(ev -> fitAllPanelsToViewport(false));
            panelFitDelay.play();
        }));
    }

    private void handleCreateDashboard() {
        TextInputDialog dialog = new TextInputDialog("새 대시보드");
        dialog.setTitle("대시보드 생성");
        dialog.setHeaderText("대시보드 이름 입력");
        dialog.setContentText("Name:");
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

    private void applyZoom(double targetScale) {
        double clamped = Math.max(0.2, Math.min(2.2, targetScale));
        dashboardViewModel.setZoom(clamped);
        double scale = currentZoomScale();
        if (dashboardUIService != null) {
            dashboardUIService.applyZoom(scale);
        } else if (classroomContainer != null) {
            classroomContainer.getTransforms().setAll(new Scale(scale, scale, 0, 0));
        }
        updateZoomButtonLabel(scale);
    }

    private void fitAllPanelsToViewport() {
        fitAllPanelsToViewport(true);
    }

    private void fitAllPanelsToViewport(boolean showUserToast) {
        if (dashboardScrollPane == null || classroomContainer == null) {
            return;
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
            applyZoom(1.0);
            return;
        }

        if (dashboardScrollPane.getViewportBounds().getWidth() <= 1 || dashboardScrollPane.getViewportBounds().getHeight() <= 1) {
            Platform.runLater(this::fitAllPanelsToViewport);
            return;
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
            applyZoom(1.0);
            return;
        }

        // 콘텐츠 경계 기반으로만 맞춤 계산한다. (뷰포트 배율 기반 가짜 캔버스 확장 제거)
        final double padding = 20.0;
        double contentW = Math.max(1, (maxX - minX) + padding * 2.0);
        double contentH = Math.max(1, (maxY - minY) + padding * 2.0);
        double viewportW = Math.max(1, dashboardScrollPane.getViewportBounds().getWidth());
        double viewportH = Math.max(1, dashboardScrollPane.getViewportBounds().getHeight());

        double targetScale = Math.min(viewportW / contentW, viewportH / contentH);
        applyZoom(targetScale);

        double currentScale = currentZoomScale();
        if (currentScale <= 0) {
            return;
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
        contentBaseW = Math.min(contentBaseW, hardMaxW);
        contentBaseH = Math.min(contentBaseH, hardMaxH);

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
        final double hVal = hRangePx > 1 ? Math.max(0, Math.min(1, (contentCenterX * currentScale - viewportW / 2.0) / hRangePx)) : 0.0;
        final double vVal = vRangePx > 1 ? Math.max(0, Math.min(1, (contentCenterY * currentScale - viewportH / 2.0) / vRangePx)) : 0.0;

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

        Platform.runLater(() -> {
            dashboardScrollPane.setHvalue(hVal);
            dashboardScrollPane.setVvalue(vVal);
            Platform.runLater(() -> {
                dashboardScrollPane.setHvalue(hVal);
                dashboardScrollPane.setVvalue(vVal);
            });
        });

        if (showUserToast) {
            showToast("전체 맞춤 적용", false);
        }
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
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    private void handleGroupDrop(Long groupId, double x, double y) {
        if (groupId == null || classroomModule == null) {
            return;
        }
        ClassroomSummaryDto selected = dashboardViewModel.getCachedClassrooms().stream()
                .filter(c -> c.getGroupId() != null && c.getGroupId().equals(groupId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            showToast("강의실 정보를 찾을 수 없습니다.", true);
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
        if (id == null || widgetModule == null) {
            return;
        }
        widgetModule.updateTextItemPosition(id, x, y, 280.0, 180.0, true);
        widgetModule.loadTextItems();
    }

    private void handleImageDrop(Long id, double x, double y) {
        if (id == null || widgetModule == null) {
            return;
        }
        widgetModule.updateImageItemPosition(id, x, y, 360.0, 240.0, true);
        widgetModule.loadImageItems();
    }

    private void arrangePanels(String mode) {
        if (!isEditModeActive()) {
            showToast("편집 모드에서만 배열 기능을 사용할 수 있습니다.", true);
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

        showToast("배열 완료: " + mode, false);
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

    private void showToast(String message, boolean error) {
        if (dashboardUIService != null) {
            dashboardUIService.showToast(message, error);
            return;
        }

        if (toastContainer == null) {
            return;
        }
        Label toast = new Label(message);
        toast.setStyle("-fx-background-color: " + (error ? "#d73a49" : "#22863a")
                + "; -fx-text-fill: white; -fx-padding: 8 12 8 12; -fx-background-radius: 8; -fx-font-weight: 600;");
        toastContainer.getChildren().add(0, toast);

        PauseTransition delay = new PauseTransition(Duration.seconds(2.2));
        delay.setOnFinished(e -> toastContainer.getChildren().remove(toast));
        delay.play();
    }

    private Button createActionButton(String text, String bgColor, String textColor) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + bgColor
                + "; -fx-text-fill: " + textColor
                + "; -fx-font-size: 11px; -fx-font-weight: 700; -fx-background-radius: 10; -fx-padding: 8 12 8 12; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.15), 4, 0, 0, 1);");
        return button;
    }

    private Button createIconActionButton(String text, String iconLiteral, String bgColor, String textColor) {
        Button button = createActionButton(text, bgColor, textColor);
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.setIconColor(Color.web(textColor));
        button.setGraphic(icon);
        return button;
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
            showAlert("PDF generation failed", "An error occurred while generating PDF.\n" + e.getMessage());
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
