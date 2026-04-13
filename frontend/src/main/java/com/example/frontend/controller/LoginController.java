package com.example.frontend.controller;

import com.example.frontend.service.ApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Map;
import java.util.Locale;
import java.util.prefs.Preferences;

public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);
    private static final Preferences PREFS = Preferences.userNodeForPackage(LoginController.class);
    private static final String PREF_SERVER_HOST = "server.host";
    private static final String PREF_SERVER_PORT = "server.port";

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private TextField hostField;

    @FXML
    private TextField portField;

    @FXML
    private Label messageLabel;

    private final ApiClient apiClient = ApiClient.getInstance();

    private Stage primaryStage;

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    @FXML
    public void initialize() {
        hostField.setText(PREFS.get(PREF_SERVER_HOST, apiClient.currentLoginHost()));
        portField.setText(PREFS.get(PREF_SERVER_PORT, Integer.toString(apiClient.currentLoginPort())));

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
        String host = hostField.getText();
        String portText = portField.getText();
        messageLabel.setVisible(false);

        if (host == null || host.isBlank()) {
            messageLabel.setText("도메인 또는 IP를 입력하세요.");
            messageLabel.setVisible(true);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out-of-range");
            }
        } catch (Exception ex) {
            messageLabel.setText("포트는 1~65535 범위의 숫자여야 합니다.");
            messageLabel.setVisible(true);
            return;
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            messageLabel.setText("서버 정보와 사용자명, 비밀번호를 입력하세요.");
            messageLabel.setVisible(true);
            return;
        }

        try {
            apiClient.configureServer(host, port);
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
                        Object roleObj = body.get("role");
                        String role = roleObj == null ? "USER" : roleObj.toString();
                        apiClient.setCurrentUserRole(role.toUpperCase(Locale.ROOT));
                        PREFS.put(PREF_SERVER_HOST, host.trim());
                        PREFS.put(PREF_SERVER_PORT, Integer.toString(port));
                        try {
                            showDashboard();
                        } catch (Exception ex) {
                            log.warn("Failed to transition to dashboard after login", ex);
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
            log.warn("Login request failed before async dispatch", e);
            messageLabel.setText("서버 연결 실패. 백엔드가 실행 중인지 확인하세요.\n" + e.getMessage());
            messageLabel.setVisible(true);
        }
    }

    private void showDashboard() throws Exception {
        var fxmlUrl = getClass().getResource("/fxml/dashboard.fxml");
        if (fxmlUrl == null) {
            throw new RuntimeException("dashboard.fxml 리소스를 찾을 수 없습니다: /fxml/dashboard.fxml (classpath 경로 문제)");
        }
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        if (primaryStage == null) {
            throw new IllegalStateException("primaryStage가 null입니다. setPrimaryStage 호출 필요");
        }

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double targetWidth = Math.max(1000, Math.min(1360, bounds.getWidth() - 120));
        double targetHeight = Math.max(680, Math.min(860, bounds.getHeight() - 120));

        // hide()로 Stage 레이아웃 캐시를 초기화한 뒤 Scene 교체 → show().
        // hide 없이 setScene만 하면 이전 Scene(460×360) 크기로 내부가 잡히는 문제 발생.
        primaryStage.hide();

        Scene scene = new Scene(root, targetWidth, targetHeight);
        scene.getStylesheets().add(getClass().getResource("/dashboard-theme.css").toExternalForm());

        primaryStage.setTitle("대시보드");
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(680);
        primaryStage.setScene(scene);
        primaryStage.setWidth(targetWidth);
        primaryStage.setHeight(targetHeight);
        root.applyCss();
        root.layout();
        primaryStage.centerOnScreen();
        primaryStage.show();
    }
}
