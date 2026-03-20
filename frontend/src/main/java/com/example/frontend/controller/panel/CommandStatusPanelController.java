package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 명령 진행 현황 패널.
 * GET /api/v1/commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=100 로 최근 명령을 폴링하고,
 * 진행 중(QUEUED/DELIVERED/ACKED)인 타입만 표시하여 실제 완료율을 계산한다.
 */
public class CommandStatusPanelController {

    private final ApiClient apiClient = ApiClient.getInstance();

    private VBox rootPanel;
    private VBox commandListBox;
    private Label summaryLabel;
    private ScheduledExecutorService scheduler;

    public CommandStatusPanelController() {
    }

    /** 패널 VBox 반환 */
    public VBox getPanel() {
        if (rootPanel == null) {
            rootPanel = buildPanel();
        }
        return rootPanel;
    }

    private VBox buildPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(12));
        panel.setStyle("-fx-background-color: #1e2a3a; -fx-background-radius: 6;");
        panel.setPrefSize(400, 260);

        // ─── 헤더 ──────────────────────────────────────────────
        Label title = new Label("⚙ 명령 진행 현황");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setTextFill(Color.WHITE);

        Button refreshBtn = new Button("↺");
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaa;");
        refreshBtn.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, spacer, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        summaryLabel = new Label("조회 중...");
        summaryLabel.setFont(Font.font("Segoe UI", 11));
        summaryLabel.setTextFill(Color.web("#aaaaaa"));

        // ─── 명령 목록 ─────────────────────────────────────────
        commandListBox = new VBox(6);
        ScrollPane scrollPane = new ScrollPane(commandListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        panel.getChildren().addAll(header, summaryLabel, new Separator(), scrollPane);

        // 자동 폴링 (10초)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cmd-status-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, 10, TimeUnit.SECONDS);

        // 패널이 scene에서 제거될 때 scheduler 자동 종료
        panel.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) shutdown();
        });

        rootPanel = panel;
        return panel;
    }

    /** 서버에서 최근 명령 목록을 가져와 UI를 갱신한다. */
    public void refresh() {
        // 모든 상태를 조회해야 완료/실패 카운트가 가능
        apiClient.getPagedList("/commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=100",
                new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(commands -> Platform.runLater(() -> updateUI(commands)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> summaryLabel.setText("서버 연결 오류"));
                    return null;
                });
    }

    private void updateUI(List<Map<String, Object>> commands) {
        commandListBox.getChildren().clear();

        // 명령 유형별 그룹화
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> cmd : commands) {
            String type = String.valueOf(cmd.getOrDefault("commandType", "UNKNOWN"));
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(cmd);
        }

        // 진행 중(QUEUED/DELIVERED/ACKED)이 있는 타입만 표시
        List<String> activeTypes = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            boolean hasActive = entry.getValue().stream().anyMatch(c -> {
                String s = String.valueOf(c.getOrDefault("status", ""));
                return "QUEUED".equals(s) || "DELIVERED".equals(s) || "ACKED".equals(s);
            });
            if (hasActive) activeTypes.add(entry.getKey());
        }

        if (activeTypes.isEmpty()) {
            Label empty = new Label("진행 중인 명령 없음");
            empty.setTextFill(Color.web("#888"));
            commandListBox.getChildren().add(empty);
            summaryLabel.setText("대기 중인 명령 없음");
            return;
        }

        summaryLabel.setText("진행 중 유형: " + activeTypes.size() + "건");

        for (String type : activeTypes) {
            List<Map<String, Object>> group = grouped.get(type);

            long done = group.stream()
                    .filter(c -> "COMPLETED".equals(c.get("status")))
                    .count();
            long failed = group.stream()
                    .filter(c -> "FAILED".equals(c.get("status")))
                    .count();
            long total = group.size();
            long inProgress = total - done - failed;

            VBox cmdBox = new VBox(4);
            cmdBox.setStyle("-fx-background-color: #2a3a4a; -fx-background-radius: 4; -fx-padding: 8;");

            Label cmdLabel = new Label(type + "  → " + total + "개 대상");
            cmdLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
            cmdLabel.setTextFill(Color.WHITE);

            ProgressBar bar = new ProgressBar(total > 0 ? (double) done / total : 0);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle(failed > 0
                    ? "-fx-accent: #e74c3c;"
                    : "-fx-accent: #2ecc71;");

            Label statLabel = new Label(String.format(
                    "완료: %d  실패: %d  대기: %d", done, failed, inProgress));
            statLabel.setFont(Font.font("Segoe UI", 11));
            statLabel.setTextFill(Color.web("#aaaaaa"));

            cmdBox.getChildren().addAll(cmdLabel, bar, statLabel);
            commandListBox.getChildren().add(cmdBox);
        }
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
