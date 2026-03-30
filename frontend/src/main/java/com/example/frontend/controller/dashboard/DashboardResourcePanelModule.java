package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.frontend.service.DashboardService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.paint.Color;

/**
 * 대시보드 좌측 리소스 패널 모듈.
 *
 * <p>강의실 패널·텍스트 위젯·이미지 위젯·네트워크 패널을 추가할 수 있는
 * "Resources" 패널의 UI 생성·갱신을 담당합니다.</p>
 */
public class DashboardResourcePanelModule {

    private final DashboardViewModel viewModel;
    private final DashboardClassroomModule classroomModule;
    private final DashboardWidgetModule widgetModule;
    private final DashboardService dashboardService;
    private final BooleanSupplier editModeSupplier;
    private final Supplier<Long> currentDashboardId;
    private final Function<String, double[]> responsiveSizeFn;
    private final Runnable onLoadDashboardPanels;

    // 내부 UI 상태 (buildView 이후 유효)
    private ListView<Object> groupListView;
    private ComboBox<ClassroomSummaryDto> panelClassroomCombo;
    private ComboBox<ClassroomSummaryDto> networkClassroomCombo;
    private VBox leftEditControls;
    private Node editControlsSection;
    private List<DashboardPanelDto> cachedPanels = List.of();

    public DashboardResourcePanelModule(
            DashboardViewModel viewModel,
            DashboardClassroomModule classroomModule,
            DashboardWidgetModule widgetModule,
            DashboardService dashboardService,
            BooleanSupplier editModeSupplier,
            Supplier<Long> currentDashboardId,
            Function<String, double[]> responsiveSizeFn,
            Runnable onLoadDashboardPanels) {
        this.viewModel = viewModel;
        this.classroomModule = classroomModule;
        this.widgetModule = widgetModule;
        this.dashboardService = dashboardService;
        this.editModeSupplier = editModeSupplier;
        this.currentDashboardId = currentDashboardId;
        this.responsiveSizeFn = responsiveSizeFn;
        this.onLoadDashboardPanels = onLoadDashboardPanels;
    }

    /**
     * 좌측 리소스 패널 VBox를 생성합니다. 반드시 한 번만 호출해야 합니다.
     */
    public VBox buildView() {
        VBox leftPane = new VBox(10);
        leftPane.setFillWidth(true);
        leftPane.setPadding(new Insets(14));
        leftPane.setPrefWidth(270);
        leftPane.setMinWidth(220);
        leftPane.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 12; "
                + "-fx-border-color: #dbe5f1; -fx-border-radius: 12;");

        Label listTitle = new Label("리소스");
        listTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        Label listSub = new Label("대시보드에서 사용 가능한 패널");
        listSub.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");

        panelClassroomCombo = buildClassroomCombo("Select classroom for panel");
        networkClassroomCombo = buildClassroomCombo("Select classroom for network panel");

        groupListView = createResourceListView();
        VBox.setVgrow(groupListView, Priority.ALWAYS);

        leftEditControls = new VBox(8,
                createAddClassroomButton(),
                createAddTextButton(),
                createAddImageButton(),
                createAddDisconnectStatusButton(),
                createBackupTextButton(),
                createRestoreTextButton(),
                new Separator(),
                panelClassroomCombo,
                createAddClassroomPanelButton(),
                networkClassroomCombo,
                createAddNetworkPanelButton()
        );
        leftEditControls.setFillWidth(true);

        ScrollPane controlsScroll = new ScrollPane(leftEditControls);
        controlsScroll.setFitToWidth(true);
        controlsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        controlsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        controlsScroll.setPannable(true);
        controlsScroll.setPrefViewportHeight(220);
        controlsScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        editControlsSection = controlsScroll;

        VBox.setVgrow(groupListView, Priority.ALWAYS);

