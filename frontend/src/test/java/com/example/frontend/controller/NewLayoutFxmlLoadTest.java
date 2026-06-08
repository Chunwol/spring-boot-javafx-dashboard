package com.example.frontend.controller;

import com.example.frontend.controller.dashboard.FxTestHelper;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 신규 메인 레이아웃(2026-06 리디자인) FXML이 컨트롤러와 정상 결합되어
 * 로드되는지 검증한다. fx:id 누락, 컨트롤러 메서드 시그니처 불일치,
 * CSS 파싱 오류, FontIcon literal 오타 등을 런타임에 잡아낸다.
 */
class NewLayoutFxmlLoadTest {

    @BeforeAll
    static void initFx() {
        FxTestHelper.initToolkit();
    }

    private Parent loadOnFxThread(String fxmlPath) throws Exception {
        AtomicReference<Parent> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
                Parent root = loader.load();
                // CSS도 함께 적용해 스타일 클래스 파싱 오류까지 검증
                Scene scene = new Scene(root);
                scene.getStylesheets().add(
                        getClass().getResource("/dashboard-theme.css").toExternalForm());
                root.applyCss();
                root.layout();
                result.set(root);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("FXML 로드 타임아웃: " + fxmlPath);
        }
        if (error.get() != null) {
            throw new AssertionError("FXML 로드 실패: " + fxmlPath, error.get());
        }
        return result.get();
    }

    @Test
    void mainFxmlLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/main.fxml"), "main.fxml 루트가 null");
    }

    @Test
    void classroomStatusTabLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/classroom-status-tab.fxml"),
                "classroom-status-tab.fxml 루트가 null");
    }

    @Test
    void placeholderTabLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/placeholder-tab.fxml"),
                "placeholder-tab.fxml 루트가 null");
    }

    @Test
    void networkTabLoads() throws Exception {
        // CircularGauge 생성 + 강의실/PC 테이블 슬롯 결합까지 initialize()에서 검증
        assertNotNull(loadOnFxThread("/fxml/network-tab.fxml"),
                "network-tab.fxml 루트가 null");
    }

    @Test
    void pcMgmtViewControlsLoads() throws Exception {
        // PC관리 검색창/상태 필터 추가 후 파싱 검증
        assertNotNull(loadOnFxThread("/fxml/pcmgmt-view-controls.fxml"),
                "pcmgmt-view-controls.fxml 루트가 null");
    }

    @Test
    void agentSectionLoads() throws Exception {
        // 에이전트 섹션 검색창(TextField/Region/Priority import) 추가 후 파싱 검증
        assertNotNull(loadOnFxThread("/fxml/mgmt-agent-section.fxml"),
                "mgmt-agent-section.fxml 루트가 null");
    }

    @Test
    void commandStatusPanelLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/command-status-panel.fxml"),
                "command-status-panel.fxml 루트가 null");
    }

    @Test
    void pcmgmtPcTableLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/pcmgmt-pc-table.fxml"),
                "pcmgmt-pc-table.fxml 루트가 null");
    }

    @Test
    void pcmgmtCommandSectionLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/pcmgmt-command-section.fxml"),
                "pcmgmt-command-section.fxml 루트가 null");
    }

    @Test
    void mgmtViewLoads() throws Exception {
        assertNotNull(loadOnFxThread("/fxml/mgmt-view.fxml"),
                "mgmt-view.fxml 루트가 null");
    }
}
