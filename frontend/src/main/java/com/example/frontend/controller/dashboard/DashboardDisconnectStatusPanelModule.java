package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.frontend.service.DashboardService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 접속 종료(오프라인) 현황 전용 패널 모듈.
 */
public class DashboardDisconnectStatusPanelModule {

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
        List<DashboardPanelDto> disconnectPanels = allPanels.stream()
                .filter(p -> "DISCONNECT_STATUS".equalsIgnoreCase(p.getPanelType()))
                .toList();

        List<Long> activeIds = disconnectPanels.stream().map(DashboardPanelDto::getId).toList();
        new ArrayList<>(panelMap.keySet()).forEach(id -> {
            if (!activeIds.contains(id)) {
                VBox node = panelMap.remove(id);
                if (node != null) {
                    classroomContainer.getChildren().remove(node);
                }
            }
        });

        for (DashboardPanelDto panelDto : disconnectPanels) {
            VBox existing = panelMap.get(panelDto.getId());
            if (existing == null) {
                VBox panel = buildPanel(panelDto, classrooms);
                attachInteraction(panel, panelDto.getId());
                panelMap.put(panelDto.getId(), panel);
                classroomContainer.getChildren().add(panel);
            } else {
                updateLayout(existing, panelDto);
                refreshContent(existing, classrooms);
            }
        }
    }

    public Collection<VBox> getPanelViews() {
        return panelMap.values();
    }

    private VBox buildPanel(DashboardPanelDto panelDto, List<ClassroomSummaryDto> classrooms) {
        Label title = new Label("접속 종료 현황");
        title.setStyle("-fx-font-weight: 800; -fx-font-size: 15px; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button deleteBtn = new Button("삭제");
        deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 6;");
        deleteBtn.setOnAction(e -> {
            Long dashboardId = dashboardIdSupplier.get();
            if (dashboardId != null) {
                dashboardService.deletePanelAsync(dashboardId, panelDto.getId(), onPanelsChanged);
            }
        });

        HBox header = new HBox(8, title, spacer, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        Label summary = new Label();
        summary.setStyle("-fx-font-size: 13px; -fx-font-weight: 700;");

        VBox listBox = new VBox(6);

        VBox wrapper = new VBox(8, header, summary, listBox);
        wrapper.getProperties().put("dashboardPanelId", panelDto.getId());
        wrapper.getProperties().put("summaryLabel", summary);
        wrapper.getProperties().put("listBox", listBox);
        wrapper.setPadding(new Insets(12));
        wrapper.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #cbd5e1; -fx-border-radius: 10;");

        double[] defaultSize = responsiveSizeFn.apply("DISCONNECT");
        wrapper.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 120);
        wrapper.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 120);
        wrapper.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : defaultSize[0]);
        wrapper.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : defaultSize[1]);

        refreshContent(wrapper, classrooms);
        return wrapper;
    }

    private void refreshContent(VBox panel, List<ClassroomSummaryDto> classrooms) {
        Label summary = (Label) panel.getProperties().get("summaryLabel");
        @SuppressWarnings("unchecked")
        VBox listBox = (VBox) panel.getProperties().get("listBox");
        if (summary == null || listBox == null) {
            return;
        }

        long total = classrooms.stream().mapToLong(ClassroomSummaryDto::getTotalPcs).sum();
        long online = classrooms.stream().mapToLong(ClassroomSummaryDto::getOnlinePcs).sum();
        long offline = Math.max(0, total - online);
        summary.setText("전체 " + total + "대 / 온라인 " + online + "대 / 접속종료 " + offline + "대");
        summary.setTextFill(offline > 0 ? Color.web("#b91c1c") : Color.web("#15803d"));

        listBox.getChildren().clear();
        classrooms.stream()
                .sorted((a, b) -> Integer.compare(
                        Math.max(0, b.getTotalPcs() - b.getOnlinePcs()),
                        Math.max(0, a.getTotalPcs() - a.getOnlinePcs())
                ))
                .limit(8)
                .forEach(c -> {
                    int off = Math.max(0, c.getTotalPcs() - c.getOnlinePcs());
                    Label row = new Label(c.getGroupName() + " : " + off + "대 종료");
                    row.setStyle("-fx-font-size: 12px; -fx-font-weight: 600;");
                    row.setTextFill(off > 0 ? Color.web("#dc2626") : Color.web("#64748b"));
                    listBox.getChildren().add(row);
                });
    }

    private void updateLayout(VBox existing, DashboardPanelDto panelDto) {
        existing.setLayoutX(panelDto.getX() != null ? panelDto.getX() : existing.getLayoutX());
        existing.setLayoutY(panelDto.getY() != null ? panelDto.getY() : existing.getLayoutY());
        existing.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : existing.getPrefWidth());
        existing.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : existing.getPrefHeight());
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
        panel.getChildren().add(handle);
        VBox.setMargin(handle, new Insets(0, 0, 0, 0));

        interactionService.attachResizeHandlers(
                panel,
                handle,
                320,
                220,
                () -> canInteract.getAsBoolean() && dashboardIdSupplier.get() != null,
                snapFn,
                () -> persistPosition(panel, panelId)
        );
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
        showToast.accept("접속 종료 현황 패널 저장됨", false);
    }
}
