package com.example.frontend;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class DashboardApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        // 로그인 화면 먼저 로드
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
        Parent root = loader.load();

        // LoginController에 Stage 전달 (로그인 성공 후 화면 전환용)
        com.example.frontend.controller.LoginController controller = loader.getController();
        controller.setPrimaryStage(primaryStage);

        primaryStage.setTitle("Smart Classroom Dashboard - Login");
        Scene scene = new Scene(root, 460, 360);
        scene.getStylesheets().add(getClass().getResource("/dashboard-theme.css").toExternalForm());
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(320);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}