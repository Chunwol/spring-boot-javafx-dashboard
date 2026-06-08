package com.example.frontend.ui;

import com.example.frontend.component.CircularGauge;
import com.example.frontend.controller.dashboard.FxTestHelper;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.application.Platform;
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
 * CircularGauge + 강의실 트래픽 카드를 가짜 데이터로 렌더링해 PNG로 저장한다.
 * 백엔드/로그인 없이 게이지가 반원으로 그려지는지, 카드 레이아웃이 맞는지 육안 검증용.
 *
 * 출력: build/network-gauge-snapshot.png
 */
class NetworkGaugeSnapshotUiSmokeTest {

    @BeforeAll
    static void initFx() {
        FxTestHelper.initToolkit();
    }

    @Test
    void renderNetworkTabVisual() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                Color in = Color.web("#2563eb");
                Color out = Color.web("#f59e0b");

                // 상단 전체 게이지 (radius 90)
                CircularGauge totalIn = new CircularGauge("인바운드", 5000, in, "Mbps", 90);
                CircularGauge totalOut = new CircularGauge("아웃바운드", 5000, out, "Mbps", 90);
                totalIn.setValue(1850);
                totalOut.setValue(920);
                HBox totalRow = new HBox(24, totalIn, totalOut);
                totalRow.setAlignment(Pos.CENTER_LEFT);
                totalRow.getStyleClass().add("network-total-card");
                totalRow.setPadding(new Insets(14, 20, 14, 20));

                // 강의실 카드 그리드
                FlowPane grid = new FlowPane(12, 12);
                // 500, 50 같은 큰/소수 값 포함해 클리핑 여부 확인
                double[][] data = {{500, 50}, {980, 60}, {1100, 240}, {75, 15}};
                String[] names = {"109-1호", "109-2호", "110호", "정보실"};
                for (int i = 0; i < data.length; i++) {
                    CircularGauge cardIn = new CircularGauge("IN", 1000, in, "", 50);
                    CircularGauge cardOut = new CircularGauge("OUT", 1000, out, "", 50);
                    cardIn.setValue(data[i][0]);
                    cardOut.setValue(data[i][1]);
                    Label title = new Label(names[i]);
                    title.getStyleClass().add("net-card-title");
                    HBox gauges = new HBox(6, cardIn, cardOut);
                    gauges.setAlignment(Pos.CENTER);
                    VBox card = new VBox(6, title, gauges);
                    card.setAlignment(Pos.CENTER);
                    card.getStyleClass().add("net-classroom-card");
                    grid.getChildren().add(card);
                }

                VBox root = new VBox(16, totalRow, new Label("강의실별 트래픽"), grid);
                root.setPadding(new Insets(20));
                root.setStyle("-fx-background-color: #f1f5f9;");

                Scene scene = new Scene(root, 900, 520);
                scene.getStylesheets().add(
                        getClass().getResource("/dashboard-theme.css").toExternalForm());
                root.applyCss();
                root.layout();

                SnapshotParameters params = new SnapshotParameters();
                WritableImage img = root.snapshot(params, null);
                File outFile = new File("build/network-gauge-snapshot.png");
                outFile.getParentFile().mkdirs();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", outFile);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("스냅샷 렌더 타임아웃");
        }
        if (error.get() != null) {
            throw new AssertionError("스냅샷 렌더 실패", error.get());
        }
        assertTrue(new File("build/network-gauge-snapshot.png").exists(),
                "스냅샷 PNG가 생성되지 않음");
    }
}