        leftPane.getChildren().addAll(listTitle, listSub, groupListView, controlsScroll);
        return leftPane;
    }

    /**
     * DashboardUIService에서 편집 모드 토글 시 show/hide 대상이 되는 컨트롤 컨테이너를 반환합니다.
     */
    public Node getEditControls() {
        return editControlsSection != null ? editControlsSection : leftEditControls;
    }

    /**
     * 리소스 목록과 콤보박스를 최신 강의실/패널 데이터로 갱신합니다.
     */
    public void updateItems(List<ClassroomSummaryDto> classrooms, List<DashboardPanelDto> panels) {
        if (groupListView == null) {
            return;
        }
        cachedPanels = panels == null ? List.of() : panels;
        groupListView.getItems().clear();
        groupListView.getItems().addAll(classrooms);
        if (widgetModule != null) {
            groupListView.getItems().addAll(widgetModule.getCachedTexts());
            groupListView.getItems().addAll(widgetModule.getCachedImages());
        }
        if (panelClassroomCombo != null) {
            panelClassroomCombo.getItems().setAll(classrooms);
        }
        if (networkClassroomCombo != null) {
            networkClassroomCombo.getItems().setAll(classrooms);
        }
    }

    /**
     * 편집 모드 상태 변경 시 리소스 목록 셀의 "Drag to add" 힌트를 갱신합니다.
     */
    public void refreshListView() {
        if (groupListView != null) {
            groupListView.refresh();
        }
    }

    // ── UI 빌더 ──────────────────────────────────────────────────

    private ListView<Object> createResourceListView() {
        ListView<Object> listView = new ListView<>();
        listView.setPlaceholder(new Label("표시할 항목이 없습니다."));
        listView.setStyle("-fx-background-radius: 10; -fx-border-color: #dbe5f1; -fx-border-radius: 10;");
        listView.setPrefHeight(320);
        listView.setMinHeight(200);
        listView.setMaxHeight(Double.MAX_VALUE);
        listView.setFixedCellSize(56);
        listView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                ResourceRowModel model = resolveResourceRowModel(item);

                Label nameLabel = new Label(model.name());
                nameLabel.setStyle("-fx-font-weight: 600;");
                Label subLabel = new Label(model.subLabel());
                subLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
                VBox textBox = new VBox(2, nameLabel, subLabel);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button deleteBtn = buildDeleteButton(item, model);
                Label dragHint = new Label(editModeSupplier.getAsBoolean() ? "추가" : "");
                dragHint.setStyle("-fx-text-fill: #2563eb; -fx-font-size: 11px; -fx-font-weight: 700;");

                HBox row = deleteBtn == null
                        ? new HBox(10, textBox, spacer, dragHint)
                        : new HBox(10, textBox, spacer, dragHint, deleteBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                configureResourceDrag(row, model.dragKey());
                setGraphic(row);
            }
        });
        return listView;
    }

    private ResourceRowModel resolveResourceRowModel(Object item) {
        List<DashboardPanelDto> panels = cachedPanels.isEmpty() ? viewModel.getCachedDashboardPanels() : cachedPanels;
        if (item instanceof ClassroomSummaryDto dto) {
            boolean added = panels.stream().anyMatch(p ->
                    "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType())
                            && dto.getGroupId().equals(p.getRefGroupId()));
            String sub = (added ? "Added" : "Not added") + " , online "
                    + dto.getOnlinePcs() + "/" + dto.getTotalPcs();
            return new ResourceRowModel("GROUP_ADD:" + dto.getGroupId(), dto.getGroupName(), sub,
                    ResourceKind.CLASSROOM, dto.getGroupId(), added);
        }
        if (item instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            Object idObj = map.get("id");
            if (!(idObj instanceof Number idNum)) {
                return new ResourceRowModel("", "Unknown", "", ResourceKind.UNKNOWN, null, false);
            }
            Long id = idNum.longValue();
            String type = String.valueOf(map.getOrDefault("type", "")).toUpperCase();

            if ("TEXT".equals(type) || map.containsKey("text")) {
                String text = String.valueOf(map.getOrDefault("text", "Text #" + id));
                String sub = text.substring(0, Math.min(24, text.length()));
                return new ResourceRowModel("TEXT_ADD:" + id, "Text widget #" + id, sub,
                        ResourceKind.TEXT, id, true);
            }
            if ("IMAGE".equals(type) || map.containsKey("imageUrl")) {
                return new ResourceRowModel("IMAGE_ADD:" + id, "Image widget #" + id, "Drag to place on canvas",
                        ResourceKind.IMAGE, id, true);
            }
        }
        return new ResourceRowModel("", "Unknown", "", ResourceKind.UNKNOWN, null, false);
    }

    private void configureResourceDrag(HBox row, String dragKey) {
        row.setOnDragDetected(event -> {
            if (!editModeSupplier.getAsBoolean() || dragKey == null || dragKey.isBlank()) {
                return;
            }
            if (isButtonTarget(event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode())) {
                return;
            }
            Dragboard dragboard = groupListView.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putString(dragKey);
            dragboard.setContent(content);
            event.consume();
        });
    }

    private boolean isButtonTarget(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private Button buildDeleteButton(Object item, ResourceRowModel model) {
        if (!editModeSupplier.getAsBoolean()) {
            return null;
        }
        if (item instanceof ClassroomSummaryDto dto) {
            Button button = tinyDeleteButton("삭제");
            button.setOnAction(e -> classroomModule.handleDeleteGroup(dto));
            return button;
        }
        if (model.kind() == ResourceKind.TEXT && model.refId() != null) {
            Button button = tinyDeleteButton("삭제");
            button.setOnAction(e -> widgetModule.handleDeleteTextItem(model.refId()));
            return button;
        }
        if (model.kind() == ResourceKind.IMAGE && model.refId() != null) {
            Button button = tinyDeleteButton("삭제");
            button.setOnAction(e -> widgetModule.handleDeleteImageItem(model.refId()));
            return button;
        }
        return null;
    }

    private Button tinyDeleteButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 10px; "
                + "-fx-font-weight: 700; -fx-background-radius: 6; -fx-padding: 4 8 4 8;");
        button.setFocusTraversable(false);
        button.setOnDragDetected(event -> event.consume());
        return button;
    }

    private Button createAddClassroomButton() {
        Button button = iconButton("Add classroom", "mdi2s-school-outline", "#0ea5e9");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> classroomModule.handleAddClassroom());
        return button;
    }

    private Button createAddClassroomPanelButton() {
        Button button = actionButton("Add classroom panel", "#2563eb");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            ClassroomSummaryDto selected = panelClassroomCombo.getValue();
            if (selected == null) {
                new Alert(Alert.AlertType.WARNING, "Select a classroom first.").showAndWait();
                return;
            }
            List<DashboardPanelDto> panels = viewModel.getCachedDashboardPanels();
            boolean exists = panels.stream().anyMatch(p ->
                    "CLASSROOM_HEARTBEAT".equalsIgnoreCase(p.getPanelType())
                            && selected.getGroupId().equals(p.getRefGroupId()));
            if (exists) {
                new Alert(Alert.AlertType.INFORMATION, "Classroom panel already exists on dashboard.").showAndWait();
                return;
            }
            double[] size = responsiveSizeFn.apply("CLASSROOM");
            classroomModule.addClassroomPanel(selected, 40.0, 40.0, size[0], size[1]);
        });
        return button;
    }

    private Button createAddTextButton() {
        Button button = iconButton("Add text", "mdi2t-text-box-plus-outline", "#334155");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> widgetModule.handleAddTextItem());
        return button;
    }

    private Button createAddImageButton() {
        Button button = iconButton("Add image", "mdi2i-image-plus", "#334155");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> widgetModule.handleAddImageItem());
        return button;
    }

    private Button createAddDisconnectStatusButton() {
        Button button = iconButton("접접 패널 추가", "mdi2a-alert-circle-outline", "#d73a49");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            if (currentDashboardId.get() == null) {
                new Alert(Alert.AlertType.WARNING, "Select a dashboard first.").showAndWait();
                return;
            }
            double[] size = responsiveSizeFn.apply("DISCONNECT");
            dashboardService.createPanelAsync(
                    currentDashboardId.get(),
                    "DISCONNECT_STATUS",
                    null,
                    80.0, 80.0, size[0], size[1],
                    dto -> Platform.runLater(onLoadDashboardPanels::run)
            );
        });
        return button;
    }

    private Button createBackupTextButton() {
        Button button = iconButton("Backup text", "mdi2c-content-save-outline", "#64748b");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> widgetModule.handleBackupTextItems());
        return button;
    }

    private Button createRestoreTextButton() {
        Button button = iconButton("Restore text", "mdi2r-restore", "#64748b");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> widgetModule.handleRestoreTextItems());
        return button;
    }

    private Button createAddNetworkPanelButton() {
        Button button = iconButton("Add network panel", "mdi2w-wan", "#2563eb");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            ClassroomSummaryDto selected = networkClassroomCombo.getValue();
            if (selected == null) {
                new Alert(Alert.AlertType.WARNING, "Select a classroom first.").showAndWait();
                return;
            }
            if (currentDashboardId.get() == null) {
                new Alert(Alert.AlertType.WARNING, "Select a dashboard first.").showAndWait();
                return;
            }
            double[] size = responsiveSizeFn.apply("NETWORK");
            dashboardService.createPanelAsync(
                    currentDashboardId.get(),
                    "NETWORK_TRAFFIC",
                    selected.getGroupId(),
                    60.0, 60.0, size[0], size[1],
                    dto -> Platform.runLater(onLoadDashboardPanels::run)
            );
        });
        return button;
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

    // ── 스타일 유틸 ──────────────────────────────────────────────

    private Button actionButton(String text, String bgColor) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + bgColor
                + "; -fx-text-fill: white; -fx-font-weight: 700;"
                + " -fx-background-radius: 8; -fx-padding: 8 12 8 12;");
        return button;
    }

    private Button iconButton(String text, String iconLiteral, String bgColor) {
        Button button = actionButton(text, bgColor);
        try {
            FontIcon icon = new FontIcon(iconLiteral);
            icon.setIconSize(14);
            icon.setIconColor(Color.WHITE);
            button.setGraphic(icon);
        } catch (IllegalArgumentException ex) {
            // 아이콘 리터럴이 잘못돼도 화면 전환이 실패하지 않도록 버튼 텍스트만 표시한다.
            button.setGraphic(null);
        }
        return button;
    }

    private enum ResourceKind {
        CLASSROOM,
        TEXT,
        IMAGE,
        UNKNOWN
    }

    private record ResourceRowModel(
            String dragKey,
            String name,
            String subLabel,
            ResourceKind kind,
            Long refId,
            boolean added) {}
}
