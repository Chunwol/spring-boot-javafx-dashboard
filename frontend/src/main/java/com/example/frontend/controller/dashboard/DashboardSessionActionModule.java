package com.example.frontend.controller.dashboard;

import com.example.frontend.controller.LoginController;
import com.example.frontend.service.ApiClient;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.net.URL;
import java.util.function.BiConsumer;

/**
 * 세션 만료/로그아웃 시 로그인 화면 전환을 담당한다.
 */
public class DashboardSessionActionModule {

    private final ApiClient apiClient;
    private final Runnable shutdownAction;
    private final BiConsumer<String, Boolean> toastConsumer;
    private final Class<?> resourceAnchor;

    public DashboardSessionActionModule(
            ApiClient apiClient,
            Runnable shutdownAction,
            BiConsumer<String, Boolean> toastConsumer,
            Class<?> resourceAnchor
    ) {
        this.apiClient = apiClient;
        this.shutdownAction = shutdownAction;
        this.toastConsumer = toastConsumer;
        this.resourceAnchor = resourceAnchor == null ? DashboardSessionActionModule.class : resourceAnchor;
    }

    /**
     * 로그인 화면으로 이동한다.
     *
     * @param window 현재 윈도우
     * @param alertMessage null이면 경고창 미표시
     * @param shouldShutdown true면 이동 전에 정리 콜백 실행
     */
    public void moveToLoginScreen(Window window, String alertMessage, boolean shouldShutdown) throws Exception {
        // 윈도우 핸들 사전 검증 (토큰 지우기 전)
        if (!(window instanceof Stage stage)) {
            showToast("윈도우 핸들을 찾을 수 없습니다.", true);
            return;
        }

        // FXML 경로는 classpath 절대경로로 고정 (클래스 패키지 무관)
        URL fxmlUrl = DashboardSessionActionModule.class.getResource("/fxml/login.fxml");
        if (fxmlUrl == null) {
            showToast("로그인 화면을 찾을 수 없습니다.", true);
            return;
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent loginRoot = loader.load();
        LoginController loginController = loader.getController();

        // 즉시 화면을 숨겨 사용자가 "멈춘" 느낌을 받지 않도록 함
        stage.hide();

        // 토큰 제거 및 정리 (화면 숨긴 후 진행)
        apiClient.setBearerToken(null);
        if (shouldShutdown && shutdownAction != null) {
            shutdownAction.run();
        }

        loginController.setPrimaryStage(stage);
        Scene loginScene = new Scene(loginRoot, 460, 360);
        URL css = DashboardSessionActionModule.class.getResource("/dashboard-theme.css");
        if (css != null) {
            loginScene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(loginScene);
        stage.setTitle("스마트 강의실 대시보드 - 로그인");
        stage.setMinWidth(420);
        stage.setMinHeight(320);
        stage.setMaximized(false);
        stage.setFullScreen(false);
        stage.setWidth(460);
        stage.setHeight(360);
        loginRoot.applyCss();
        loginRoot.layout();
        stage.centerOnScreen();
        stage.show();

        if (alertMessage != null && !alertMessage.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("서버 연결 실패");
            alert.setHeaderText("서버 연결이 끊어졌습니다.");
            alert.setContentText(alertMessage);
            alert.show();
        }
    }

    private void showToast(String message, boolean error) {
        if (toastConsumer != null) {
            toastConsumer.accept(message, error);
        }
    }
}
