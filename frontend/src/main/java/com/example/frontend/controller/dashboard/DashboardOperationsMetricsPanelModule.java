package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.DashboardPanelDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 운영 메트릭(내장) 패널의 생성·배치·삭제와 데이터 갱신을 관리하는 모듈.
 */
public class DashboardOperationsMetricsPanelModule {

    private static final Logger log = LoggerFactory.getLogger(DashboardOperationsMetricsPanelModule.class);
    private static final double LAYOUT_EPSILON = 0.5;
    private static final long SNAPSHOT_REFRESH_INTERVAL_MS = 30_000L;
    private static final DateTimeFormatter PERF_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final ApiClient apiClient = ApiClient.getInstance();
    private final DashboardService dashboardService;
    private final Pane classroomContainer;
    private final DashboardInteractionService interactionService;
    private final BooleanSupplier canInteract;
    private final DoubleUnaryOperator snapFn;
    private final Supplier<Long> dashboardIdSupplier;
    private final BiConsumer<String, Boolean> showToast;
    private final PanelLayoutCacheUpdater panelLayoutCacheUpdater;
    private final Function<String, double[]> responsiveSizeFn;
    private final Runnable beforePanelMutation;
    private final Runnable onPanelsChanged;

    @FunctionalInterface
    public interface PanelLayoutCacheUpdater {
        void update(Long panelId, double x, double y, double width, double height);
    }

    private final Map<Long, VBox> panelMap = new HashMap<>();
    private final Map<Long, OperationsViewRefs> refsMap = new HashMap<>();
    private final Map<Long, Button> deleteBtnMap = new HashMap<>();
    private final Map<Long, Label> resizeHandleMap = new HashMap<>();
    private final AtomicBoolean snapshotFetchInFlight = new AtomicBoolean(false);
    private java.util.function.BiConsumer<javafx.scene.Node, Boolean> onSelectCallback;
    private Supplier<java.util.Set<javafx.scene.Node>> selectedNodesSupplier;

    public void setOnSelectCallback(java.util.function.BiConsumer<javafx.scene.Node, Boolean> callback) {
        this.onSelectCallback = callback;
    }

    public void setSelectedNodesSupplier(Supplier<java.util.Set<javafx.scene.Node>> supplier) {
        this.selectedNodesSupplier = supplier;
    }

    public void deletePanel(Long panelId) {
        Long dashboardId = dashboardIdSupplier.get();
        if (dashboardId == null || panelId == null) return;
        runBeforePanelMutation();
        dashboardService.deletePanelAsync(dashboardId, panelId, onPanelsChanged);
    }

    private volatile long lastSnapshotFetchedAt = 0L;
    private volatile Map<String, Object> latestSnapshot = Map.of();

    public DashboardOperationsMetricsPanelModule(
            DashboardService dashboardService,
            Pane classroomContainer,
            DashboardInteractionService interactionService,
            BooleanSupplier canInteract,
            DoubleUnaryOperator snapFn,
            Supplier<Long> dashboardIdSupplier,
            BiConsumer<String, Boolean> showToast,
            PanelLayoutCacheUpdater panelLayoutCacheUpdater,
            Function<String, double[]> responsiveSizeFn,
            Runnable beforePanelMutation,
            Runnable onPanelsChanged) {
        this.dashboardService = dashboardService;
        this.classroomContainer = classroomContainer;
        this.interactionService = interactionService;
        this.canInteract = canInteract;
        this.snapFn = snapFn;
        this.dashboardIdSupplier = dashboardIdSupplier;
        this.showToast = showToast;
        this.panelLayoutCacheUpdater = panelLayoutCacheUpdater;
        this.responsiveSizeFn = responsiveSizeFn;
        this.beforePanelMutation = beforePanelMutation;
        this.onPanelsChanged = onPanelsChanged;
    }

