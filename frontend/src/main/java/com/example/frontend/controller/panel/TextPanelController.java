package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import javafx.scene.layout.VBox;

import javafx.scene.control.TextArea;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.control.Label;
import javafx.scene.Cursor;
import javafx.geometry.Pos;

public class TextPanelController {
    private final VBox panel;
    private final Long textId;
    private final ApiClient apiClient = ApiClient.getInstance();
    private double startX, startY;

    public TextPanelController(VBox panel, Long textId) {
        this.panel = panel;
        this.textId = textId;
    }

    public VBox getPanel() {
        return panel;
    }

    // 텍스트 패널 UI 초기화 및 이벤트 바인딩
    public void updatePanel() {
        panel.getChildren().clear();
        TextArea textArea = new TextArea();
        textArea.setWrapText(true);
        panel.getChildren().add(textArea);
        makeInteractive(panel, textArea, textId);
        makeResizable(panel, textId);
    }

    private void makeInteractive(VBox box, TextArea textArea, Long id) {
        box.setOnMousePressed(e -> {
            startX = e.getSceneX() - box.getLayoutX();
            startY = e.getSceneY() - box.getLayoutY();
            box.toFront();
        });
        box.setOnMouseDragged(e -> {
            box.setLayoutX(e.getSceneX() - startX);
            box.setLayoutY(e.getSceneY() - startY);
        });
        box.setOnMouseReleased(e -> {
            updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
        });
        textArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { // 포커스 잃으면 저장
                updateTextItem(id, textArea.getText(), box.getLayoutX(), box.getLayoutY(), box.getPrefWidth(), box.getPrefHeight());
            }
        });
    }

    private void makeResizable(VBox panel, Long textId) {
        Label resizeHandle = new Label("◢");
        resizeHandle.setTextFill(Color.GRAY);
        resizeHandle.setCursor(Cursor.SE_RESIZE);
        resizeHandle.setAlignment(Pos.BOTTOM_RIGHT);
        HBox handleBox = new HBox(resizeHandle);
        handleBox.setAlignment(Pos.BOTTOM_RIGHT);
        panel.getChildren().add(handleBox);
        resizeHandle.setOnMouseDragged(e -> {
            double newWidth = e.getSceneX() - panel.localToScene(panel.getBoundsInLocal()).getMinX();
            double newHeight = e.getSceneY() - panel.localToScene(panel.getBoundsInLocal()).getMinY();
            if (newWidth > 200) panel.setPrefWidth(newWidth);
            if (newHeight > 100) panel.setPrefHeight(newHeight);
            e.consume();
        });
        resizeHandle.setOnMouseReleased(e -> {
            TextArea ta = (TextArea) panel.getChildren().get(0);
            updateTextItem(textId, ta.getText(), panel.getLayoutX(), panel.getLayoutY(), panel.getPrefWidth(), panel.getPrefHeight());
            e.consume();
        });
    }

    private void updateTextItem(Long id, String text, double x, double y, double w, double h) {
        apiClient.putJson("/dashboard/texts/" + id, java.util.Map.of(
            "text", text,
            "x", x,
            "y", y,
            "width", w,
            "height", h
        )).exceptionally(ex -> null);
    }
}
