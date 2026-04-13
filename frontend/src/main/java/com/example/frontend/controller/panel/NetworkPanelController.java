package com.example.frontend.controller.panel;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkPanelController {
    private static final Logger log = LoggerFactory.getLogger(NetworkPanelController.class);
    // Default gauge max - will be overridden by config
    private double gaugeMaxMbps = 1000.0;
    private double classroomThresholdMbps = 1000.0;
    private double pcThresholdMbps = 300.0;

    @FXML
    private VBox panel;

    @FXML
    private HBox gaugeRowSlot;

    @FXML
    private StackPane chartSlot;

    @FXML
    private StackPane tableSlot;

    private final ClassroomSummaryDto summary;
    private final LineChart<String, Number> networkChart;
    private final XYChart.Series<String, Number> inboundSeries;
    private final XYChart.Series<String, Number> outboundSeries;
    private final TableView<Map<String, Object>> pcTrafficTable;
    private final ProgressBar inboundGauge;
    private final ProgressBar outboundGauge;
    private final Label inboundGaugeValue;
    private final Label outboundGaugeValue;
    private final ApiClient apiClient = ApiClient.getInstance();
    private final DashboardService dashboardService = new DashboardService();
    private Timeline inboundBlinkingTimeline;
    private Timeline outboundBlinkingTimeline;

    public NetworkPanelController(ClassroomSummaryDto summary) {
        this.summary = summary;
        loadPanelFxml();
        this.inboundSeries = new XYChart.Series<>();
        this.inboundSeries.setName("인바운드");
        this.outboundSeries = new XYChart.Series<>();
        this.outboundSeries.setName("아웃바운드");
        this.networkChart = createNetworkChart();
        this.inboundGauge = createGaugeBar("gauge-bar-inbound");
        this.outboundGauge = createGaugeBar("gauge-bar-outbound");
        this.inboundGaugeValue = createGaugeValueLabel();
        this.outboundGaugeValue = createGaugeValueLabel();
        this.pcTrafficTable = createPcTrafficTable();
        HBox gaugeRow = createGaugeRow();

        if (gaugeRowSlot != null) {
            gaugeRowSlot.getChildren().setAll(gaugeRow);
        }
        if (chartSlot != null) {
            chartSlot.getChildren().setAll(networkChart);
        }
        if (tableSlot != null) {
            tableSlot.getChildren().setAll(pcTrafficTable);
        }

        updateGaugeValues(0.0, 0.0);
        
        // 설정값 비동기로 가져오기
        applyTrafficThresholdsAsync();
    }

    private void loadPanelFxml() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/network-panel.fxml"));
            loader.setController(this);
            panel = loader.load();
        } catch (Exception e) {
            throw new IllegalStateException("네트워크 패널 FXML 로딩 실패", e);
        }
    }

    private LineChart<String, Number> createNetworkChart() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/network-chart.fxml"));
            LineChart<String, Number> chart = loader.load();
            chart.getData().add(inboundSeries);
            chart.getData().add(outboundSeries);
            return chart;
        } catch (Exception e) {
            throw new IllegalStateException("네트워크 차트 FXML 로딩 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createPcTrafficTable() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/network-traffic-table.fxml"));
            TableView<Map<String, Object>> table = loader.load();
            var ns = loader.getNamespace();

            TableColumn<Map<String, Object>, String> pcCol = (TableColumn<Map<String, Object>, String>) ns.get("pcCol");
            TableColumn<Map<String, Object>, String> ipCol = (TableColumn<Map<String, Object>, String>) ns.get("ipCol");
            TableColumn<Map<String, Object>, String> inCol = (TableColumn<Map<String, Object>, String>) ns.get("inCol");
            TableColumn<Map<String, Object>, String> outCol = (TableColumn<Map<String, Object>, String>) ns.get("outCol");
            TableColumn<Map<String, Object>, Double> loadCol = (TableColumn<Map<String, Object>, Double>) ns.get("loadCol");

            pcCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
            ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
            inCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("inboundMbps"))));
            outCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("outboundMbps"))));

            loadCol.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(combinedLoadRatio(d.getValue())));
            loadCol.setCellFactory(col -> new TableCell<>() {
                private final ProgressBar bar = new ProgressBar(0);
                private Timeline blinkingTimeline;
                {
                    bar.setPrefWidth(60);
                    bar.getStyleClass().add("network-progress-normal");
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                }

                @Override protected void updateItem(Double value, boolean empty) {
                    super.updateItem(value, empty);
                    if (empty || value == null) { setGraphic(null); stopBlinking(); return; }
                    bar.setProgress(Math.max(0.0, Math.min(1.0, value)));
                    if (value > 1.0) {
                        bar.getStyleClass().removeAll("network-progress-normal", "network-progress-warning");
                        bar.getStyleClass().add("network-progress-warning");
                        startBlinking();
                    } else {
                        bar.getStyleClass().removeAll("network-progress-warning", "network-progress-normal");
                        bar.getStyleClass().add("network-progress-normal");
                        stopBlinking();
                    }
                    setGraphic(bar);
                }

                private void startBlinking() {
                    if (blinkingTimeline == null || blinkingTimeline.getStatus() == javafx.animation.Animation.Status.STOPPED) {
                        if (blinkingTimeline != null) blinkingTimeline.stop();
                        blinkingTimeline = new Timeline();
                        blinkingTimeline.setCycleCount(Timeline.INDEFINITE);
                        blinkingTimeline.getKeyFrames().addAll(
                            new KeyFrame(Duration.millis(0), e -> bar.setOpacity(1.0)),
                            new KeyFrame(Duration.millis(300), e -> bar.setOpacity(1.0)),
                            new KeyFrame(Duration.millis(600), e -> bar.setOpacity(0.3)),
                            new KeyFrame(Duration.millis(900), e -> bar.setOpacity(0.3))
                        );
                        blinkingTimeline.play();
                    }
                }

                private void stopBlinking() {
                    if (blinkingTimeline != null && blinkingTimeline.getStatus() == javafx.animation.Animation.Status.RUNNING) {
                        blinkingTimeline.stop();
                        blinkingTimeline = null;
                    }
                    bar.setOpacity(1.0);
                }
            });

            return table;
        } catch (Exception e) {
            throw new IllegalStateException("PC 트래픽 테이블 FXML 로딩 실패", e);
        }
    }

    public VBox getPanel() {
        return panel;
    }

    /**
     * 리소스 정리: 실행 중인 Timeline 애니메이션을 모두 중지합니다.
     * 패널 제거 시 반드시 호출해야 메모리 누수를 방지합니다.
     */
    public void shutdown() {
        if (inboundBlinkingTimeline != null) {
            inboundBlinkingTimeline.stop();
            inboundBlinkingTimeline = null;
        }
        if (outboundBlinkingTimeline != null) {
            outboundBlinkingTimeline.stop();
            outboundBlinkingTimeline = null;
        }
    }

    // 네트워크 트래픽 데이터 갱신
    public void updateNetworkChart() {
        apiClient.get("/dashboard/traffic")
                .thenAccept(body -> {
                    try {
                        JsonNode node = apiClient.getObjectMapper().readTree(body);
                        double inTraffic = node.path("inboundMbps").asDouble(0.0);
                        double outTraffic = node.path("outboundMbps").asDouble(0.0);
                        Platform.runLater(() -> {
                            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                            inboundSeries.getData().add(new XYChart.Data<>(now, inTraffic));
                            outboundSeries.getData().add(new XYChart.Data<>(now, outTraffic));
                            if (inboundSeries.getData().size() > 20) inboundSeries.getData().remove(0);
                            if (outboundSeries.getData().size() > 20) outboundSeries.getData().remove(0);
                        });
                    } catch (Exception e) {
                        log.warn("Failed to parse network chart payload", e);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Failed to fetch network traffic for chart", ex);
                    return null;
                });
    }

    public void updateNetworkChart(double inboundMbps, double outboundMbps) {
        Platform.runLater(() -> appendTrafficPoint(inboundMbps, outboundMbps));
    }

    private void appendTrafficPoint(double inboundMbps, double outboundMbps) {
        String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        inboundSeries.getData().add(new XYChart.Data<>(now, inboundMbps));
        outboundSeries.getData().add(new XYChart.Data<>(now, outboundMbps));
        updateGaugeValues(inboundMbps, outboundMbps);
        if (inboundSeries.getData().size() > 20) {
            inboundSeries.getData().remove(0);
        }
        if (outboundSeries.getData().size() > 20) {
            outboundSeries.getData().remove(0);
        }
    }

    public void updatePcTraffic(List<Map<String, Object>> rows) {
        Platform.runLater(() -> pcTrafficTable.getItems().setAll(rows == null ? List.of() : rows));
    }

    public void refreshAllPcTraffic() {
        if (summary != null && summary.getGroupId() != null) {
            dashboardService.fetchClassroomPcTrafficAsync(summary.getGroupId(), this::updatePcTraffic);
            return;
        }

        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
                .thenCompose(classrooms -> {
                    if (classrooms == null || classrooms.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<Map<String, Object>>of());
                    }

                    List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
                    for (ClassroomSummaryDto classroom : classrooms) {
                        if (classroom == null || classroom.getGroupId() == null) {
                            continue;
                        }
                        CompletableFuture<List<Map<String, Object>>> future = apiClient.getJson(
                                        "/dashboard/classrooms/" + classroom.getGroupId() + "/traffic/pcs",
                                        new TypeReference<List<Map<String, Object>>>() {})
                                .exceptionally(ex -> {
                                    log.warn("Failed to fetch pc traffic for classroom {}", classroom.getGroupId(), ex);
                                    return List.of();
                                });
                        futures.add(future);
                    }

                    if (futures.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<Map<String, Object>>of());
                    }

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                List<Map<String, Object>> merged = new ArrayList<>();
                                for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                                    List<Map<String, Object>> rows = future.getNow(List.of());
                                    if (rows != null && !rows.isEmpty()) {
                                        merged.addAll(rows);
                                    }
                                }
                                return merged;
                            });
                })
                .thenAccept(this::updatePcTraffic)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch all pc traffic", ex);
                    updatePcTraffic(List.of());
                    return null;
                });
    }

    private String str(Object value) {
        if (value == null) {
            return "-";
        }
        String s = value.toString();
        return s.isBlank() ? "-" : s;
    }

    private String formatMbps(Object value) {
        if (value instanceof Number number) {
            return String.format("%.2f", number.doubleValue());
        }
        try {
            return String.format("%.2f", Double.parseDouble(str(value)));
        } catch (Exception ex) {
            return "0.00";
        }
    }

    private HBox createGaugeRow() {
        VBox inBox = createGaugeBox("인바운드", inboundGauge, inboundGaugeValue);
        VBox outBox = createGaugeBox("아웃바운드", outboundGauge, outboundGaugeValue);
        HBox row = new HBox(12, inBox, outBox);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private VBox createGaugeBox(String titleText, ProgressBar gauge, Label valueLabel) {
        Label title = new Label(titleText);
        title.getStyleClass().add("network-panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, title, spacer, valueLabel);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox box = new VBox(4, top, gauge);
        box.setPadding(new Insets(8));
        box.getStyleClass().add("network-stat-box");
        box.setPrefWidth(260);
        return box;
    }

    private ProgressBar createGaugeBar(String color) {
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(240);
        bar.getStyleClass().add(color);
        return bar;
    }

    private Label createGaugeValueLabel() {
        Label label = new Label("0.00 Mbps");
        label.getStyleClass().add("network-value-label");
        return label;
    }

    private void updateGaugeValues(double inboundMbps, double outboundMbps) {
        inboundGauge.setProgress(Math.max(0.0, Math.min(1.0, inboundMbps / gaugeMaxMbps)));
        outboundGauge.setProgress(Math.max(0.0, Math.min(1.0, outboundMbps / gaugeMaxMbps)));
        inboundGaugeValue.setText(String.format("%.2f Mbps", inboundMbps));
        outboundGaugeValue.setText(String.format("%.2f Mbps", outboundMbps));
        
        // 임계값 체크 및 깜빡거림 처리
        checkGaugeThresholds(inboundMbps, outboundMbps);
    }

    private void checkGaugeThresholds(double inboundMbps, double outboundMbps) {
        // 강의실 트래픽이 임계값을 초과하면 깜빡거리기
        if (inboundMbps > classroomThresholdMbps) {
            if (inboundBlinkingTimeline == null || inboundBlinkingTimeline.getStatus() == javafx.animation.Animation.Status.STOPPED) {
                startGaugeBlinking(inboundGauge);
                inboundBlinkingTimeline = createBlinkingTimeline(inboundGauge);
            }
        } else {
            stopGaugeBlinking(inboundGauge, inboundBlinkingTimeline);
            inboundBlinkingTimeline = null;
        }
        
        if (outboundMbps > classroomThresholdMbps) {
            if (outboundBlinkingTimeline == null || outboundBlinkingTimeline.getStatus() == javafx.animation.Animation.Status.STOPPED) {
                startGaugeBlinking(outboundGauge);
                outboundBlinkingTimeline = createBlinkingTimeline(outboundGauge);
            }
        } else {
            stopGaugeBlinking(outboundGauge, outboundBlinkingTimeline);
            outboundBlinkingTimeline = null;
        }
    }

    private Timeline createBlinkingTimeline(ProgressBar gauge) {
        Timeline timeline = new Timeline();
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.getKeyFrames().addAll(
            new KeyFrame(Duration.millis(0), e -> gauge.setOpacity(1.0)),
            new KeyFrame(Duration.millis(300), e -> gauge.setOpacity(1.0)),
            new KeyFrame(Duration.millis(600), e -> gauge.setOpacity(0.3)),
            new KeyFrame(Duration.millis(900), e -> gauge.setOpacity(0.3))
        );
        timeline.play();
        return timeline;
    }

    private void startGaugeBlinking(ProgressBar gauge) {
        gauge.getStyleClass().add("gauge-alert");
    }

    private void stopGaugeBlinking(ProgressBar gauge, Timeline timeline) {
        if (timeline != null && timeline.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            timeline.stop();
        }
        // 빨간 테두리 제거 및 기본 상태로 복원
        gauge.setOpacity(1.0);
        gauge.getStyleClass().remove("gauge-alert");
    }

    private double combinedLoadRatio(Map<String, Object> row) {
        double in = toDouble(row.get("inboundMbps"));
        double out = toDouble(row.get("outboundMbps"));
        return (in + out) / pcThresholdMbps;
    }

    private void applyTrafficThresholdsAsync() {
        dashboardService.fetchTrafficThresholdsAsync(thresholds -> {
            Platform.runLater(() -> {
                if (thresholds != null) {
                    Double global = thresholds.get("globalMbps");
                    Double classroom = thresholds.get("classroomMbps");
                    Double pc = thresholds.get("pcMbps");
                    
                    if (global != null) {
                        // 게이지 기준값은 강의실 임계값의 1.5배로 설정하여 시각적 여유 제공
                        this.gaugeMaxMbps = classroom != null ? classroom * 1.5 : global;
                    }
                    if (classroom != null) {
                        this.classroomThresholdMbps = classroom;
                    }
                    if (pc != null) {
                        this.pcThresholdMbps = pc;
                    }
                    
                    log.debug("Traffic thresholds applied: gauge={}, classroom={}, pc={}", 
                        gaugeMaxMbps, classroomThresholdMbps, pcThresholdMbps);
                }
            });
        });
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(str(value));
        } catch (Exception ex) {
            return 0.0;
        }
    }
}