    public void update(List<ClassroomSummaryDto> classrooms, List<DashboardPanelDto> allPanels) {
        List<DashboardPanelDto> metricPanels = allPanels.stream()
                .filter(this::isOperationsPanelType)
                .toList();

        List<Long> activeIds = metricPanels.stream().map(DashboardPanelDto::getId).toList();
        removeInactive(activeIds);

        for (DashboardPanelDto panelDto : metricPanels) {
            VBox existing = panelMap.get(panelDto.getId());
            if (existing == null) {
                addPanel(panelDto);
            } else {
                updateLayout(existing, panelDto);
            }
        }

        refreshSnapshot(false, false);
    }

    public Collection<VBox> getPanelViews() {
        return panelMap.values();
    }

    public void applyEditMode(boolean editMode) {
        if (!editMode) {
            resizeHandleMap.values().forEach(handle -> {
                handle.setVisible(false);
                handle.setManaged(false);
            });
        }
    }

    private boolean isOperationsPanelType(DashboardPanelDto panel) {
        if (panel == null || panel.getPanelType() == null) {
            return false;
        }
        String type = panel.getPanelType();
        return "OPERATIONS_METRICS".equalsIgnoreCase(type)
                || "PERFORMANCE_METRICS".equalsIgnoreCase(type);
    }

    private void removeInactive(List<Long> activeIds) {
        new ArrayList<>(panelMap.keySet()).forEach(id -> {
            if (!activeIds.contains(id)) {
                VBox node = panelMap.remove(id);
                if (node != null) {
                    classroomContainer.getChildren().remove(node);
                }
                refsMap.remove(id);
                deleteBtnMap.remove(id);
                resizeHandleMap.remove(id);
            }
        });
    }

    private void addPanel(DashboardPanelDto panelDto) {
        VBox panel = buildPanel(panelDto);
        attachInteraction(panel, panelDto.getId());

        panelMap.put(panelDto.getId(), panel);
        classroomContainer.getChildren().add(panel);

        if (!latestSnapshot.isEmpty()) {
            applySnapshotToRefs(refsMap.get(panelDto.getId()), latestSnapshot);
        }
    }

    private VBox buildPanel(DashboardPanelDto panelDto) {
        VBox wrapper;
        Button refreshBtn;
        Button deleteBtn;
        StackPane resizeHandleHolder;
        Label updatedAtLabel;
        Label pcOnlineLabel;
        Label pcRatioLabel;
        Label commandQueueLabel;
        Label commandFailureLabel;
        Label staleLabel;
        Label tokenLabel;
        Label cacheLabel;
        Label queuePressureLabel;
        Label stalePressureLabel;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/operations-metrics-panel.fxml"));
            wrapper = loader.load();
            refreshBtn = (Button) loader.getNamespace().get("refreshBtn");
            deleteBtn = (Button) loader.getNamespace().get("deleteBtn");
            resizeHandleHolder = (StackPane) loader.getNamespace().get("resizeHandleHolder");
            updatedAtLabel = (Label) loader.getNamespace().get("updatedAtLabel");
            pcOnlineLabel = (Label) loader.getNamespace().get("pcOnlineLabel");
            pcRatioLabel = (Label) loader.getNamespace().get("pcRatioLabel");
            commandQueueLabel = (Label) loader.getNamespace().get("commandQueueLabel");
            commandFailureLabel = (Label) loader.getNamespace().get("commandFailureLabel");
            staleLabel = (Label) loader.getNamespace().get("staleLabel");
            tokenLabel = (Label) loader.getNamespace().get("tokenLabel");
            cacheLabel = (Label) loader.getNamespace().get("cacheLabel");
            queuePressureLabel = (Label) loader.getNamespace().get("queuePressureLabel");
            stalePressureLabel = (Label) loader.getNamespace().get("stalePressureLabel");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load operations-metrics-panel.fxml", e);
        }

