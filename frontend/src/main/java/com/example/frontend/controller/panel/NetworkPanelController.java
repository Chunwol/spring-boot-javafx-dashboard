package com.example.frontend.controller.panel;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
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
import java.util.HashMap;
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
    
    private final VBox panel;
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
    private Map<String, Timeline> pcRowBlinkingTimelines = new HashMap<>();

    public NetworkPanelController(ClassroomSummaryDto summary) {
        this.summary = summary;
        this.inboundSeries = new XYChart.Series<>();
        this.inboundSeries.setName("Inbound");
        this.outboundSeries = new XYChart.Series<>();
        this.outboundSeries.setName("Outbound");
        this.networkChart = createNetworkChart();
        this.inboundGauge = createGaugeBar("#2563eb");
        this.outboundGauge = createGaugeBar("#dc2626");
        this.inboundGaugeValue = createGaugeValueLabel();
        this.outboundGaugeValue = createGaugeValueLabel();
        this.pcTrafficTable = createPcTrafficTable();
        HBox gaugeRow = createGaugeRow();

        Label listTitle = new Label("PC별 트래픽");
        listTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #334155;");

        this.panel = new VBox(8, gaugeRow, networkChart, listTitle, pcTrafficTable);
        this.panel.setPadding(new Insets(10));
        VBox.setVgrow(networkChart, Priority.ALWAYS);
        updateGaugeValues(0.0, 0.0);
        
        // 설정값 비동기로 가져오기
        applyTrafficThresholdsAsync();
    }

    private LineChart<String, Number> createNetworkChart() {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("시간");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("트래픽 (Mbps)");
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("실시간 전체 네트워크 트래픽");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        xAxis.setStyle("-fx-font-size: 13px;");
        yAxis.setStyle("-fx-font-size: 13px;");
        chart.setStyle("-fx-font-size: 13px;");
        chart.getData().add(inboundSeries);
        chart.getData().add(outboundSeries);
        return chart;
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createPcTrafficTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPrefHeight(300);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label placeholder = new Label("PC 트래픽 데이터가 없습니다.");
        placeholder.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
        table.setPlaceholder(placeholder);

        TableColumn<Map<String, Object>, String> pcCol = new TableColumn<>("PC명");
        pcCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
        pcCol.setPrefWidth(90);

        TableColumn<Map<String, Object>, String> ipCol = new TableColumn<>("IP주소");
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
        ipCol.setPrefWidth(110);

        TableColumn<Map<String, Object>, String> inCol = new TableColumn<>("입력(Mbps)");
        inCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("inboundMbps"))));
        inCol.setPrefWidth(80);
        inCol.setStyle("-fx-alignment: CENTER_RIGHT;");

        TableColumn<Map<String, Object>, String> outCol = new TableColumn<>("출력(Mbps)");
        outCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("outboundMbps"))));
        outCol.setPrefWidth(80);
        outCol.setStyle("-fx-alignment: CENTER_RIGHT;");

        TableColumn<Map<String, Object>, Double> loadCol = new TableColumn<>("부하율");
        loadCol.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(
                combinedLoadRatio(d.getValue())));
        loadCol.setPrefWidth(75);
        loadCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private Timeline blinkingTimeline;
            {
                bar.setPrefWidth(60);
                bar.setStyle("-fx-accent: #0f766e;");
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }

            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    stopBlinking();
                    return;
                }
                bar.setProgress(Math.max(0.0, Math.min(1.0, value)));

                // PC당 임계값 초과 시 깜빡거림 및 색상 변경
                double pcLoad = value;
                if (pcLoad > 1.0) {  // 임계값 초과 (300M 기준)
                    bar.setStyle("-fx-accent: #ff0000;");  // 빨간색
                    startBlinking();
                } else {
                    bar.setStyle("-fx-accent: #0f766e;");  // 기본 색상
                    stopBlinking();
                }

                setGraphic(bar);
            }

            private void startBlinking() {
                if (blinkingTimeline == null || blinkingTimeline.getStatus() == javafx.animation.Animation.Status.STOPPED) {
                    // 이전 타임라인 정리 (메모리 누수 방지)
                    if (blinkingTimeline != null) {
                        blinkingTimeline.stop();
                    }

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
                    blinkingTimeline = null;  // 메모리 정리
                }
                bar.setOpacity(1.0);
            }
        });

        table.getColumns().addAll(pcCol, ipCol, inCol, outCol, loadCol);
        table.setStyle("-fx-font-family: 'Noto Sans CJK KR'; -fx-font-size: 14px;");
        return table;
    }

    public VBox getPanel() {
        return panel;
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
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #334155;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, title, spacer, valueLabel);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox box = new VBox(4, top, gauge);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");
        box.setPrefWidth(260);
        return box;
    }

    private ProgressBar createGaugeBar(String color) {
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(240);
        bar.setStyle("-fx-accent: " + color + ";");
        return bar;
    }

    private Label createGaugeValueLabel() {
        Label label = new Label("0.00 Mbps");
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: #0f172a; -fx-font-weight: 700;");
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
        gauge.setStyle(gauge.getStyle() + "; -fx-border-color: #ff0000; -fx-border-width: 2;");
    }

    private void stopGaugeBlinking(ProgressBar gauge, Timeline timeline) {
        if (timeline != null && timeline.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            timeline.stop();
        }
        // 빨간 테두리 제거 및 기본 상태로 복원
        gauge.setOpacity(1.0);
        String style = gauge.getStyle();
        if (style.contains("-fx-border-color")) {
            style = style.replaceAll("; -fx-border-color: #ff0000; -fx-border-width: 2;", "");
            gauge.setStyle(style);
        }
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
