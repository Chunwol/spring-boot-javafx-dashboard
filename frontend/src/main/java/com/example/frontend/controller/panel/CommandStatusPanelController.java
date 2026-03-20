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
 * GET /api/v1/commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=200 로 최근 명령을 폴링하고,
 * 상태별 집계와 타입별 진행중(QUEUED/DELIVERED/ACKED) 수를 표시한다.
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
        apiClient.getPagedList("/commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=200",
                new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(commands -> Platform.runLater(() -> updateUI(commands)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> summaryLabel.setText("서버 연결 오류"));
                    return null;
                });
    }

    private void updateUI(List<Map<String, Object>> commands) {
        commandListBox.getChildren().clear();

        List<Map<String, Object>> recent = commands == null ? List.of() : commands.stream().limit(80).toList();

        long queued = recent.stream().filter(c -> "QUEUED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
        long delivered = recent.stream().filter(c -> "DELIVERED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
        long acked = recent.stream().filter(c -> "ACKED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
        long completed = recent.stream().filter(c -> "COMPLETED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
        long failed = recent.stream().filter(c -> "FAILED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
        long activeTotal = queued + delivered + acked;

        if (recent.isEmpty()) {
            Label empty = new Label("진행 중인 명령 없음");
            empty.setTextFill(Color.web("#888"));
            commandListBox.getChildren().add(empty);
            summaryLabel.setText("대기 중인 명령 없음");
            return;
        }

        summaryLabel.setText(String.format(
                "최근 80건 기준: 진행중 %d (Q:%d D:%d A:%d) / 완료 %d / 실패 %d",
                activeTotal, queued, delivered, acked, completed, failed));

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> cmd : recent) {
            String type = String.valueOf(cmd.getOrDefault("commandType", cmd.getOrDefault("type", "UNKNOWN")));
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(cmd);
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            String type = entry.getKey();
            List<Map<String, Object>> group = entry.getValue();

            long q = group.stream().filter(c -> "QUEUED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
            long d = group.stream().filter(c -> "DELIVERED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
            long a = group.stream().filter(c -> "ACKED".equalsIgnoreCase(String.valueOf(c.getOrDefault("status", "")))).count();
            long c = group.stream().filter(x -> "COMPLETED".equalsIgnoreCase(String.valueOf(x.getOrDefault("status", "")))).count();
            long f = group.stream().filter(x -> "FAILED".equalsIgnoreCase(String.valueOf(x.getOrDefault("status", "")))).count();
            long active = q + d + a;
            long total = group.size();

            if (active == 0 && c == 0 && f == 0) {
                continue;
            }

            VBox cmdBox = new VBox(4);
            cmdBox.setStyle("-fx-background-color: #2a3a4a; -fx-background-radius: 4; -fx-padding: 8;");

            Label cmdLabel = new Label(type + "  → 총 " + total + "건");
            cmdLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
            cmdLabel.setTextFill(Color.WHITE);

            ProgressBar bar = new ProgressBar(total > 0 ? (double) (c + f) / total : 0);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle(f > 0
                    ? "-fx-accent: #e74c3c;"
                    : "-fx-accent: #2ecc71;");

            Label statLabel = new Label(String.format(
                    "Q:%d D:%d A:%d  완료:%d 실패:%d", q, d, a, c, f));
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