        OperationsViewRefs refs = new OperationsViewRefs(
                updatedAtLabel,
                pcOnlineLabel,
                pcRatioLabel,
                commandQueueLabel,
                commandFailureLabel,
                staleLabel,
                tokenLabel,
                cacheLabel,
                queuePressureLabel,
                stalePressureLabel
        );
        refsMap.put(panelDto.getId(), refs);

        // Delete button permanently hidden; action wired for right-panel use
        deleteBtn.setVisible(false);
        deleteBtn.setManaged(false);
        deleteBtnMap.put(panelDto.getId(), deleteBtn);
        deleteBtn.setOnAction(e -> deletePanel(panelDto.getId()));

        refreshBtn.setOnAction(e -> refreshSnapshot(true, true));

        wrapper.getProperties().put("dashboardPanelId", panelDto.getId());
        wrapper.getProperties().put("panelType", "operations");
        wrapper.getProperties().put("resizeHandleHolder", resizeHandleHolder);
        wrapper.setLayoutX(panelDto.getX() != null ? panelDto.getX() : 100);
        wrapper.setLayoutY(panelDto.getY() != null ? panelDto.getY() : 100);
        double[] defaultSize = responsiveSizeFn.apply("OPERATIONS");
        wrapper.setPrefWidth(panelDto.getWidth() != null ? panelDto.getWidth() : defaultSize[0]);
        wrapper.setPrefHeight(panelDto.getHeight() != null ? panelDto.getHeight() : defaultSize[1]);

