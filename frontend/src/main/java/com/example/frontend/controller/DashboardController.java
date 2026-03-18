package com.example.frontend.controller;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardInfoDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.DashboardRefreshDto;
import com.example.dto.dashboard.NetworkTrafficDto;
import com.example.frontend.controller.dashboard.DashboardCanvasModule;
import com.example.frontend.controller.dashboard.DashboardClassroomModule;
import com.example.frontend.controller.dashboard.DashboardManagementModule;
import com.example.frontend.controller.dashboard.DashboardRealtimeModule;
import com.example.frontend.controller.dashboard.DashboardUIService;
import com.example.frontend.controller.dashboard.DashboardViewModel;
import com.example.frontend.controller.dashboard.DashboardWidgetModule;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.service.DashboardDataService;
import com.example.frontend.service.DashboardPanelManager;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
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
import javafx.util.Duration;

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
import java.util.prefs.Preferences;

public class DashboardController {

    private static final double GRID_SIZE = 20.0;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private VBox rootVBox;

    private DashboardCanvasModule canvasModule;
    private DashboardClassroomModule classroomModule;
    private DashboardManagementModule managementModule;
    private DashboardRealtimeModule realtimeModule;
    private DashboardWidgetModule widgetModule;
    private DashboardViewModel dashboardViewModel;
    private DashboardUIService dashboardUIService;

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

    private ListView<Object> groupListView;
    private ComboBox<DashboardInfoDto> dashboardSelector;
    private ComboBox<ClassroomSummaryDto> panelClassroomCombo;
    private ComboBox<ClassroomSummaryDto> networkClassroomCombo;

    private NetworkPanelController networkPanelController;
    private final DashboardPanelManager panelManager = new DashboardPanelManager();
    private final Map<Long, VBox> textItemMap = new HashMap<>();
    private final Map<Long, VBox> imageItemMap = new HashMap<>();
    private final Map<Long, VBox> networkItemMap = new HashMap<>();
    private final Map<Long, NetworkPanelController> networkItemControllers = new HashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DashboardService dashboardService = new DashboardService();
    private final DashboardDataService dashboardDataService = new DashboardDataService();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Preferences preferences = Preferences.userNodeForPackage(DashboardController.class);

    private static final String API_BASE_URL = "http://localhost:8080/api/v1";

    private List<ClassroomSummaryDto> cachedClassrooms = new ArrayList<>();
    private List<DashboardInfoDto> cachedDashboards = new ArrayList<>();
    private List<DashboardPanelDto> cachedDashboardPanels = new ArrayList<>();

    private Long selectedDashboardId;
    private double zoomScale = 1.0;
    private boolean snapToGrid = true;
    private boolean layoutLocked = false;
    private boolean dashboardOnlyMode = false;
    private boolean focusMode = false;
    private boolean isEditMode = false;

