package com.example.frontend.controller.panel;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;

public class ImagePanelController {
    private final VBox panel;
    private final Long imageId;

    public ImagePanelController(VBox panel, Long imageId) {
        this.panel = panel;
        this.imageId = imageId;
    }

    public VBox getPanel() {
        return panel;
    }

    public void updatePanel() {
        panel.getChildren().clear();
        panel.setSpacing(8);
        panel.setStyle("-fx-border-color: #ccc; -fx-border-width: 1; -fx-padding: 8;");

        Label title = new Label(imageId > 0 ? "이미지 ID: " + imageId : "이미지 패널");
        title.setStyle("-fx-font-weight: bold;");

        ImageView imageView = new ImageView();
        imageView.setFitWidth(240);
        imageView.setFitHeight(160);
        imageView.setPreserveRatio(true);
        imageView.setImage(new Image("https://via.placeholder.com/320x180.png?text=Image", true));

        Button refreshBtn = new Button("새로고침");
        refreshBtn.setOnAction(e -> {
            // TODO: 실제 API에서 이미지 로드
            imageView.setImage(new Image("https://via.placeholder.com/320x180.png?text=Updated", true));
        });

        HBox buttons = new HBox(5, refreshBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        panel.getChildren().addAll(title, imageView, buttons);
    }
}
