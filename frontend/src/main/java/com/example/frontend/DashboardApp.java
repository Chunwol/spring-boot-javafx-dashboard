package com.example.frontend;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

public class DashboardApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        loadFonts(); // Pretendard — 스타일시트 적용 전에 등록해야 함
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        // 로그인 화면 먼저 로드
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
        Parent root = loader.load();

        // LoginController에 Stage 전달 (로그인 성공 후 화면 전환용)
        com.example.frontend.controller.LoginController controller = loader.getController();
        controller.setPrimaryStage(primaryStage);

        primaryStage.setTitle("스마트 강의실 대시보드 - 로그인");
        Scene scene = new Scene(root, 480, 540);
        scene.getStylesheets().add(getClass().getResource("/dashboard-theme.css").toExternalForm());
        primaryStage.setMinWidth(400);
        primaryStage.setMinHeight(480);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /** 번들된 Pretendard 폰트를 JavaFX에 등록한다. CSS의 -fx-font-family:"Pretendard"가 적용되도록. */
    private void loadFonts() {
        for (String weight : new String[]{"Regular", "Medium", "SemiBold", "Bold"}) {
            try (InputStream is = getClass().getResourceAsStream("/fonts/Pretendard-" + weight + ".ttf")) {
                if (is != null) {
                    Font.loadFont(is, 12);
                }
            } catch (Exception ignored) {
                // 폰트 로드 실패 시 시스템 기본 폰트로 폴백 (CSS의 2순위 글꼴)
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}