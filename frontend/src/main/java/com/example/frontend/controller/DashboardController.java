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
import com.example.frontend.controller.dashboard.DashboardRealtimeModule;
import com.example.frontend.controller.dashboard.DashboardResourcePanelModule;
import com.example.frontend.controller.dashboard.DashboardUIService;
import com.example.frontend.controller.dashboard.DashboardViewModel;
import com.example.frontend.controller.dashboard.DashboardWidgetModule;
import com.example.frontend.controller.dashboard.DashboardNetworkPanelModule;
import com.example.frontend.controller.panel.CommandStatusPanelController;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.fxml.FXMLLoader;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

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

    @FXML
    private VBox rootVBox;

    private DashboardCanvasModule canvasModule;
    private DashboardClassroomModule classroomModule;
    private DashboardManagementModule managementModule;
    private DashboardRealtimeModule realtimeModule;
    private DashboardWidgetModule widgetModule;
    private DashboardResourcePanelModule resourcePanelModule;
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
    private VBox leftEditControls;

    private ComboBox<DashboardInfoDto> dashboardSelector;

    private NetworkPanelController networkPanelController;
    private CommandStatusPanelController commandStatusPanelController;
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

    private static final String API_BASE_URL = com.example.frontend.service.ApiClient.DEFAULT_BASE_URL;


    @FXML
    public void initialize() {
        dashboardViewModel = new DashboardViewModel();

        if (rootVBox == null) {
            return;
        }

        rootVBox.getChildren().clear();
        rootVBox.setSpacing(10);
        rootVBox.setStyle("-fx-background-color: linear-gradient(to bottom, #eef4ff, #f7fbff 45%, #ffffff 100%);");

        rootVBox.getChildren().add(buildTopBar());
        rootVBox.getChildren().add(buildTabs());

        rootVBox.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.widthProperty().addListener((o, oldW, newW) -> resizeCanvasBySceneWidth(newW.doubleValue()));
                newScene.windowProperty().addListener((wo, oldWindow, newWindow) -> registerWindowShutdownHook(newWindow));
                registerWindowShutdownHook(newScene.getWindow());
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
        HBox topBar = new HBox(12);
        this.topBarRef = topBar;
        topBar.setPadding(new Insets(12, 16, 12, 16));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #0f2b46; -fx-background-radius: 14;");

        Label title = new Label("Integrated Classroom NOC");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 20));

        Label subtitle = new Label("Realtime monitoring, remote control, reporting");
        subtitle.setTextFill(Color.web("#c4d7ee"));
        subtitle.setFont(Font.font("Segoe UI", 12));
        VBox titleBox = new VBox(2, title, subtitle);

        dashboardSelector = new ComboBox<>();
        dashboardSelector.setPromptText("Select dashboard");
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

        Button addDashboardBtn = createIconActionButton("+ Dashboard", "mdi2p-plus", "#16a34a", "white");
        addDashboardBtn.setOnAction(e -> handleCreateDashboard());

        Button logoutBtn = createIconActionButton("Logout", "mdi2l-logout", "#b91c1c", "white");
        logoutBtn.setOnAction(e -> handleLogout());

        statusSummaryLabel = new Label("Classrooms 0, Online 0/0");
        statusSummaryLabel.setTextFill(Color.web("#d9e8f8"));
        statusSummaryLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));

        connectivityLabel = new Label("Waiting connection");
        connectivityLabel.setTextFill(Color.web("#d9e8f8"));
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
                stage.setTitle("Smart Classroom Dashboard - Login");
                stage.setMinWidth(420);
                stage.setMinHeight(320);
                stage.setMaximized(false);
            } catch (Exception ex) {
                showToast("로그아웃 처리 실패: " + ex.getMessage(), true);
            }
        });
    }

    private Node buildTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-insets: 0; -fx-padding: 0 6 6 6;");

        Tab mainTab = new Tab("Main Dashboard", createMainDashboardView());

        networkPanelController = new NetworkPanelController(null);
        Tab networkTab = new Tab("Network Traffic", networkPanelController.getPanel());

        commandStatusPanelController = new CommandStatusPanelController();
        Tab commandTab = new Tab("Command Status", commandStatusPanelController.getPanel());

        Tab manageTab = new Tab("Management", createManagementView());

        tabPane.getTabs().addAll(mainTab, networkTab, commandTab, manageTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        return tabPane;
    }

    private Node createManagementView() {
        managementModule = new DashboardManagementModule(
                httpClient,
                objectMapper,
                API_BASE_URL,
                () -> rootVBox.getScene()
        );
        return managementModule.createView();
    }

    private Node createMainDashboardView() {
        SplitPane splitPane = new SplitPane();
        this.mainSplitPane = splitPane;
        splitPane.setStyle("-fx-background-color: transparent;");
        VBox rightPane = buildRightPane();

        widgetModule = new DashboardWidgetModule(
                httpClient,
                objectMapper,
                API_BASE_URL,
                classroomContainer,
                textItemMap,
                imageItemMap,
            () -> isEditModeActive() && !isLayoutLockedState(),
            this::isSnapToGridEnabled,
                this::refreshPanelListAndDashboard
        );
        widgetModule.loadTextItems();
        widgetModule.loadImageItems();

        classroomModule = new DashboardClassroomModule(
                httpClient,
                objectMapper,
                API_BASE_URL,
                classroomContainer,
                panelManager,
            () -> isEditModeActive() && !isLayoutLockedState(),
            this::refreshDashboardSnapshot,
            () -> currentDashboardId() == null ? -1L : currentDashboardId(),
                this::loadDashboardPanels,
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
        splitPane.setDividerPositions(0.23);

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
                () -> isEditModeActive() && !isLayoutLockedState(),
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
        ToggleButton lockLayoutBtn = createLockLayoutToggleButton();
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
                arrangeMenu,
                lockLayoutBtn,
                zoomOutBtn,
                zoomResetBtn,
                zoomInBtn,
                fitAllBtn
        );
        editOnlyToolbar.setManaged(false);
        editOnlyToolbar.setVisible(false);

        HBox rightToolbar = new HBox(
                10,
                bgColorPicker,
                editModeBtn,
                focusModeBtn,
                fullScreenBtn,
                dashboardOnlyBtn,
                editOnlyToolbar,
                refreshBtn
        );
        rightToolbar.setAlignment(Pos.CENTER_RIGHT);
        rightToolbar.setPadding(new Insets(4, 4, 4, 4));

        canvasModule = new DashboardCanvasModule(
                this::isEditModeActive,
                this::saveNodePosition,
                (groupId, x, y) -> {
                    if (classroomModule != null) {
                        classroomModule.updateGroupPosition(groupId, x, y, 300.0, 300.0, true);
                    }
                },
                (id, x, y) -> {
                    if (widgetModule != null) {
                        widgetModule.updateTextItemPosition(id, x, y, 200.0, 100.0, true);
                    }
                },
                (id, x, y) -> {
                    if (widgetModule != null) {
                        widgetModule.updateImageItemPosition(id, x, y, 300.0, 200.0, true);
                    }
                }
        );

        classroomContainer = canvasModule.getClassroomContainer();
        classroomContainer.setStyle("-fx-background-color: linear-gradient(to bottom right, #f8fbff, #eef5ff);");
        applyZoom(currentZoomScale());

        ScrollPane canvasScroll = new ScrollPane(classroomContainer);
        dashboardScrollPane = canvasScroll;
        canvasScroll.setFitToWidth(false);
        canvasScroll.setFitToHeight(false);
        canvasScroll.setPannable(true);
        canvasScroll.setStyle("-fx-background-color: transparent;");
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
        ToggleButton editModeBtn = new ToggleButton("Edit mode");
        editModeBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        editModeBtn.setOnAction(e -> {
            dashboardViewModel.setEditMode(editModeBtn.isSelected());
            applyEditModeUiState();
            showToast(isEditModeActive() ? "Edit mode on" : "Edit mode off", false);
        });
        return editModeBtn;
    }

    private ToggleButton createLockLayoutToggleButton() {
        ToggleButton lockLayoutBtn = new ToggleButton("Lock layout");
        lockLayoutBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        lockLayoutBtn.setSelected(isLayoutLockedState());
        lockLayoutBtn.setOnAction(e -> {
            dashboardViewModel.setLayoutLocked(lockLayoutBtn.isSelected());
            showToast(isLayoutLockedState() ? "Layout locked" : "Layout unlocked", false);
        });
        return lockLayoutBtn;
    }

    private ToggleButton createFocusModeToggleButton() {
        ToggleButton focusModeBtn = new ToggleButton("Focus mode");
        focusModeBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
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
        ToggleButton dashboardOnlyBtn = new ToggleButton("Dashboard only");
        dashboardOnlyBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
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
            showToast(isDashboardOnlyModeActive() ? "Dashboard-only mode" : "Normal mode", false);
        });
        return dashboardOnlyBtn;
    }

    private Button createFullScreenButton() {
        Button fullScreenBtn = createIconActionButton("Full screen", "mdi2f-fullscreen", "#1d4ed8", "white");
        fullScreenBtn.setOnAction(e -> {
            if (rootVBox != null && rootVBox.getScene() != null && rootVBox.getScene().getWindow() instanceof Stage stage) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });
        return fullScreenBtn;
    }

    private Button createRefreshButton() {
        Button refreshBtn = createIconActionButton("Refresh", "mdi2r-refresh", "#0f766e", "white");
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
        });
        return bgColorPicker;
    }

    private ToggleButton createGridToggleButton() {
        ToggleButton gridToggleBtn = new ToggleButton("Grid");
        gridToggleBtn.setStyle("-fx-background-radius: 8;");
        gridToggleBtn.setOnAction(e -> {
            if (canvasModule != null) {
                canvasModule.drawGrid(gridToggleBtn.isSelected());
            }
        });
        return gridToggleBtn;
    }

    private ToggleButton createSnapToggleButton() {
        ToggleButton snapToggleBtn = new ToggleButton("Snap");
        snapToggleBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        snapToggleBtn.setSelected(isSnapToGridEnabled());
        snapToggleBtn.setOnAction(e -> dashboardViewModel.setSnapToGrid(snapToggleBtn.isSelected()));
        return snapToggleBtn;
    }

    private MenuButton createAlignMenu() {
        MenuButton alignMenu = new MenuButton("Align");
        MenuItem alignLeft = new MenuItem("Left");
        alignLeft.setOnAction(e -> canvasModule.alignSelectedNodes("LEFT"));
        MenuItem alignRight = new MenuItem("Right");
        alignRight.setOnAction(e -> canvasModule.alignSelectedNodes("RIGHT"));
        MenuItem alignTop = new MenuItem("Top");
        alignTop.setOnAction(e -> canvasModule.alignSelectedNodes("TOP"));
        MenuItem alignBottom = new MenuItem("Bottom");
        alignBottom.setOnAction(e -> canvasModule.alignSelectedNodes("BOTTOM"));
        alignMenu.getItems().addAll(alignLeft, alignRight, alignTop, alignBottom);
        return alignMenu;
    }

    private MenuButton createArrangeMenu() {
        MenuButton arrangeMenu = new MenuButton("Arrange");
        MenuItem gridArrange = new MenuItem("Grid");
        gridArrange.setOnAction(e -> arrangePanels("GRID"));
        MenuItem rowArrange = new MenuItem("Row");
        rowArrange.setOnAction(e -> arrangePanels("ROW"));
        MenuItem colArrange = new MenuItem("Column");
        colArrange.setOnAction(e -> arrangePanels("COLUMN"));
        arrangeMenu.getItems().addAll(gridArrange, rowArrange, colArrange);
        return arrangeMenu;
    }

    private Button createZoomOutButton() {
        Button zoomOutBtn = new Button("-");
        zoomOutBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomOutBtn.setOnAction(e -> applyZoom(currentZoomScale() - 0.1));
        return zoomOutBtn;
    }

    private Button createZoomResetButton() {
        Button zoomResetBtn = new Button("100%");
        zoomResetBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomResetBtn.setOnAction(e -> applyZoom(1.0));
        return zoomResetBtn;
    }

    private Button createZoomInButton() {
        Button zoomInBtn = new Button("+");
        zoomInBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomInBtn.setOnAction(e -> applyZoom(currentZoomScale() + 0.1));
        return zoomInBtn;
    }

    private Button createFitAllButton() {
        Button fitAllBtn = new Button("Fit all");
        fitAllBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        fitAllBtn.setOnAction(e -> fitAllPanelsToViewport());
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
        if (resourcePanelModule != null) {
            resourcePanelModule.refreshListView();
        }
    }

    private void connectWebSocket() {
        if (realtimeModule == null) {
            realtimeModule = new DashboardRealtimeModule();
        }
        realtimeModule.start(
                () -> Platform.runLater(this::refreshDashboardSnapshot),
                alertPayload -> showToast("Realtime alert received", false)
        );
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

            if (widgetModule != null) {
                widgetModule.applySnapshot(snapshot.getTexts(), snapshot.getImages());
            }

            NetworkTrafficDto traffic = snapshot.getTraffic();
            if (traffic != null) {
                if (networkPanelController != null) {
                    networkPanelController.updateNetworkChart(traffic.getInboundMbps(), traffic.getOutboundMbps());
                }
                if (networkPanelModule != null) {
                    networkPanelModule.updateNetworkTraffic(traffic.getInboundMbps(), traffic.getOutboundMbps());
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
        }));
    }

    private void handleCreateDashboard() {
        TextInputDialog dialog = new TextInputDialog("New dashboard");
        dialog.setTitle("Create dashboard");
        dialog.setHeaderText("Enter dashboard name");
        dialog.setContentText("Name:");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            dashboardService.createDashboardAsync(trimmed, "User created dashboard", created -> Platform.runLater(() -> {
                if (created == null) {
                    showAlert("Dashboard create failed", "Could not create dashboard.");
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
        }
        updateTopSummary();
    }

    private void applyZoom(double targetScale) {
        double clamped = Math.max(0.4, Math.min(2.2, targetScale));
        dashboardViewModel.setZoom(clamped);
        double scale = currentZoomScale();
        if (dashboardUIService != null) {
            dashboardUIService.applyZoom(scale);
        } else if (classroomContainer != null) {
            classroomContainer.setScaleX(scale);
            classroomContainer.setScaleY(scale);
        }
    }

    private void fitAllPanelsToViewport() {
        if (dashboardUIService != null) {
            dashboardUIService.fitAllPanelsToViewport(getAllCanvasPanels());
            return;
        }

        if (dashboardScrollPane == null || classroomContainer == null) {
            return;
        }

        List<Node> nodes = getAllCanvasPanels();
        if (nodes.isEmpty()) {
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

        for (Node node : nodes) {
            double x = node.getLayoutX();
            double y = node.getLayoutY();
            double w = Math.max(1, node.prefWidth(-1));
            double h = Math.max(1, node.prefHeight(-1));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        double contentW = Math.max(1, (maxX - minX) + 80);
        double contentH = Math.max(1, (maxY - minY) + 80);
        double viewportW = Math.max(1, dashboardScrollPane.getViewportBounds().getWidth());
        double viewportH = Math.max(1, dashboardScrollPane.getViewportBounds().getHeight());

        double targetScale = Math.min(viewportW / contentW, viewportH / contentH);
        applyZoom(targetScale);

        double currentScale = currentZoomScale();
        double scaledW = Math.max(1, classroomContainer.getPrefWidth() * currentScale);
        double scaledH = Math.max(1, classroomContainer.getPrefHeight() * currentScale);
        double centerX = ((minX + maxX) / 2.0) * currentScale;
        double centerY = ((minY + maxY) / 2.0) * currentScale;

        double hRange = Math.max(1, scaledW - viewportW);
        double vRange = Math.max(1, scaledH - viewportH);

        dashboardScrollPane.setHvalue(Math.max(0, Math.min(1, (centerX - viewportW / 2.0) / hRange)));
        dashboardScrollPane.setVvalue(Math.max(0, Math.min(1, (centerY - viewportH / 2.0) / vRange)));

        showToast("Fit-all applied", false);
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

    private void arrangePanels(String mode) {
        if (!isEditModeActive() || isLayoutLockedState()) {
            showToast("Arrange is available only in edit mode", true);
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

        showToast("Arrange complete: " + mode, false);
    }

    private List<Node> getAllCanvasPanels() {
        List<Node> nodes = new ArrayList<>();
        panelManager.getPanels().forEach(p -> nodes.add(p.getPanel()));
        nodes.addAll(textItemMap.values());
        nodes.addAll(imageItemMap.values());
        if (networkPanelModule != null) {
            nodes.addAll(networkPanelModule.getPanelViews());
        }
        return nodes;
    }

    private void resizeCanvasBySceneWidth(double width) {
        if (canvasModule == null) {
            return;
        }
        if (width >= 1800) {
            canvasModule.getClassroomContainer().setPrefSize(2600, 1500);
        } else if (width >= 1400) {
            canvasModule.getClassroomContainer().setPrefSize(2200, 1300);
        } else {
            canvasModule.getClassroomContainer().setPrefSize(1800, 1100);
        }
    }

    private void updateTopSummary() {
        if (statusSummaryLabel == null || connectivityLabel == null) {
            return;
        }
        List<ClassroomSummaryDto> classrooms = dashboardViewModel.getCachedClassrooms();
        int classroomCount = classrooms.size();
        long online = classrooms.stream().mapToLong(ClassroomSummaryDto::getOnlinePcs).sum();
        long total = classrooms.stream().mapToLong(ClassroomSummaryDto::getTotalPcs).sum();
        statusSummaryLabel.setText("Classrooms " + classroomCount + ", online " + online + "/" + total);
        connectivityLabel.setText("Connected");
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
        toast.setStyle("-fx-background-color: " + (error ? "#b91c1c" : "#0f766e")
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
                + "; -fx-font-weight: 700; -fx-background-radius: 8; -fx-padding: 8 12 8 12;");
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

    private boolean isLayoutLockedState() {
        return viewModel().isLayoutLocked();
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

    private void registerWindowShutdownHook(Window window) {
        if (window == null) {
            return;
        }
        window.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> shutdown());
    }
}
