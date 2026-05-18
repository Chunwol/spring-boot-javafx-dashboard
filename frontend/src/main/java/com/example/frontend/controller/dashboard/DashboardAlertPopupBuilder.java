package com.example.frontend.controller.dashboard;

import com.example.frontend.model.dashboard.DashboardAlertItem;
import javafx.scene.control.Button;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 운영 경보 팝업 메뉴 UI를 생성한다.
 */
public final class DashboardAlertPopupBuilder {

    private DashboardAlertPopupBuilder() {
    }

    public static CustomMenuItem buildRootMenuItem(
            List<DashboardAlertItem> alertItems,
            boolean viewerMode,
            Runnable onRefresh,
            Runnable onAcknowledgeAll,
            Consumer<DashboardAlertItem> onAcknowledgeOne
    ) {
        List<DashboardAlertItem> safeItems = alertItems == null ? List.of() : alertItems;

        VBox container = new VBox(8);
        container.getStyleClass().add("alerts-menu-container");

        HBox header = new HBox(8);
        Label title = new Label("운영 경보");
        title.getStyleClass().add("alerts-menu-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        long unreadCountInList = safeItems.stream()
                .filter(item -> item != null && !item.isAcknowledged())
                .count();

        Button refreshButton = new Button("새로고침");
        refreshButton.getStyleClass().add("alerts-menu-refresh-btn");
        refreshButton.setOnAction(event -> {
            event.consume();
            if (onRefresh != null) {
                onRefresh.run();
            }
        });

        header.getChildren().addAll(title, spacer);
        if (!viewerMode && unreadCountInList > 0 && onAcknowledgeAll != null) {
            Button ackAllButton = new Button("전체 확인");
            ackAllButton.getStyleClass().add("alerts-menu-ackall-btn");
            ackAllButton.setOnAction(event -> {
                event.consume();
                onAcknowledgeAll.run();
            });
            header.getChildren().add(ackAllButton);
        }
        header.getChildren().add(refreshButton);

        VBox alertList = new VBox(6);
        if (safeItems.isEmpty()) {
            Label emptyLabel = new Label("현재 표시할 경보가 없습니다.");
            emptyLabel.getStyleClass().add("alerts-empty-label");
            alertList.getChildren().add(emptyLabel);
        } else {
            for (DashboardAlertItem item : safeItems) {
                if (item == null) {
                    continue;
                }
                alertList.getChildren().add(buildAlertCard(item, viewerMode, onAcknowledgeOne));
            }
        }

        ScrollPane scrollPane = new ScrollPane(alertList);
        scrollPane.getStyleClass().add("alerts-list-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportHeight(330);
        scrollPane.setPrefWidth(420);

        container.getChildren().addAll(header, scrollPane);
        return new CustomMenuItem(container, false);
    }

    private static VBox buildAlertCard(
            DashboardAlertItem item,
            boolean viewerMode,
            Consumer<DashboardAlertItem> onAcknowledgeOne
    ) {
        VBox card = new VBox(6);
        card.getStyleClass().add("alerts-item");
        if (item.isCritical()) {
            card.getStyleClass().add("alerts-item-critical");
        }

        Label messageLabel = new Label(item.getMessage() == null ? "(내용 없음)" : item.getMessage());
        messageLabel.getStyleClass().add("alerts-item-message");
        messageLabel.setWrapText(true);

        HBox metaRow = new HBox(8);
        Label severityLabel = new Label(DashboardAlertSupport.severityDisplayText(item.getSeverity()));
        severityLabel.getStyleClass().add(DashboardAlertSupport.severityStyleClass(item.getSeverity()));

        String source = item.getSourceMac();
        if (source == null || source.isBlank()) {
            source = "SYSTEM";
        }
        Label sourceLabel = new Label(source);
        sourceLabel.getStyleClass().add("alerts-item-meta");

        Label timeLabel = new Label(DashboardAlertSupport.formatAlertTime(item.getCreatedAt()));
        timeLabel.getStyleClass().add("alerts-item-meta");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        metaRow.getChildren().addAll(severityLabel, sourceLabel, timeLabel, spacer);

        if (!item.isAcknowledged()) {
            if (viewerMode) {
                Label pendingLabel = new Label("미확인");
                pendingLabel.getStyleClass().add("alerts-item-meta");
                metaRow.getChildren().add(pendingLabel);
            } else {
                Button ackButton = new Button("확인");
                ackButton.getStyleClass().add("alerts-ack-button");
                ackButton.setOnAction(event -> {
                    event.consume();
                    if (onAcknowledgeOne != null) {
                        onAcknowledgeOne.accept(item);
                    }
                });
                metaRow.getChildren().add(ackButton);
            }
        } else {
            Label acknowledgedLabel = new Label("확인됨");
            acknowledgedLabel.getStyleClass().add("alerts-acknowledged");
            metaRow.getChildren().add(acknowledgedLabel);
        }

        card.getChildren().addAll(messageLabel, metaRow);
        return card;
    }
}
