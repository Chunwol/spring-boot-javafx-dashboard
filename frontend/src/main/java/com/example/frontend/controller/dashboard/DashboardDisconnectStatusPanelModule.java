package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 접속현황(실시간 ON/OFF 이벤트 로그) 전용 패널 모듈.
 */
public class DashboardDisconnectStatusPanelModule {

    private static final Logger log = LoggerFactory.getLogger(DashboardDisconnectStatusPanelModule.class);
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double LAYOUT_EPSILON = 0.5;

    private final ApiClient apiClient = ApiClient.getInstance();
    private final DashboardService dashboardService;
    private final DashboardInteractionService interactionService;
    private final BooleanSupplier canInteract;
    private final DoubleUnaryOperator snapFn;
    private final Supplier<Long> dashboardIdSupplier;
    private final BiConsumer<String, Boolean> showToast;
    private final Function<String, double[]> responsiveSizeFn;
    private final Runnable onPanelsChanged;
    private final javafx.scene.layout.Pane classroomContainer;

    private final Map<Long, VBox> panelMap = new HashMap<>();
    private final Map<Long, Button> deleteBtnMap = new HashMap<>();
    private final Map<Long, Label> resizeHandleMap = new HashMap<>();
    // 접속 상태 변화 추적: MAC → 상태 (최대 500개 유지, 초과 시 임의 제거)
    private final Map<String, String> previousStatusByMac = Collections.synchronizedMap(new LinkedHashMap<String, String>() {
        private static final int MAX_SIZE = 500;

        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry eldest) {
            return size() > MAX_SIZE;
        }
    });
    private final ObservableList<ConnectionLogRow> logRows = FXCollections.observableArrayList();
    private final AtomicBoolean pollingInFlight = new AtomicBoolean(false);

    public DashboardDisconnectStatusPanelModule(
            DashboardService dashboardService,
            javafx.scene.layout.Pane classroomContainer,
            DashboardInteractionService interactionService,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapFn,
            Supplier<Long> dashboardIdSupplier,
            BiConsumer<String, Boolean> showToast,
            Function<String, double[]> responsiveSizeFn,
            Runnable onPanelsChanged) {
        this.dashboardService = dashboardService;
        this.classroomContainer = classroomContainer;
        this.interactionService = interactionService;
        this.canInteract = canInteract;
        this.snapFn = snapFn;
        this.dashboardIdSupplier = dashboardIdSupplier;
        this.showToast = showToast;
        this.responsiveSizeFn = responsiveSizeFn;
        this.onPanelsChanged = onPanelsChanged;
    }

    public void update(List<ClassroomSummaryDto> classrooms, List<DashboardPanelDto> allPanels) {
        List<DashboardPanelDto> statusPanels = allPanels.stream()
                .filter(p -> "DISCONNECT_STATUS".equalsIgnoreCase(p.getPanelType()))
                .toList();

        List<Long> activeIds = statusPanels.stream().map(DashboardPanelDto::getId).toList();
        new ArrayList<>(panelMap.keySet()).forEach(id -> {
            if (!activeIds.contains(id)) {
                VBox node = panelMap.remove(id);
                if (node != null) {
                    classroomContainer.getChildren().remove(node);
                }
                deleteBtnMap.remove(id);
                resizeHandleMap.remove(id);
            }
        });

        for (DashboardPanelDto panelDto : statusPanels) {
            VBox existing = panelMap.get(panelDto.getId());
            if (existing == null) {
                VBox panel = buildPanel(panelDto, classrooms);
                attachInteraction(panel, panelDto.getId());
                panelMap.put(panelDto.getId(), panel);
                classroomContainer.getChildren().add(panel);
            } else {
                updateLayout(existing, panelDto);
                refreshSummary(existing, classrooms);
            }
        }

        pollAndAppendConnectionLogs(classrooms);
    }

    public Collection<VBox> getPanelViews() {
        return panelMap.values();
    }

    private VBox buildPanel(DashboardPanelDto panelDto, List<ClassroomSummaryDto> classrooms) {
        Label title = new Label("접속현황 패널");
        title.setStyle("-fx-font-weight: 800; -fx-font-size: 15px; -fx-text-fill: #334155;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button deleteBtn = new Button("삭제");
        deleteBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        deleteBtn.setVisible(canInteract.getAsBoolean());
        deleteBtn.setManaged(canInteract.getAsBoolean());
        deleteBtnMap.put(panelDto.getId(), deleteBtn);
        deleteBtn.setOnAction(e -> {
            Long dashboardId = dashboardIdSupplier.get();
            if (dashboardId != null) {
                dashboardService.deletePanelAsync(dashboardId, panelDto.getId(), onPanelsChanged);
            }
        });

        HBox header = new HBox(8, title, spacer, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 8, 10));
        header.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 8;");

        Label summary = new Label();
        summary.setStyle("-fx-font-size: 12px; -fx-font-weight: 700;");

        TableView<ConnectionLogRow> table = createLogTable();
        table.setItems(logRows);
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox wrapper = new VBox(10, header, summary, table);
        wrapper.getProperties().put("dashboardPanelId", panelDto.getId());
        wrapper.getProperties().put("summaryLabel", summary);
        wrapper.setPadding(new Insets(16, 14, 14, 14));
        wrapper.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12; -fx-border-color: #d8dee8; -fx-border-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.12), 16, 0, 0, 4);");

        double[] defaultSize = responsiveSizeFn.apply("DISCONNECT");
        wrapper.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 120);
        wrapper.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 120);
        wrapper.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : defaultSize[0]);
        wrapper.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : defaultSize[1]);

        refreshSummary(wrapper, classrooms);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private TableView<ConnectionLogRow> createLogTable() {
        TableView<ConnectionLogRow> table = new TableView<>();
        table.setPlaceholder(new Label("아직 접속 상태 변경 로그가 없습니다."));

        TableColumn<ConnectionLogRow, String> timeCol = new TableColumn<>("시간");
        timeCol.setPrefWidth(150);
        timeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().time()));

        TableColumn<ConnectionLogRow, String> classroomCol = new TableColumn<>("강의실");
        classroomCol.setPrefWidth(120);
        classroomCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().classroom()));

        TableColumn<ConnectionLogRow, String> pcCol = new TableColumn<>("PC명");
        pcCol.setPrefWidth(160);
        pcCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().pcName()));

        TableColumn<ConnectionLogRow, String> statusCol = new TableColumn<>("이벤트");
        statusCol.setPrefWidth(110);
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().event()));

        table.getColumns().addAll(timeCol, classroomCol, pcCol, statusCol);
        return table;
    }

    private void refreshSummary(VBox panel, List<ClassroomSummaryDto> classrooms) {
        Label summary = (Label) panel.getProperties().get("summaryLabel");
        if (summary == null) {
            return;
        }

        long total = classrooms.stream().mapToLong(ClassroomSummaryDto::getTotalPcs).sum();
        long online = classrooms.stream().mapToLong(ClassroomSummaryDto::getOnlinePcs).sum();
        long offline = Math.max(0, total - online);
        summary.setText("전체 " + total + "대 / 온라인 " + online + "대 / 오프라인 " + offline + "대");
        summary.setTextFill(offline > 0 ? Color.web("#d73a49") : Color.web("#22863a"));
    }

    private void pollAndAppendConnectionLogs(List<ClassroomSummaryDto> classrooms) {
        if (classrooms == null || classrooms.isEmpty()) {
            return;
        }
        if (!pollingInFlight.compareAndSet(false, true)) {
            return;
        }

        List<String> classroomNames = new ArrayList<>();
        List<CompletableFuture<List<PcStatusDto>>> futures = new ArrayList<>();

        for (ClassroomSummaryDto c : classrooms) {
            if (c == null || c.getGroupId() == null) continue;
            classroomNames.add(c.getGroupName() == null ? "-" : c.getGroupName());
            futures.add(apiClient.getPagedList("/dashboard/classrooms/" + c.getGroupId() + "/pcs",
                    new TypeReference<List<PcStatusDto>>() {}));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    List<ConnectionLogRow> newRows = new ArrayList<>();
                    for (int i = 0; i < futures.size(); i++) {
                        String classroom = i < classroomNames.size() ? classroomNames.get(i) : "-";
                        List<PcStatusDto> pcs = futures.get(i).getNow(List.of());
                        for (PcStatusDto pc : pcs) {
                            if (pc == null || pc.getMacAddress() == null || pc.getMacAddress().isBlank()) continue;

                            String now = normalizeStatus(pc.getStatus());
                            String before = previousStatusByMac.put(pc.getMacAddress(), now);
                            if (before == null || before.equals(now)) {
                                continue;
                            }

                            boolean wasOnline = "ONLINE".equals(before);
                            boolean isOnline = "ONLINE".equals(now);
                            if (wasOnline == isOnline) {
                                continue;
                            }

                            String event = isOnline ? "켜짐(ONLINE)" : "꺼짐(OFFLINE)";
                            String pcName = (pc.getHostname() == null || pc.getHostname().isBlank())
                                    ? (pc.getIpAddress() == null ? pc.getMacAddress() : pc.getIpAddress())
                                    : pc.getHostname();

                            newRows.add(new ConnectionLogRow(
                                    LocalDateTime.now().format(LOG_TIME_FMT),
                                    classroom,
                                    pcName,
                                    event
                            ));
                        }
                    }

                    if (!newRows.isEmpty()) {
                        Platform.runLater(() -> {
                            logRows.addAll(0, newRows);
                            if (logRows.size() > 300) {
                                logRows.remove(300, logRows.size());
                            }
                        });
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Failed to poll connection logs", ex);
                    return null;
                })
                .whenComplete((r, t) -> pollingInFlight.set(false));
    }

    private String normalizeStatus(String status) {
        if (status == null) return "";
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private void updateLayout(VBox existing, DashboardPanelDto panelDto) {
        if (panelDto.getX() != null) {
            applyIfChanged(existing.getLayoutX(), panelDto.getX(), existing::setLayoutX);
        }
        if (panelDto.getY() != null) {
            applyIfChanged(existing.getLayoutY(), panelDto.getY(), existing::setLayoutY);
        }
        if (panelDto.getWidth() != null) {
            applyIfChanged(existing.getPrefWidth(), panelDto.getWidth(), existing::setPrefWidth);
        }
        if (panelDto.getHeight() != null) {
            applyIfChanged(existing.getPrefHeight(), panelDto.getHeight(), existing::setPrefHeight);
        }
    }

    private void applyIfChanged(double current, double next, java.util.function.DoubleConsumer setter) {
        if (Math.abs(current - next) > LAYOUT_EPSILON) {
            setter.accept(next);
        }
    }

    private void attachInteraction(VBox panel, Long panelId) {
        interactionService.attachDragHandlers(
                panel,
                () -> canInteract.getAsBoolean() && dashboardIdSupplier.get() != null,
                snapFn,
                () -> persistPosition(panel, panelId)
        );

        Label handle = new Label("◢");
        handle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        handle.setVisible(canInteract.getAsBoolean());
        handle.setManaged(canInteract.getAsBoolean());
        resizeHandleMap.put(panelId, handle);
        StackPane handleHolder = new StackPane(handle);
        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);
        panel.getChildren().add(handleHolder);

        interactionService.attachResizeHandlers(
                panel,
                handle,
                380,
                240,
                () -> canInteract.getAsBoolean() && dashboardIdSupplier.get() != null,
                snapFn,
                () -> persistPosition(panel, panelId)
        );
    }

    public void applyEditMode(boolean editMode) {
        deleteBtnMap.values().forEach(btn -> {
            btn.setVisible(editMode);
            btn.setManaged(editMode);
        });
        resizeHandleMap.values().forEach(handle -> {
            handle.setVisible(editMode);
            handle.setManaged(editMode);
        });
    }

    private void persistPosition(VBox panel, Long panelId) {
        Long dashboardId = dashboardIdSupplier.get();
        if (dashboardId == null) {
            return;
        }
        dashboardService.updatePanelPositionAsync(
                dashboardId,
                panelId,
                panel.getLayoutX(),
                panel.getLayoutY(),
                panel.getPrefWidth(),
                panel.getPrefHeight()
        );
        showToast.accept("접속현황 패널 저장됨", false);
    }

    private record ConnectionLogRow(String time, String classroom, String pcName, String event) {}
}
