package com.example.frontend.controller.dashboard;

import com.example.frontend.controller.panel.ImagePanelController;
import com.example.frontend.controller.panel.NetworkPanelController;
import com.example.frontend.controller.panel.TextPanelController;
import com.example.frontend.service.DashboardService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class DashboardControllerV2 {
    @FXML private VBox rootVBox;
    @FXML private ScrollPane scrollPane;

    private final DashboardService dashboardService = new DashboardService();
    private NetworkPanelController networkPanelController;
    private TextPanelController textPanelController;
    private ImagePanelController imagePanelController;

    @FXML
    public void initialize() {
        if (rootVBox == null) return;
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        networkPanelController = new NetworkPanelController(null);
        textPanelController = new TextPanelController(new VBox(), -1L);
        imagePanelController = new ImagePanelController(new VBox(), -1L);

        tabPane.getTabs().addAll(
            new Tab("통합 관제", new Label("통합 관제 대시보드")),
            new Tab("네트워크", networkPanelController.getPanel()),
            new Tab("텍스트", textPanelController.getPanel()),
            new Tab("이미지", imagePanelController.getPanel())
        );
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        rootVBox.getChildren().setAll(tabPane);
        Platform.runLater(this::refreshDashboardData);
    }

    @FXML
    private void handleRefreshButtonAction() {
        refreshDashboardData();
    }

    @FXML
    private void handleGeneratePdf() {
        try {
            new com.example.frontend.service.DashboardPdfService().generateTrafficPdf(rootVBox.getScene().getWindow());
        } catch (Exception e) {
            showAlert("PDF 생성 실패", e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void refreshDashboardData() {
        dashboardService.fetchClassroomsAsync(classrooms -> {
            if (networkPanelController != null) {
                networkPanelController.updateNetworkChart();
            }
        });
    }
}
