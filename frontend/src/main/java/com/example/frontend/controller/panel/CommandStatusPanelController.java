package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.beans.property.SimpleStringProperty;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
    private TableView<Map<String, Object>> commandTable;
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
        panel.setStyle("-fx-background-color: #f8fbff; -fx-background-radius: 8; -fx-border-color: #cbd5e0; -fx-border-radius: 8;");
        panel.setPrefSize(400, 260);

        Label title = new Label("명령 상태");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setTextFill(Color.web("#0f172a"));

        Button refreshBtn = new Button("↺");
        refreshBtn.setStyle("-fx-background-color: #edf2f7; -fx-text-fill: #2d3748; -fx-font-weight: 700;");
        refreshBtn.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, spacer, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        summaryLabel = new Label("조회 중...");
        summaryLabel.setFont(Font.font("Segoe UI", 11));
        summaryLabel.setTextFill(Color.web("#334155"));

        commandTable = createCommandTable();
        VBox.setVgrow(commandTable, Priority.ALWAYS);

        panel.getChildren().addAll(header, summaryLabel, new Separator(), commandTable);

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

    private TableView<Map<String, Object>> createCommandTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPlaceholder(new Label("명령 로그가 없습니다."));

        TableColumn<Map<String, Object>, String> timeCol = new TableColumn<>("시간");
        timeCol.setPrefWidth(140);
        timeCol.setCellValueFactory(data -> new SimpleStringProperty(extractTime(data.getValue())));

        TableColumn<Map<String, Object>, String> typeCol = new TableColumn<>("종류");
        typeCol.setPrefWidth(80);
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "commandType", "type")));

        TableColumn<Map<String, Object>, String> targetCol = new TableColumn<>("대상");
        targetCol.setPrefWidth(110);
        targetCol.setCellValueFactory(data -> new SimpleStringProperty(extractTarget(data.getValue())));

        // 상태 컬럼 - SCMATE 상태 포함
        TableColumn<Map<String, Object>, String> statusCol = new TableColumn<>("상태");
        statusCol.setPrefWidth(140);
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(extractDetailedStatus(data.getValue())));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    // 상태별 색상 표시
                    if (item.contains("시점저장")) {
                        setStyle("-fx-text-fill: #d97706; -fx-font-weight: 700;"); // 주황색 - 주의
                    } else if (item.contains("COMPLETED")) {
                        setStyle("-fx-text-fill: #10b981; -fx-font-weight: 600;"); // 초록색 - 완료
                    } else if (item.contains("DELIVERED")) {
                        setStyle("-fx-text-fill: #3b82f6; -fx-font-weight: 600;"); // 파란색 - 전송
                    } else if (item.contains("ACKED")) {
                        setStyle("-fx-text-fill: #8b5cf6; -fx-font-weight: 600;"); // 보라색 - 실행중
                    } else if (item.contains("FAILED")) {
                        setStyle("-fx-text-fill: #ef4444; -fx-font-weight: 600;"); // 빨강색 - 실패
                    }
                }
            }
        });

        TableColumn<Map<String, Object>, String> exitCol = new TableColumn<>("종료코드");
        exitCol.setPrefWidth(60);
        exitCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "exitCode")));

        table.getColumns().addAll(timeCol, typeCol, targetCol, statusCol, exitCol);
        return table;
    }

    private void updateUI(List<Map<String, Object>> commands) {
        List<Map<String, Object>> recent = commands == null ? List.of() : commands.stream().limit(120).toList();

        long queued = recent.stream().filter(c -> "QUEUED".equals(normalizeStatus(asText(c, "status")))).count();
        long delivered = recent.stream().filter(c -> "DELIVERED".equals(normalizeStatus(asText(c, "status")))).count();
        long acked = recent.stream().filter(c -> "ACKED".equals(normalizeStatus(asText(c, "status")))).count();
        long completed = recent.stream().filter(c -> "COMPLETED".equals(normalizeStatus(asText(c, "status")))).count();
        long failed = recent.stream().filter(c -> "FAILED".equals(normalizeStatus(asText(c, "status")))).count();
        
        // SCMATE 상태 카운팅
        long scmateReset = recent.stream()
            .filter(c -> "PENDING".equals(asText(c, "scmateResetStatus")))
            .count();
        long activeTotal = queued + delivered + acked + scmateReset;

        if (recent.isEmpty()) {
            summaryLabel.setText("대기 중인 명령 없음");
            commandTable.setItems(FXCollections.observableArrayList());
            return;
        }

        summaryLabel.setText(String.format(
                "최근 120건: 진행중 %d (Q:%d D:%d A:%d) / 시점저장 %d / 완료 %d / 실패 %d",
                activeTotal, queued, delivered, acked, scmateReset, completed, failed));
        commandTable.setItems(FXCollections.observableArrayList(recent));
    }

    private String asText(Map<String, Object> row, String... keys) {
        if (row == null) return "";
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                String s = value.toString();
                if (!s.isBlank()) return s;
            }
        }
        return "";
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    /** 상세 상태 추출: SCMATE 상태 포함 */
    private String extractDetailedStatus(Map<String, Object> row) {
        String status = normalizeStatus(asText(row, "status"));
        String scmateStatus = asText(row, "scmateResetStatus");
        
        if ("PENDING".equals(scmateStatus)) {
            return "실행완료 (시점저장필요)";
        } else if ("COMPLETED".equals(scmateStatus)) {
            return "명령완료";
        }
        
        // 기본 상태
        return switch(status) {
            case "QUEUED" -> "전송중";
            case "DELIVERED" -> "전송중";
            case "ACKED" -> "실행중";
            case "COMPLETED" -> "완료";
            case "FAILED" -> "실패";
            default -> status;
        };
    }

    private String extractTarget(Map<String, Object> row) {
        String mac = asText(row, "macAddress");
        if (!mac.isBlank()) return mac;
        String ip = asText(row, "ipAddress");
        if (!ip.isBlank()) return ip;
        String groupId = asText(row, "groupId");
        if (!groupId.isBlank()) return "GROUP:" + groupId;
        return "-";
    }

    private String extractTime(Map<String, Object> row) {
        String completed = asText(row, "completedAt");
        if (!completed.isBlank()) return completed;
        String acked = asText(row, "ackedAt");
        if (!acked.isBlank()) return acked;
        String created = asText(row, "createdAt");
        if (!created.isBlank()) return created;
        return "-";
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
