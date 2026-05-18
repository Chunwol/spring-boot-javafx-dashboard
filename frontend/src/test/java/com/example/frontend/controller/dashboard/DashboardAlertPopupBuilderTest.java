package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAlertPopupBuilderTest {

    @BeforeAll
    static void initToolkit() {
        new JFXPanel();
    }

    @Test
    void buildRootMenuItem_whenEmpty_shouldRenderEmptyStateAndRefreshCallback() throws InterruptedException {
        runOnFxAndWait(() -> {
            AtomicInteger refreshCount = new AtomicInteger(0);
            CustomMenuItem root = DashboardAlertPopupBuilder.buildRootMenuItem(
                    List.of(),
                    true,
                    refreshCount::incrementAndGet,
                    null,
                    null
            );

            Parent content = (Parent) root.getContent();
            assertThat(findLabelByText(content, "현재 표시할 경보가 없습니다.")).isNotNull();
            assertThat(findButtonByText(content, "전체 확인")).isNull();

            Button refreshButton = findButtonByText(content, "새로고침");
            assertThat(refreshButton).isNotNull();
            refreshButton.fire();
            assertThat(refreshCount.get()).isEqualTo(1);
        });
    }

    @Test
    void buildRootMenuItem_whenViewerMode_shouldShowPendingAndHideAckButtons() throws InterruptedException {
        runOnFxAndWait(() -> {
            DashboardAlertItem unread = item(1L, "오프라인 경보", false, "WARNING");

            CustomMenuItem root = DashboardAlertPopupBuilder.buildRootMenuItem(
                    List.of(unread),
                    true,
                    null,
                    null,
                    null
            );

            Parent content = (Parent) root.getContent();
            assertThat(findLabelByText(content, "미확인")).isNotNull();
            assertThat(findButtonByText(content, "확인")).isNull();
            assertThat(findButtonByText(content, "전체 확인")).isNull();
        });
    }

    @Test
    void buildRootMenuItem_whenEditorMode_shouldWireAckCallbacks() throws InterruptedException {
        runOnFxAndWait(() -> {
            DashboardAlertItem unread = item(10L, "업데이트 후 재부팅 필요", false, "CRITICAL");
            AtomicInteger ackAllCount = new AtomicInteger(0);
            AtomicReference<Long> ackedId = new AtomicReference<>(null);

            CustomMenuItem root = DashboardAlertPopupBuilder.buildRootMenuItem(
                    List.of(unread),
                    false,
                    null,
                    ackAllCount::incrementAndGet,
                    item -> ackedId.set(item.getId())
            );

            Parent content = (Parent) root.getContent();
            Button ackAllButton = findButtonByText(content, "전체 확인");
            assertThat(ackAllButton).isNotNull();
            ackAllButton.fire();
            assertThat(ackAllCount.get()).isEqualTo(1);

            Button ackButton = findButtonByText(content, "확인");
            assertThat(ackButton).isNotNull();
            ackButton.fire();
            assertThat(ackedId.get()).isEqualTo(10L);
        });
    }

    private static DashboardAlertItem item(Long id, String message, boolean acknowledged, String severity) {
        DashboardAlertItem item = new DashboardAlertItem();
        item.setId(id);
        item.setMessage(message);
        item.setAcknowledged(acknowledged);
        item.setSeverity(severity);
        item.setSourceMac("AA-BB-CC");
        item.setCreatedAt(LocalDateTime.of(2026, 4, 17, 9, 30));
        return item;
    }

    private static Button findButtonByText(Parent root, String text) {
        for (Button button : collectNodes(root, Button.class)) {
            if (text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Label findLabelByText(Parent root, String text) {
        for (Label label : collectNodes(root, Label.class)) {
            if (text.equals(label.getText())) {
                return label;
            }
        }
        return null;
    }

    private static <T extends Node> List<T> collectNodes(Parent root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectNodesRecursive(root, type, out);
        return out;
    }

    private static <T extends Node> void collectNodesRecursive(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof ScrollPane scrollPane && scrollPane.getContent() != null) {
            collectNodesRecursive(scrollPane.getContent(), type, out);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectNodesRecursive(child, type, out);
            }
        }
    }

    private static void runOnFxAndWait(Runnable runnable) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }
}
