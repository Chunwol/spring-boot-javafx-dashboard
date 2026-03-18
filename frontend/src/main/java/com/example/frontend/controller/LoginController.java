package com.example.frontend.controller;

import com.example.frontend.service.ApiClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.util.Map;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Label messageLabel;

    private final ApiClient apiClient = ApiClient.getInstance();

    private Stage primaryStage;

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    @FXML
    public void initialize() {
        // Enter 키 입력 시 로그인 수행
        passwordField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) handleLogin();
        });
        usernameField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) handleLogin();
        });
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText();
        String password = passwordField.getText();
        messageLabel.setVisible(false);

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            messageLabel.setText("사용자명과 비밀번호를 입력하세요.");
            messageLabel.setVisible(true);
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "username", username,
                    "password", password
            );
            apiClient.postJson("/auth/login", payload)
                    .thenApply(apiClient::parseObject)
                    .thenAccept(body -> Platform.runLater(() -> {
                        Object tokenObj = body.get("token");
                        if (tokenObj == null) {
                            messageLabel.setText("로그인 응답에 토큰이 없습니다.");
                            messageLabel.setVisible(true);
                            return;
                        }
                        apiClient.setBearerToken(tokenObj.toString());
                        try {
                            showDashboard();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            messageLabel.setText("화면 전환 오류: " + ex.getMessage());
                            messageLabel.setVisible(true);
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            messageLabel.setText("로그인 실패: " + cause.getMessage());
                            messageLabel.setVisible(true);
                        });
                        return null;
                    });
        } catch (Exception e) {
            e.printStackTrace();
            messageLabel.setText("서버 연결 실패. 백엔드가 실행 중인지 확인하세요.\n" + e.getMessage());
            messageLabel.setVisible(true);
        }
    }

    private void showDashboard() throws Exception {
        // FXML 경로를 URL로 안전하게 로드 (표준 방식)
        var fxmlUrl = getClass().getResource("/fxml/dashboard.fxml");
        if (fxmlUrl == null) {
            throw new RuntimeException("dashboard.fxml 리소스를 찾을 수 없습니다: /fxml/dashboard.fxml (classpath 경로 문제)");
        }
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        if (primaryStage == null) {
            throw new IllegalStateException("primaryStage가 null입니다. setPrimaryStage 호출 필요");
        }
        Scene scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/dashboard-theme.css").toExternalForm());
        primaryStage.setMinWidth(1280);
        primaryStage.setMinHeight(800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Dashboard");
        primaryStage.setMaximized(true);
    }
}