        return wrapper;
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
                selectedNodesSupplier,
                () -> persistPosition(panel, panelId, "운영 메트릭 패널 위치 저장됨")
        );

        StackPane handleHolder = (StackPane) panel.getProperties().get("resizeHandleHolder");
        Label handle = new Label("◢");
        handle.getStyleClass().add("drag-handle");
        handle.setVisible(false);
        handle.setManaged(false);
        resizeHandleMap.put(panelId, handle);
        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);
        handleHolder.getChildren().setAll(handle);
        panel.getProperties().put("resizeHandleNode", handle);

        interactionService.attachResizeHandlers(
                panel,
                handle,
                520,
                260,
                () -> canInteract.getAsBoolean() && dashboardIdSupplier.get() != null,
                snapFn,
                () -> persistPosition(panel, panelId, "운영 메트릭 패널 크기 저장됨")
        );

        panel.setOnMouseClicked(e -> {
            if (!canInteract.getAsBoolean()) return;
            boolean dragged = Boolean.TRUE.equals(panel.getProperties().get("__dragging__"));
            if (!dragged && onSelectCallback != null) {
                onSelectCallback.accept(panel, e.isControlDown());
                e.consume();
            }
        });
    }

    private void persistPosition(VBox panel, Long panelId, String toastMessage) {
        Long dashboardId = dashboardIdSupplier.get();
        if (dashboardId == null) {
            return;
        }
        runBeforePanelMutation();
        dashboardService.updatePanelPositionAsync(
                dashboardId,
                panelId,
                panel.getLayoutX(),
                panel.getLayoutY(),
                panel.getPrefWidth(),
                panel.getPrefHeight()
        );
        if (panelLayoutCacheUpdater != null) {
            panelLayoutCacheUpdater.update(
                    panelId,
                    panel.getLayoutX(),
                    panel.getLayoutY(),
                    panel.getPrefWidth(),
                    panel.getPrefHeight()
            );
        }
        showToast.accept(toastMessage, false);
    }

    private void runBeforePanelMutation() {
        if (beforePanelMutation == null) {
            return;
        }
        beforePanelMutation.run();
    }

    private void refreshSnapshot(boolean force, boolean notifyOnFailure) {
        if (panelMap.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && !latestSnapshot.isEmpty() && now - lastSnapshotFetchedAt < SNAPSHOT_REFRESH_INTERVAL_MS) {
            applySnapshotToAll(latestSnapshot);
            return;
        }

        if (!snapshotFetchInFlight.compareAndSet(false, true)) {
            return;
        }

        apiClient.getJson("/status/performance", new TypeReference<Map<String, Object>>() {})
                .thenAccept(snapshot -> Platform.runLater(() -> {
                    latestSnapshot = snapshot == null ? Map.of() : snapshot;
                    lastSnapshotFetchedAt = System.currentTimeMillis();
                    applySnapshotToAll(latestSnapshot);
                }))
                .exceptionally(ex -> {
                    log.warn("운영 메트릭 스냅샷 조회 실패", ex);
                    Platform.runLater(() -> {
                        markSnapshotFetchFailed();
                        if (notifyOnFailure) {
                            showToast.accept("운영 메트릭 조회 실패", true);
                        }
                    });
                    return null;
                })
                .whenComplete((v, t) -> snapshotFetchInFlight.set(false));
    }

    private void applySnapshotToAll(Map<String, Object> snapshot) {
        refsMap.values().forEach(refs -> applySnapshotToRefs(refs, snapshot));
    }

    private void markSnapshotFetchFailed() {
        refsMap.values().forEach(refs -> {
            if (refs == null || refs.updatedAtLabel() == null) {
                return;
            }
            refs.updatedAtLabel().setText("조회 실패");
        });
    }

    private void applySnapshotToRefs(OperationsViewRefs refs, Map<String, Object> snapshot) {
        if (refs == null || snapshot == null) {
            return;
        }

        Map<String, Object> pc = toMap(snapshot.get("pc"));
        Map<String, Object> command = toMap(snapshot.get("command"));
        Map<String, Object> token = toMap(snapshot.get("token"));
        Map<String, Object> cache = toMap(snapshot.get("cache"));
        Map<String, Object> operational = toMap(snapshot.get("operational"));
        Map<String, Object> latency = toMap(operational.get("latency"));
        Map<String, Object> transfer = toMap(operational.get("transfer"));
        Map<String, Object> request = toMap(operational.get("request"));
        Map<String, Object> pressure = toMap(snapshot.get("pressure"));

        long totalPc = toLong(pc.get("total"));
        long onlinePc = toLong(pc.get("online"));
        double onlineRatio = toDouble(pc.get("onlineRatio"));

        long queued = toLong(command.get("queued"));
        long inFlight = toLong(command.get("inFlight"));
        long completed = toLong(command.get("completed"));
        long failed = toLong(command.get("failed"));
        long staleDelivered = toLong(command.get("staleDelivered"));
        long staleAcked = toLong(command.get("staleAcked"));

        long activeToken = toLong(token.get("active"));
        long revokedToken = toLong(token.get("revoked"));
        long cacheSize = toLong(cache.get("estimatedSize"));
        double hitRate = toDouble(cache.get("hitRate"));

        double commandFailureRate = toDouble(latency.get("failureRate"));
        double ackP95Ms = toDouble(latency.get("ackP95Ms"));
        double resultP95Ms = toDouble(latency.get("resultP95Ms"));
        double uploadMbps = toDouble(transfer.get("uploadAvgMbps"));
        double downloadMbps = toDouble(transfer.get("downloadAvgMbps"));
        double transferFailureRate = toDouble(transfer.get("failureRate"));
        double forbiddenRate = toDouble(request.get("forbiddenRate"));
        double requestErrorRate = toDouble(request.get("errorRate"));

        if (refs.updatedAtLabel() != null) {
            refs.updatedAtLabel().setText(formatSnapshotTime(snapshot.get("timestamp")));
        }
        if (refs.pcOnlineLabel() != null) {
            refs.pcOnlineLabel().setText(onlinePc + " / " + totalPc);
        }
        if (refs.pcRatioLabel() != null) {
            refs.pcRatioLabel().setText(String.format("온라인 비율 %.1f%%", onlineRatio));
        }
        if (refs.commandQueueLabel() != null) {
            refs.commandQueueLabel().setText("대기 " + queued + " | 진행 " + inFlight);
        }
        if (refs.commandFailureLabel() != null) {
            refs.commandFailureLabel().setText(String.format("완료 %d | 실패 %d | 실패율 %.1f%%",
                    completed,
                    failed,
                    commandFailureRate * 100.0));
        }
        if (refs.staleLabel() != null) {
            refs.staleLabel().setText(String.format("전달지연 %d | ACK지연 %d | ACK P95 %.0fms | 결과 P95 %.0fms",
                    staleDelivered,
                    staleAcked,
                    ackP95Ms,
                    resultP95Ms));
        }
        if (refs.tokenLabel() != null) {
            refs.tokenLabel().setText(String.format("활성 %d | 폐기 %d | 403 %.1f%% | API 에러 %.1f%%",
                    activeToken,
                    revokedToken,
                    forbiddenRate * 100.0,
                    requestErrorRate * 100.0));
        }
        if (refs.cacheLabel() != null) {
            refs.cacheLabel().setText(String.format("hit %.1f%% | size %d | up/down %.1f/%.1f Mbps | 전송실패 %.1f%%",
                    hitRate * 100.0,
                    cacheSize,
                    uploadMbps,
                    downloadMbps,
                    transferFailureRate * 100.0));
        }

        String queuePressure = normalizePressure(pressure.get("queuePressure"));
        String stalePressure = normalizePressure(pressure.get("stalePressure"));
        if (refs.queuePressureLabel() != null) {
            refs.queuePressureLabel().setText(queuePressure);
            applyPressureStyle(refs.queuePressureLabel(), queuePressure);
        }
        if (refs.stalePressureLabel() != null) {
            refs.stalePressureLabel().setText(stalePressure);
            applyPressureStyle(refs.stalePressureLabel(), stalePressure);
        }
    }

    private void applyPressureStyle(Label label, String pressure) {
        if (label == null) {
            return;
        }
        label.getStyleClass().removeAll("status-online", "status-warning", "status-failed");
        switch (pressure) {
            case "HIGH" -> label.getStyleClass().add("status-failed");
            case "MEDIUM" -> label.getStyleClass().add("status-warning");
            default -> label.getStyleClass().add("status-online");
        }
    }

    private String normalizePressure(Object value) {
        if (value == null) {
            return "LOW";
        }
        String normalized = value.toString().trim().toUpperCase();
        return switch (normalized) {
            case "HIGH", "MEDIUM", "LOW" -> normalized;
            default -> "LOW";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String formatSnapshotTime(Object timestamp) {
        if (timestamp == null) {
            return "-";
        }
        if (timestamp instanceof LocalDateTime localDateTime) {
            return localDateTime.format(PERF_TIME_FORMAT);
        }
        if (timestamp instanceof Number num) {
            return formatEpochMillis(num.longValue());
        }

        String raw = timestamp.toString();
        try {
            return LocalDateTime.parse(raw).format(PERF_TIME_FORMAT);
        } catch (Exception ignore) {
            // no-op
        }

        try {
            return formatEpochMillis(Long.parseLong(raw));
        } catch (Exception ignore) {
            return raw;
        }
    }

    private String formatEpochMillis(long epochMaybeSeconds) {
        long epochMillis = Math.abs(epochMaybeSeconds) < 10_000_000_000L
                ? epochMaybeSeconds * 1_000L
                : epochMaybeSeconds;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(PERF_TIME_FORMAT);
    }

    private record OperationsViewRefs(
            Label updatedAtLabel,
            Label pcOnlineLabel,
            Label pcRatioLabel,
            Label commandQueueLabel,
            Label commandFailureLabel,
            Label staleLabel,
            Label tokenLabel,
            Label cacheLabel,
            Label queuePressureLabel,
            Label stalePressureLabel) {
    }
}
