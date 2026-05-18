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
import javafx.scene.control.CheckBox;
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
    private static final String PREF_REMEMBER_USER = "remember.user";
    private static final String PREF_USERNAME_KEY = "username";

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

    @FXML
    private CheckBox rememberUserCheck;

    private final ApiClient apiClient = ApiClient.getInstance();

    private Stage primaryStage;

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    @FXML
    public void initialize() {
        // 숨김 시 레이아웃 공간도 해제
        messageLabel.managedProperty().bind(messageLabel.visibleProperty());

        hostField.setText(PREFS.get(PREF_SERVER_HOST, apiClient.currentLoginHost()));
        portField.setText(PREFS.get(PREF_SERVER_PORT, Integer.toString(apiClient.currentLoginPort())));
        // username/remember 설정 로드
        usernameField.setText(PREFS.get(PREF_USERNAME_KEY, ""));
        rememberUserCheck.setSelected(PREFS.getBoolean(PREF_REMEMBER_USER, false));

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
                            messageLabel.setText("로그인 응답이 올바르지 않습니다. 다시 시도해 주세요.");
                            messageLabel.setVisible(true);
                            return;
                        }
                        apiClient.setBearerToken(tokenObj.toString());
                        Object roleObj = body.get("role");
                        String role = roleObj == null ? "USER" : roleObj.toString();
                        apiClient.setCurrentUserRole(role.toUpperCase(Locale.ROOT));
                        PREFS.put(PREF_SERVER_HOST, host.trim());
                        PREFS.put(PREF_SERVER_PORT, Integer.toString(port));
                        // 사용자 기억 옵션 처리
                        if (rememberUserCheck != null && rememberUserCheck.isSelected()) {
                            PREFS.put(PREF_USERNAME_KEY, username == null ? "" : username.trim());
                            PREFS.putBoolean(PREF_REMEMBER_USER, true);
                        } else {
                            PREFS.remove(PREF_USERNAME_KEY);
                            PREFS.putBoolean(PREF_REMEMBER_USER, false);
                        }
                        try {
                            showDashboard();
                        } catch (Exception ex) {
                            log.warn("Failed to transition to dashboard after login", ex);
                            messageLabel.setText("화면 전환 중 오류가 발생했습니다. 다시 로그인해 주세요.");
                            messageLabel.setVisible(true);
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String userMessage = ApiClient.userFacingErrorMessage(ex, "로그인 요청을 처리하지 못했습니다.");
                            messageLabel.setText("로그인 실패: " + userMessage);
                            messageLabel.setVisible(true);
                        });
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Login request failed before async dispatch", e);
            messageLabel.setText("서버 연결 실패. 백엔드 실행 상태와 주소/포트를 확인하세요.");
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

        // macOS DPI 스케일링을 고려한 보수적인 창 크기 계산
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        // macOS Retina: getVisualBounds()가 네이티브 픽셀 기준일 수 있으므로 더 작게 제한
        double screenWidth = bounds.getWidth();
        double screenHeight = bounds.getHeight();
        double maxWidth = Math.min(1280, screenWidth * 0.8);   // 화면의 80% 이하
        double maxHeight = Math.min(800, screenHeight * 0.8);  // 화면의 80% 이하
        double targetWidth = Math.max(1000, maxWidth);
        double targetHeight = Math.max(680, maxHeight);

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
        
        // macOS에서 전체화면처럼 보이는 문제 방지
        primaryStage.setFullScreen(false);
        primaryStage.setIconified(false);
        
        root.applyCss();
        root.layout();
        primaryStage.centerOnScreen();
        primaryStage.show();
    }
}
