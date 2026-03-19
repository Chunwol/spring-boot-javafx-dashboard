package com.example.frontend.controller.panel;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.frontend.service.ApiClient;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.Priority;
import javafx.application.Platform;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkPanelController {
    private static final Logger log = LoggerFactory.getLogger(NetworkPanelController.class);
    private final VBox panel;
    private final ClassroomSummaryDto summary;
    private final LineChart<String, Number> networkChart;
    private final XYChart.Series<String, Number> inboundSeries;
    private final XYChart.Series<String, Number> outboundSeries;
    private final ApiClient apiClient = ApiClient.getInstance();

    public NetworkPanelController(ClassroomSummaryDto summary) {
        this.summary = summary;
        this.inboundSeries = new XYChart.Series<>();
        this.inboundSeries.setName("Inbound");
        this.outboundSeries = new XYChart.Series<>();
        this.outboundSeries.setName("Outbound");
        this.networkChart = createNetworkChart();
        this.panel = new VBox(networkChart);
        this.panel.setPadding(new Insets(10));
        VBox.setVgrow(networkChart, Priority.ALWAYS);
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
        chart.getData().add(inboundSeries);
        chart.getData().add(outboundSeries);
        return chart;
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
        if (inboundSeries.getData().size() > 20) {
            inboundSeries.getData().remove(0);
        }
        if (outboundSeries.getData().size() > 20) {
            outboundSeries.getData().remove(0);
        }
    }
}
