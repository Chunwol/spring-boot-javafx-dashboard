package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.service.DashboardService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
 * 대시보드 네트워크 트래픽 패널의 생성·배치·삭제를 관리하는 모듈.
 *
 * <p>각 패널은 특정 강의실에 연결된 {@link NetworkPanelController}를 포함하며,
 * 편집 모드에서 드래그·리사이즈 인터랙션을 제공합니다.</p>
 */
public class DashboardNetworkPanelModule {

    private final DashboardService dashboardService;
    private final Pane classroomContainer;
    private final DashboardInteractionService interactionService;
    private final BooleanSupplier canInteract;
    private final DoubleUnaryOperator snapFn;
    private final Supplier<Long> dashboardIdSupplier;
    private final BiConsumer<String, Boolean> showToast;
    private final Function<String, double[]> responsiveSizeFn;
    private final Runnable onPanelsChanged;

    private final Map<Long, VBox> panelMap = new HashMap<>();
    private final Map<Long, NetworkPanelController> controllerMap = new HashMap<>();
    private final Map<Long, Button> deleteBtnMap = new HashMap<>();
    private final Map<Long, Label> resizeHandleMap = new HashMap<>();

    public DashboardNetworkPanelModule(
            DashboardService dashboardService,
            Pane classroomContainer,
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

    /**
     * 현재 대시보드 패널 목록과 강의실 정보를 기반으로 화면을 갱신합니다.
     * NETWORK_TRAFFIC 타입 패널만 처리하며, 더 이상 유효하지 않은 패널은 제거합니다.
     */
    public void update(List<ClassroomSummaryDto> classrooms, List<DashboardPanelDto> allPanels) {
        List<DashboardPanelDto> networkPanels = allPanels.stream()
                .filter(p -> "NETWORK_TRAFFIC".equalsIgnoreCase(p.getPanelType()))
                .toList();

        List<Long> activeIds = networkPanels.stream().map(DashboardPanelDto::getId).toList();
        removeInactive(activeIds);

        for (DashboardPanelDto panelDto : networkPanels) {
            VBox existing = panelMap.get(panelDto.getId());
            if (existing == null) {
                addPanel(panelDto, classrooms);
            } else {
                updateLayout(existing, panelDto);
            }
        }
    }

    /**
     * 실시간 네트워크 트래픽 데이터를 모든 네트워크 패널에 반영합니다.
     */
    public void updateNetworkTraffic(double inboundMbps, double outboundMbps) {
        controllerMap.values().forEach(c -> c.updateNetworkChart(inboundMbps, outboundMbps));
    }

    /**
     * 캔버스 배치 연산(arrange/fit-all)에서 참조하도록 현재 패널 VBox 컬렉션을 반환합니다.
     */
    public Collection<VBox> getPanelViews() {
        return panelMap.values();
    }

    // ── 내부 구현 ────────────────────────────────────────────────

    private void removeInactive(List<Long> activeIds) {
        new ArrayList<>(panelMap.keySet()).forEach(id -> {
            if (!activeIds.contains(id)) {
                VBox node = panelMap.remove(id);
                if (node != null) {
                    classroomContainer.getChildren().remove(node);
                }
                controllerMap.remove(id);
                deleteBtnMap.remove(id);
                resizeHandleMap.remove(id);
            }
        });
    }

    private void addPanel(DashboardPanelDto panelDto, List<ClassroomSummaryDto> classrooms) {
        ClassroomSummaryDto linked = classrooms.stream()
                .filter(c -> c.getGroupId().equals(panelDto.getRefGroupId()))
                .findFirst()
                .orElse(null);

        NetworkPanelController controller = new NetworkPanelController(linked);
        VBox wrapper = buildWrapper(panelDto, linked, controller);
        attachInteraction(wrapper, panelDto.getId());

        classroomContainer.getChildren().add(wrapper);
        panelMap.put(panelDto.getId(), wrapper);
        controllerMap.put(panelDto.getId(), controller);
    }

    private VBox buildWrapper(DashboardPanelDto panelDto, ClassroomSummaryDto linked,
                              NetworkPanelController controller) {
        Label title = new Label(linked != null ? linked.getGroupName() + " Network" : "Network panel");
        title.setStyle("-fx-font-weight: 700; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; "
                + "-fx-font-size: 11px; -fx-background-radius: 6;");
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

        VBox wrapper = new VBox(8, header, controller.getPanel());
        wrapper.getProperties().put("dashboardPanelId", panelDto.getId());
        wrapper.setPadding(new Insets(12));
        wrapper.setStyle("-fx-background-color: white; -fx-background-radius: 10; "
                + "-fx-border-color: #cbd5e1; -fx-border-radius: 10;");
        wrapper.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 80);
        wrapper.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 80);

        double[] defaultSize = responsiveSizeFn.apply("NETWORK");
        wrapper.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : defaultSize[0]);
        wrapper.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : defaultSize[1]);
        return wrapper;
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
                () -> persistPosition(panel, panelId, "Panel position saved")
        );

        Label handle = new Label("◢");
        handle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        handle.setVisible(canInteract.getAsBoolean());
        handle.setManaged(canInteract.getAsBoolean());
        resizeHandleMap.put(panelId, handle);
        StackPane handleHolder = new StackPane(handle);
        handleHolder.setPickOnBounds(false);
        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);
        panel.getChildren().add(handleHolder);

        interactionService.attachResizeHandlers(
                panel, handle, 320, 220,
                () -> canInteract.getAsBoolean() && dashboardIdSupplier.get() != null,
                snapFn,
                () -> persistPosition(panel, panelId, "Panel size saved")
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

    private void persistPosition(VBox panel, Long panelId, String toastMessage) {
        Long dashboardId = dashboardIdSupplier.get();
        if (dashboardId == null) {
            return;
        }
        dashboardService.updatePanelPositionAsync(
                dashboardId, panelId,
                panel.getLayoutX(), panel.getLayoutY(),
                panel.getPrefWidth(), panel.getPrefHeight()
        );
        showToast.accept(toastMessage, false);
    }
}