    @FXML
    public void initialize() {
        dashboardViewModel = new DashboardViewModel();
        zoomScale = dashboardViewModel.getZoomScale();
        snapToGrid = dashboardViewModel.isSnapToGrid();
        layoutLocked = dashboardViewModel.isLayoutLocked();
        dashboardOnlyMode = dashboardViewModel.isDashboardOnlyMode();

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
                selectedDashboardId = newValue.getId();
                loadDashboardPanels();
            }
        });

        Button addDashboardBtn = createActionButton("+ Dashboard", "#16a34a", "white");
        addDashboardBtn.setOnAction(e -> handleCreateDashboard());

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
                statusSummaryLabel,
                connectivityLabel,
                spacer,
                clockLabel
        );
        return topBar;
    }

    private Node buildTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-insets: 0; -fx-padding: 0 6 6 6;");

        Tab mainTab = new Tab("Main Dashboard", createMainDashboardView());

        networkPanelController = new NetworkPanelController(null);
        Tab networkTab = new Tab("Network Traffic", networkPanelController.getPanel());

        Tab manageTab = new Tab("Management", createManagementView());

        tabPane.getTabs().addAll(mainTab, networkTab, manageTab);
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

        VBox leftPane = buildLeftPane();
        this.leftToolPane = leftPane;

        VBox rightPane = buildRightPane();

        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.23);

        widgetModule = new DashboardWidgetModule(
                httpClient,
                objectMapper,
                API_BASE_URL,
                classroomContainer,
                textItemMap,
                imageItemMap,
                () -> isEditMode && !layoutLocked,
                () -> snapToGrid,
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
                () -> isEditMode && !layoutLocked,
                this::refreshDashboardData,
                () -> selectedDashboardId == null ? -1L : selectedDashboardId,
                this::loadDashboardPanels,
                () -> snapToGrid
        );

            dashboardUIService = new DashboardUIService(
                toastContainer,
                classroomContainer,
                dashboardScrollPane,
                canvasModule,
                editOnlyToolbar,
                leftEditControls
            );

        applyEditModeUiState();
        return splitPane;
    }

    private VBox buildLeftPane() {
        VBox leftPane = new VBox(10);
        leftPane.setPadding(new Insets(14));
        leftPane.setPrefWidth(300);
        leftPane.setMinWidth(260);
        leftPane.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 12; -fx-border-color: #dbe5f1; -fx-border-radius: 12;");

        Label listTitle = new Label("Resources");
        listTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        Label listSub = new Label("Panels and widgets available for canvas");
        listSub.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");

        Button addClassroomBtn = createActionButton("Add classroom", "#0ea5e9", "white");
        addClassroomBtn.setMaxWidth(Double.MAX_VALUE);
        addClassroomBtn.setOnAction(e -> classroomModule.handleAddClassroom());

        panelClassroomCombo = buildClassroomCombo("Select classroom for panel");

        Button addClassroomPanelBtn = createActionButton("Add classroom panel", "#2563eb", "white");
        addClassroomPanelBtn.setMaxWidth(Double.MAX_VALUE);
        addClassroomPanelBtn.setOnAction(e -> {
            ClassroomSummaryDto selected = panelClassroomCombo.getValue();
            if (selected == null) {
                new Alert(Alert.AlertType.WARNING, "Select a classroom first.").showAndWait();
                return;
            }
            boolean exists = cachedDashboardPanels.stream().anyMatch(p ->
                    "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType())
                            && selected.getGroupId().equals(p.getRefGroupId()));
            if (exists) {
                new Alert(Alert.AlertType.INFORMATION, "Classroom panel already exists on dashboard.").showAndWait();
                return;
            }
            double[] size = getResponsiveDefaultSize("CLASSROOM");
            classroomModule.addClassroomPanel(selected, 40.0, 40.0, size[0], size[1]);
        });

        Button addTextBtn = createActionButton("Add text", "#334155", "white");
        addTextBtn.setMaxWidth(Double.MAX_VALUE);
        addTextBtn.setOnAction(e -> widgetModule.handleAddTextItem());

        Button addImageBtn = createActionButton("Add image", "#334155", "white");
        addImageBtn.setMaxWidth(Double.MAX_VALUE);
        addImageBtn.setOnAction(e -> widgetModule.handleAddImageItem());

        Button backupBtn = createActionButton("Backup text", "#64748b", "white");
        backupBtn.setMaxWidth(Double.MAX_VALUE);
        backupBtn.setOnAction(e -> widgetModule.handleBackupTextItems());

        Button restoreBtn = createActionButton("Restore text", "#64748b", "white");
        restoreBtn.setMaxWidth(Double.MAX_VALUE);
        restoreBtn.setOnAction(e -> widgetModule.handleRestoreTextItems());

        networkClassroomCombo = buildClassroomCombo("Select classroom for network panel");

        Button addNetworkPanelBtn = createActionButton("Add network panel", "#2563eb", "white");
        addNetworkPanelBtn.setMaxWidth(Double.MAX_VALUE);
        addNetworkPanelBtn.setOnAction(e -> {
            ClassroomSummaryDto selected = networkClassroomCombo.getValue();
            if (selected == null) {
                new Alert(Alert.AlertType.WARNING, "Select a classroom first.").showAndWait();
                return;
            }
            if (selectedDashboardId == null) {
                new Alert(Alert.AlertType.WARNING, "Select a dashboard first.").showAndWait();
                return;
            }
            double[] size = getResponsiveDefaultSize("NETWORK");
            dashboardService.createPanelAsync(
                    selectedDashboardId,
                    "NETWORK_TRAFFIC",
                    selected.getGroupId(),
                    60.0,
                    60.0,
                    size[0],
                    size[1],
                    dto -> Platform.runLater(this::loadDashboardPanels)
            );
        });

        groupListView = new ListView<>();
        groupListView.setPlaceholder(new Label("No items to display."));
        groupListView.setStyle("-fx-background-radius: 10; -fx-border-color: #dbe5f1; -fx-border-radius: 10;");
        groupListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }

                Label nameLabel = new Label();
                nameLabel.setStyle("-fx-font-weight: 600;");
                Label subLabel = new Label();
                subLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
                VBox textBox = new VBox(2, nameLabel, subLabel);

                String dragKey = "";
                if (item instanceof ClassroomSummaryDto dto) {
                    nameLabel.setText(dto.getGroupName());
                    boolean added = cachedDashboardPanels.stream().anyMatch(p ->
                            "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType())
                                    && dto.getGroupId().equals(p.getRefGroupId()));
                    subLabel.setText((added ? "Added" : "Not added") + " , online " + dto.getOnlinePcs() + "/" + dto.getTotalPcs());
                    dragKey = "GROUP_ADD:" + dto.getGroupId();
                } else if (item instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) rawMap;
                    Long id = ((Number) map.get("id")).longValue();
                    if (map.containsKey("text")) {
                        String text = map.get("text").toString();
                        nameLabel.setText("Text widget");
                        subLabel.setText(text.substring(0, Math.min(24, text.length())));
                        dragKey = "TEXT_ADD:" + id;
                    } else if (map.containsKey("imageUrl")) {
                        nameLabel.setText("Image widget #" + id);
                        subLabel.setText("Move/delete from canvas");
                        dragKey = "IMAGE_ADD:" + id;
                    }
                }

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label dragHint = new Label(isEditMode ? "Drag to add" : "");
                dragHint.setStyle("-fx-text-fill: #2563eb; -fx-font-size: 11px; -fx-font-weight: 700;");

                HBox row = new HBox(10, textBox, spacer, dragHint);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);

                String finalDragKey = dragKey;
                row.setOnDragDetected(event -> {
                    if (!isEditMode) {
                        return;
                    }
                    Dragboard dragboard = groupListView.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(finalDragKey);
                    dragboard.setContent(content);
                    event.consume();
                });
            }
        });
        VBox.setVgrow(groupListView, Priority.ALWAYS);

        leftEditControls = new VBox(
                8,
                addClassroomBtn,
                addTextBtn,
                addImageBtn,
                backupBtn,
                restoreBtn,
                new Separator(),
                panelClassroomCombo,
                addClassroomPanelBtn,
                networkClassroomCombo,
                addNetworkPanelBtn
        );

        leftPane.getChildren().addAll(listTitle, listSub, groupListView, leftEditControls);
        return leftPane;
    }

    private VBox buildRightPane() {
        VBox rightPane = new VBox(10);
        rightPane.setPadding(new Insets(4, 8, 8, 8));

        ToggleButton editModeBtn = new ToggleButton("Edit mode");
        editModeBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        editModeBtn.setOnAction(e -> {
            isEditMode = editModeBtn.isSelected();
            if (dashboardViewModel != null) {
                dashboardViewModel.setEditMode(isEditMode);
            }
            applyEditModeUiState();
            showToast(isEditMode ? "Edit mode on" : "Edit mode off", false);
        });

        ToggleButton lockLayoutBtn = new ToggleButton("Lock layout");
        lockLayoutBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        lockLayoutBtn.setSelected(layoutLocked);
        lockLayoutBtn.setOnAction(e -> {
            layoutLocked = lockLayoutBtn.isSelected();
            if (dashboardViewModel != null) {
                dashboardViewModel.setLayoutLocked(layoutLocked);
            }
            showToast(layoutLocked ? "Layout locked" : "Layout unlocked", false);
        });

        ToggleButton focusModeBtn = new ToggleButton("Focus mode");
        focusModeBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        focusModeBtn.setOnAction(e -> {
            focusMode = focusModeBtn.isSelected();
            if (dashboardViewModel != null) {
                dashboardViewModel.setFocusMode(focusMode);
            }
            if (leftToolPane != null) {
                leftToolPane.setManaged(!focusMode);
                leftToolPane.setVisible(!focusMode);
            }
            if (mainSplitPane != null) {
                mainSplitPane.setDividerPositions(focusMode ? 0.0 : 0.23);
            }
        });

        Button fullScreenBtn = createActionButton("Full screen", "#1d4ed8", "white");
        fullScreenBtn.setOnAction(e -> {
            if (rootVBox != null && rootVBox.getScene() != null && rootVBox.getScene().getWindow() instanceof Stage stage) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });

        ToggleButton dashboardOnlyBtn = new ToggleButton("Dashboard only");
        dashboardOnlyBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        dashboardOnlyBtn.setSelected(dashboardOnlyMode);
        dashboardOnlyBtn.setOnAction(e -> {
            dashboardOnlyMode = dashboardOnlyBtn.isSelected();
            if (dashboardViewModel != null) {
                dashboardViewModel.setDashboardOnlyMode(dashboardOnlyMode);
            }
            if (leftToolPane != null) {
                leftToolPane.setManaged(!dashboardOnlyMode);
                leftToolPane.setVisible(!dashboardOnlyMode);
            }
            if (topBarRef != null) {
                topBarRef.setManaged(!dashboardOnlyMode);
                topBarRef.setVisible(!dashboardOnlyMode);
            }
            showToast(dashboardOnlyMode ? "Dashboard-only mode" : "Normal mode", false);
        });

        Button refreshBtn = createActionButton("Refresh", "#0f766e", "white");
        refreshBtn.setOnAction(e -> {
            RotateTransition rotate = new RotateTransition(Duration.millis(450), refreshBtn);
            rotate.setByAngle(360);
            rotate.play();
            refreshDashboardSnapshot();
        });

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

        ToggleButton gridToggleBtn = new ToggleButton("Grid");
        gridToggleBtn.setStyle("-fx-background-radius: 8;");
        gridToggleBtn.setOnAction(e -> {
            if (canvasModule != null) {
                canvasModule.drawGrid(gridToggleBtn.isSelected());
            }
        });

        ToggleButton snapToggleBtn = new ToggleButton("Snap");
        snapToggleBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        snapToggleBtn.setSelected(snapToGrid);
        snapToggleBtn.setOnAction(e -> {
            snapToGrid = snapToggleBtn.isSelected();
            if (dashboardViewModel != null) {
                dashboardViewModel.setSnapToGrid(snapToGrid);
            }
            preferences.putBoolean("dashboard.snapToGrid", snapToGrid);
        });

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

        MenuButton arrangeMenu = new MenuButton("Arrange");
        MenuItem gridArrange = new MenuItem("Grid");
        gridArrange.setOnAction(e -> arrangePanels("GRID"));
        MenuItem rowArrange = new MenuItem("Row");
        rowArrange.setOnAction(e -> arrangePanels("ROW"));
        MenuItem colArrange = new MenuItem("Column");
        colArrange.setOnAction(e -> arrangePanels("COLUMN"));
        arrangeMenu.getItems().addAll(gridArrange, rowArrange, colArrange);

        Button zoomOutBtn = new Button("-");
        zoomOutBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomOutBtn.setOnAction(e -> applyZoom(zoomScale - 0.1));

        Button zoomResetBtn = new Button("100%");
        zoomResetBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomResetBtn.setOnAction(e -> applyZoom(1.0));

        Button zoomInBtn = new Button("+");
        zoomInBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        zoomInBtn.setOnAction(e -> applyZoom(zoomScale + 0.1));

        Button fitAllBtn = new Button("Fit all");
        fitAllBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
        fitAllBtn.setOnAction(e -> fitAllPanelsToViewport());

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
                () -> isEditMode,
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
        applyZoom(zoomScale);

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

    private ComboBox<ClassroomSummaryDto> buildClassroomCombo(String prompt) {
        ComboBox<ClassroomSummaryDto> combo = new ComboBox<>();
        combo.setPromptText(prompt);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(ClassroomSummaryDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getGroupName());
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ClassroomSummaryDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getGroupName());
            }
        });
        return combo;
    }

    private void applyEditModeUiState() {
        if (dashboardUIService != null) {
            dashboardUIService.applyEditModeUiState(isEditMode);
        } else if (canvasModule != null) {
            canvasModule.applyEditMode(isEditMode);
        }
        if (groupListView != null) {
            groupListView.refresh();
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
        double width = node.prefWidth(-1);
        double height = node.prefHeight(-1);
        boolean visible = node.isVisible();

        Object dashboardPanelIdObj = node.getProperties().get("dashboardPanelId");
        if (dashboardPanelIdObj != null && selectedDashboardId != null) {
            Long panelId;
            try {
                panelId = Long.valueOf(dashboardPanelIdObj.toString());
            } catch (Exception ex) {
                panelId = null;
            }
            if (panelId != null) {
                dashboardService.updatePanelPositionAsync(selectedDashboardId, panelId, x, y, width, height);
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

    private void refreshDashboardData() {
        dashboardService.fetchClassroomsAsync(classrooms -> {
            cachedClassrooms = classrooms;
            Platform.runLater(this::refreshPanelListAndDashboard);
        });
    }

    private void refreshDashboardSnapshot() {
        dashboardDataService.fetchRefreshSnapshotAsync(snapshot -> Platform.runLater(() -> applyRefreshSnapshot(snapshot)));
    }

    private void applyRefreshSnapshot(DashboardRefreshDto snapshot) {
        if (snapshot == null) {
            return;
        }

        cachedClassrooms = snapshot.getClassrooms() == null ? List.of() : snapshot.getClassrooms();

        if (widgetModule != null) {
            widgetModule.applySnapshot(snapshot.getTexts(), snapshot.getImages());
        }

        NetworkTrafficDto traffic = snapshot.getTraffic();
        if (traffic != null) {
            if (networkPanelController != null) {
                networkPanelController.updateNetworkChart(traffic.getInboundMbps(), traffic.getOutboundMbps());
            }
            networkItemControllers.values().forEach(controller ->
                    controller.updateNetworkChart(traffic.getInboundMbps(), traffic.getOutboundMbps()));
        }

        refreshPanelListAndDashboard();
    }

    private void loadDashboards() {
        dashboardService.fetchDashboardsAsync(dashboards -> Platform.runLater(() -> {
            cachedDashboards = dashboards;
            if (dashboardSelector == null) {
                return;
            }
            dashboardSelector.getItems().setAll(dashboards);
            if (dashboards.isEmpty()) {
                selectedDashboardId = null;
                return;
            }
            DashboardInfoDto selected = dashboards.stream()
                    .filter(d -> d.getId() != null && d.getId().equals(selectedDashboardId))
                    .findFirst()
                    .orElse(dashboards.get(0));
            dashboardSelector.getSelectionModel().select(selected);
            selectedDashboardId = selected.getId();
            loadDashboardPanels();
        }));
    }

    private void loadDashboardPanels() {
        if (selectedDashboardId == null) {
            cachedDashboardPanels = List.of();
            Platform.runLater(this::refreshPanelListAndDashboard);
            return;
        }
        dashboardService.fetchDashboardPanelsAsync(selectedDashboardId, panels -> Platform.runLater(() -> {
            cachedDashboardPanels = panels;
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
                selectedDashboardId = created.getId();
                loadDashboards();
            }));
        });
    }

    private void refreshPanelListAndDashboard() {
        if (groupListView != null) {
            groupListView.getItems().clear();
            groupListView.getItems().addAll(cachedClassrooms);
            if (widgetModule != null) {
                groupListView.getItems().addAll(widgetModule.getCachedTexts());
                groupListView.getItems().addAll(widgetModule.getCachedImages());
            }
        }

        if (panelClassroomCombo != null) {
            panelClassroomCombo.getItems().setAll(cachedClassrooms);
        }
        if (networkClassroomCombo != null) {
            networkClassroomCombo.getItems().setAll(cachedClassrooms);
        }

        if (classroomModule != null) {
            classroomModule.updateDashboardClassrooms(cachedClassrooms, cachedDashboardPanels);
        }
        renderNetworkPanels();
        updateTopSummary();
    }

    private void renderNetworkPanels() {
        if (classroomContainer == null) {
            return;
        }

        List<DashboardPanelDto> networkPanels = cachedDashboardPanels.stream()
                .filter(p -> "NETWORK_TRAFFIC".equalsIgnoreCase(p.getPanelType()))
                .toList();

        List<Long> activeIds = networkPanels.stream().map(DashboardPanelDto::getId).toList();
        new ArrayList<>(networkItemMap.keySet()).forEach(id -> {
            if (!activeIds.contains(id)) {
                VBox node = networkItemMap.remove(id);
                if (node != null) {
                    classroomContainer.getChildren().remove(node);
                }
                networkItemControllers.remove(id);
            }
        });

        for (DashboardPanelDto panelDto : networkPanels) {
            VBox existing = networkItemMap.get(panelDto.getId());
            if (existing == null) {
                ClassroomSummaryDto linked = cachedClassrooms.stream()
                        .filter(c -> c.getGroupId().equals(panelDto.getRefGroupId()))
                        .findFirst()
                        .orElse(null);

                NetworkPanelController controller = new NetworkPanelController(linked);
                VBox body = controller.getPanel();

                Label title = new Label(linked != null ? linked.getGroupName() + " Network" : "Network panel");
                title.setStyle("-fx-font-weight: 700; -fx-text-fill: #0f172a;");

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button deleteBtn = new Button("Delete");
                deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
                deleteBtn.setOnAction(e -> {
                    if (selectedDashboardId != null) {
                        dashboardService.deletePanelAsync(selectedDashboardId, panelDto.getId(), this::loadDashboardPanels);
                    }
                });

                HBox header = new HBox(8, title, spacer, deleteBtn);
                header.setAlignment(Pos.CENTER_LEFT);

                VBox wrapper = new VBox(8, header, body);
                wrapper.getProperties().put("dashboardPanelId", panelDto.getId());
                wrapper.setPadding(new Insets(12));
                wrapper.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #cbd5e1; -fx-border-radius: 10;");
                wrapper.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 80);
                wrapper.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 80);
                wrapper.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : getResponsiveDefaultSize("NETWORK")[0]);
                wrapper.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : getResponsiveDefaultSize("NETWORK")[1]);

                makeDashboardPanelInteractive(wrapper, panelDto.getId());
                addResizeHandleToDashboardPanel(wrapper, panelDto.getId());

                classroomContainer.getChildren().add(wrapper);
                networkItemMap.put(panelDto.getId(), wrapper);
                networkItemControllers.put(panelDto.getId(), controller);
            } else {
                existing.setLayoutX(panelDto.getX() != null ? panelDto.getX() : existing.getLayoutX());
                existing.setLayoutY(panelDto.getY() != null ? panelDto.getY() : existing.getLayoutY());
                existing.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : existing.getPrefWidth());
                existing.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : existing.getPrefHeight());
            }
        }
    }

    private void makeDashboardPanelInteractive(VBox panel, Long panelId) {
        final double[] dragStart = new double[2];

        panel.setOnMousePressed(event -> {
            if (!isEditMode || layoutLocked) {
                return;
            }
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            dragStart[0] = parentPoint.getX() - panel.getLayoutX();
            dragStart[1] = parentPoint.getY() - panel.getLayoutY();
            panel.toFront();
            event.consume();
        });

        panel.setOnMouseDragged(event -> {
            if (!isEditMode || layoutLocked) {
                return;
            }
            Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
            panel.setLayoutX(applySnap(parentPoint.getX() - dragStart[0]));
            panel.setLayoutY(applySnap(parentPoint.getY() - dragStart[1]));
            event.consume();
        });

        panel.setOnMouseReleased(event -> {
            if (!isEditMode || layoutLocked || selectedDashboardId == null) {
                return;
            }
            dashboardService.updatePanelPositionAsync(
                    selectedDashboardId,
                    panelId,
                    panel.getLayoutX(),
                    panel.getLayoutY(),
                    panel.getPrefWidth(),
                    panel.getPrefHeight()
            );
            showToast("Panel position saved", false);
            event.consume();
        });
    }

    private void addResizeHandleToDashboardPanel(VBox panel, Long panelId) {
        Label handle = new Label("◢");
        handle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        handle.setMouseTransparent(false);

        StackPane handleHolder = new StackPane(handle);
        handleHolder.setPickOnBounds(false);
        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);

        panel.getChildren().add(handleHolder);

        handle.setOnMouseDragged(event -> {
            if (!isEditMode || layoutLocked) {
                return;
            }
            Point2D local = panel.sceneToLocal(event.getSceneX(), event.getSceneY());
            double newW = Math.max(320, local.getX());
            double newH = Math.max(220, local.getY());
            panel.setPrefWidth(applySnap(newW));
            panel.setPrefHeight(applySnap(newH));
            event.consume();
        });

        handle.setOnMouseReleased(event -> {
            if (!isEditMode || layoutLocked || selectedDashboardId == null) {
                return;
            }
            dashboardService.updatePanelPositionAsync(
                    selectedDashboardId,
                    panelId,
                    panel.getLayoutX(),
                    panel.getLayoutY(),
                    panel.getPrefWidth(),
                    panel.getPrefHeight()
            );
            showToast("Panel size saved", false);
            event.consume();
        });
    }

    private void applyZoom(double targetScale) {
        double clamped = Math.max(0.4, Math.min(2.2, targetScale));
        zoomScale = clamped;
        if (dashboardViewModel != null) {
            dashboardViewModel.setZoom(clamped);
        }
        preferences.putDouble("dashboard.zoomScale", zoomScale);
        if (dashboardUIService != null) {
            dashboardUIService.applyZoom(zoomScale);
        } else if (classroomContainer != null) {
            classroomContainer.setScaleX(zoomScale);
            classroomContainer.setScaleY(zoomScale);
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

        double scaledW = Math.max(1, classroomContainer.getPrefWidth() * zoomScale);
        double scaledH = Math.max(1, classroomContainer.getPrefHeight() * zoomScale);
        double centerX = ((minX + maxX) / 2.0) * zoomScale;
        double centerY = ((minY + maxY) / 2.0) * zoomScale;

        double hRange = Math.max(1, scaledW - viewportW);
        double vRange = Math.max(1, scaledH - viewportH);

        dashboardScrollPane.setHvalue(Math.max(0, Math.min(1, (centerX - viewportW / 2.0) / hRange)));
        dashboardScrollPane.setVvalue(Math.max(0, Math.min(1, (centerY - viewportH / 2.0) / vRange)));

        showToast("Fit-all applied", false);
    }

    private double[] getResponsiveDefaultSize(String type) {
        double sceneWidth = rootVBox != null && rootVBox.getScene() != null ? rootVBox.getScene().getWidth() : 1600;
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
        if (!snapToGrid) {
            return value;
        }
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    private void arrangePanels(String mode) {
        if (!isEditMode || layoutLocked) {
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
        nodes.addAll(networkItemMap.values());
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
        int classroomCount = cachedClassrooms == null ? 0 : cachedClassrooms.size();
        long online = cachedClassrooms == null ? 0 : cachedClassrooms.stream().mapToLong(ClassroomSummaryDto::getOnlinePcs).sum();
        long total = cachedClassrooms == null ? 0 : cachedClassrooms.stream().mapToLong(ClassroomSummaryDto::getTotalPcs).sum();
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
        scheduler.shutdownNow();
        if (realtimeModule != null) {
            realtimeModule.stop();
        }
    }
}
