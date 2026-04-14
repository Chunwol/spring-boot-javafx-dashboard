package com.example.frontend.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

class LoginUiSmokeTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("/fxml/login.fxml"));
        Scene scene = new Scene(root, 480, 360);
        var stylesheet = getClass().getResource("/dashboard-theme.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle("Login UI Smoke");
        stage.show();
    }

    @Test
    void showsValidationMessageWhenUsernameOrPasswordMissing() throws Exception {
        TextField hostField = lookup("#hostField").queryAs(TextField.class);
        TextField portField = lookup("#portField").queryAs(TextField.class);
        TextField usernameField = lookup("#usernameField").queryAs(TextField.class);
        PasswordField passwordField = lookup("#passwordField").queryAs(PasswordField.class);

        clickOn(hostField);
        eraseText(hostField.getText() == null ? 0 : hostField.getText().length());
        write("localhost");

        clickOn(portField);
        eraseText(portField.getText() == null ? 0 : portField.getText().length());
        write("8888");

        clickOn(usernameField);
        eraseText(usernameField.getText() == null ? 0 : usernameField.getText().length());

        clickOn(passwordField);
        eraseText(passwordField.getText() == null ? 0 : passwordField.getText().length());

        clickOn("로그인");

        Label messageLabel = lookup("#messageLabel").queryAs(Label.class);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, messageLabel::isVisible);
        Assertions.assertTrue(messageLabel.isVisible(), "검증 실패 메시지가 표시되어야 합니다.");
        Assertions.assertTrue(messageLabel.getText().contains("사용자명"), "메시지에 사용자명 안내가 포함되어야 합니다.");
        Assertions.assertTrue(messageLabel.getText().contains("비밀번호"), "메시지에 비밀번호 안내가 포함되어야 합니다.");
    }
}
