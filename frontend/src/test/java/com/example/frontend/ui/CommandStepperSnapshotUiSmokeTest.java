package com.example.frontend.ui;

import com.example.frontend.controller.dashboard.FxTestHelper;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 명령 진행 스텝퍼(대기중→전송됨→실행중→완료)를 상태별로 렌더링해 PNG로 저장한다.
 * CSS(cmd-step-*)가 done/active/pending/failed/connector를 의도대로 칠하는지 육안 검증.
 *
 * 출력: build/command-stepper-snapshot.png
 */
class CommandStepperSnapshotUiSmokeTest {

    private static final String[] STEP_LABELS = {"대기중", "전송됨", "실행중", "완료"};

    @BeforeAll
    static void initFx() {
        FxTestHelper.initToolkit();
    }

    /** NetworkTabController/CommandStatusPanelController.renderProgressSteps와 동일한 구성. */
    private HBox buildStepper(int current, boolean failed) {
        HBox box = new HBox();
        box.getStyleClass().add("cmd-step-row");
        box.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < STEP_LABELS.length; i++) {
            Label step = new Label(STEP_LABELS[i]);
            step.getStyleClass().add("cmd-step");
            if (failed && i == current) step.getStyleClass().add("cmd-step-failed");
            else if (i < current) step.getStyleClass().add("cmd-step-done");
            else if (i == current) step.getStyleClass().add("cmd-step-active");
            else step.getStyleClass().add("cmd-step-pending");
            box.getChildren().add(step);
            if (i < STEP_LABELS.length - 1) {
                Region c = new Region();
                c.getStyleClass().add("cmd-step-connector");
                if (i < current) c.getStyleClass().add("cmd-step-connector-done");
                HBox.setHgrow(c, Priority.ALWAYS);
                box.getChildren().add(c);
            }
        }
        return box;
    }

    private VBox labeled(String caption, HBox stepper) {
        Label l = new Label(caption);
        VBox v = new VBox(4, l, stepper);
        v.setPrefWidth(380);
        return v;
    }

    @Test
    void renderStepperVisual() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                VBox root = new VBox(16,
                        labeled("대기중 (QUEUED)", buildStepper(0, false)),
                        labeled("전송됨 (DELIVERED)", buildStepper(1, false)),
                        labeled("실행중 (ACKED)", buildStepper(2, false)),
                        labeled("완료 (COMPLETED)", buildStepper(3, false)),
                        labeled("실패 (FAILED)", buildStepper(3, true)));
                root.setPadding(new Insets(20));
                root.setStyle("-fx-background-color: white;");

                Scene scene = new Scene(root, 420, 360);
                scene.getStylesheets().add(
                        getClass().getResource("/dashboard-theme.css").toExternalForm());
                root.applyCss();
                root.layout();

                WritableImage img = root.snapshot(new javafx.scene.SnapshotParameters(), null);
                File out = new File("build/command-stepper-snapshot.png");
                out.getParentFile().mkdirs();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) fail("스텝퍼 렌더 타임아웃");
        if (error.get() != null) throw new AssertionError("스텝퍼 렌더 실패", error.get());
        assertTrue(new File("build/command-stepper-snapshot.png").exists(), "스냅샷 PNG 미생성");
    }
}
