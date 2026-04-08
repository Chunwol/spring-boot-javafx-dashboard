package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.input.MouseButton;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ApiClient apiClient = ApiClient.getInstance();
    private final ObjectMapper objectMapper = ApiClient.getInstance().getObjectMapper();

    private VBox rootPanel;
    private TableView<Map<String, Object>> commandTable;
    private Label summaryLabel;
    private Label lastUpdatedLabel;
    private TextField searchField;
    private ComboBox<String> statusFilterCombo;
    private ComboBox<String> sortCombo;
    private List<Map<String, Object>> latestCommands = List.of();
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
        panel.setPrefSize(1100, 360);

        Label title = new Label("명령 상태");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setTextFill(Color.web("#0f172a"));

        Button refreshBtn = new Button("새로고침");
        refreshBtn.setStyle("-fx-background-color: #edf2f7; -fx-text-fill: #2d3748; -fx-font-weight: 700;");
        refreshBtn.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, spacer, refreshBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        summaryLabel = new Label("조회 중...");
        summaryLabel.setFont(Font.font("Segoe UI", 11));
        summaryLabel.setTextFill(Color.web("#334155"));

        lastUpdatedLabel = new Label("마지막 갱신: -");
        lastUpdatedLabel.setFont(Font.font("Segoe UI", 11));
        lastUpdatedLabel.setTextFill(Color.web("#64748b"));

        HBox controlBar = buildControlBar();

        commandTable = createCommandTable();
        VBox.setVgrow(commandTable, Priority.ALWAYS);

        panel.getChildren().addAll(header, summaryLabel, lastUpdatedLabel, controlBar, new Separator(), commandTable);

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

    private HBox buildControlBar() {
        searchField = new TextField();
        searchField.setPromptText("검색: 강의실, PC명, IP, MAC, 명령 종류");
        searchField.setPrefWidth(320);
        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyTableView());

        statusFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                "전체",
                "진행중",
                "완료",
                "실패",
                "검증중",
                "시점저장필요"
        ));
        statusFilterCombo.setValue("전체");
        statusFilterCombo.setPrefWidth(130);
        statusFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());

        sortCombo = new ComboBox<>(FXCollections.observableArrayList(
                "최신순",
                "오래된순",
                "강의실순",
                "PC명순",
                "상태순",
                "명령종류순"
        ));
        sortCombo.setValue("최신순");
        sortCombo.setPrefWidth(140);
        sortCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());

        Button clearButton = new Button("초기화");
        clearButton.setStyle("-fx-background-color: #edf2f7; -fx-text-fill: #1f2937; -fx-font-weight: 700;");
        clearButton.setOnAction(e -> {
            searchField.clear();
            statusFilterCombo.setValue("전체");
            sortCombo.setValue("최신순");
        });

        Label filterLabel = new Label("필터");
        filterLabel.setTextFill(Color.web("#475569"));
        Label sortLabel = new Label("정렬");
        sortLabel.setTextFill(Color.web("#475569"));

        HBox bar = new HBox(8, searchField, filterLabel, statusFilterCombo, sortLabel, sortCombo, clearButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** 서버에서 최근 명령 목록을 가져와 UI를 갱신한다. */
    public void refresh() {
        apiClient.getPagedList("/commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=200",
                new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(commands -> Platform.runLater(() -> updateUI(commands)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        summaryLabel.setText("서버 연결 오류");
                        lastUpdatedLabel.setText("마지막 갱신: 실패 " + LocalTime.now().format(TIME_FORMAT));
                    });
                    return null;
                });
    }

    private TableView<Map<String, Object>> createCommandTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPlaceholder(new Label("명령 로그가 없습니다."));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        TableColumn<Map<String, Object>, String> timeCol = new TableColumn<>("시간");
        timeCol.setPrefWidth(160);
        timeCol.setCellValueFactory(data -> new SimpleStringProperty(extractTime(data.getValue())));

        TableColumn<Map<String, Object>, String> roomCol = new TableColumn<>("강의실");
        roomCol.setPrefWidth(140);
        roomCol.setCellValueFactory(data -> new SimpleStringProperty(extractGroupName(data.getValue())));

        TableColumn<Map<String, Object>, String> pcNameCol = new TableColumn<>("PC명");
        pcNameCol.setPrefWidth(170);
        pcNameCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "pcName")));

        TableColumn<Map<String, Object>, String> ipCol = new TableColumn<>("IP");
        ipCol.setPrefWidth(140);
        ipCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "ipAddress")));

        TableColumn<Map<String, Object>, String> macCol = new TableColumn<>("MAC");
        macCol.setPrefWidth(170);
        macCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "macAddress")));

        TableColumn<Map<String, Object>, String> typeCol = new TableColumn<>("종류");
        typeCol.setPrefWidth(90);
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(extractCommandDisplayType(data.getValue())));

        TableColumn<Map<String, Object>, String> transferCol = new TableColumn<>("전송대상");
        transferCol.setPrefWidth(180);
        transferCol.setCellValueFactory(data -> new SimpleStringProperty(extractTransferDisplay(data.getValue())));

        TableColumn<Map<String, Object>, String> destinationCol = new TableColumn<>("대상경로");
        destinationCol.setPrefWidth(190);
        destinationCol.setCellValueFactory(data -> new SimpleStringProperty(extractDestinationDisplay(data.getValue())));

        TableColumn<Map<String, Object>, String> modeCol = new TableColumn<>("실행옵션");
        modeCol.setPrefWidth(220);
        modeCol.setCellValueFactory(data -> new SimpleStringProperty(extractRunMode(data.getValue())));

        // 상태 컬럼 - SCMATE 상태 포함
        TableColumn<Map<String, Object>, String> statusCol = new TableColumn<>("상태");
        statusCol.setPrefWidth(130);
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
        exitCol.setPrefWidth(80);
        exitCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "exitCode")));

        TableColumn<Map<String, Object>, Void> detailCol = new TableColumn<>("결과");
        detailCol.setPrefWidth(100);
        detailCol.setCellFactory(col -> new TableCell<>() {
            private final Button button = new Button("보기");
            {
                button.setStyle("-fx-background-color: #edf2f7; -fx-text-fill: #1f2937; -fx-font-weight: 700;");
                button.setOnAction(e -> {
                    Map<String, Object> row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        showCommandDetail(row);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || getTableRow() == null || getTableRow().getItem() == null ? null : button);
            }
        });

        table.getColumns().addAll(timeCol, roomCol, pcNameCol, ipCol, macCol, typeCol, transferCol, destinationCol, modeCol, statusCol, exitCol, detailCol);
        table.setRowFactory(tv -> {
            TableRow<Map<String, Object>> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    showCommandDetail(row.getItem());
                }
            });
            return row;
        });
        return table;
    }

    private void updateUI(List<Map<String, Object>> commands) {
        List<Map<String, Object>> recent = commands == null ? List.of() : commands.stream().limit(120).toList();
        latestCommands = recent;

        long queued = recent.stream().filter(c -> "QUEUED".equals(normalizeStatus(asText(c, "status")))).count();
        long delivered = recent.stream().filter(c -> "DELIVERED".equals(normalizeStatus(asText(c, "status")))).count();
        long acked = recent.stream().filter(c -> "ACKED".equals(normalizeStatus(asText(c, "status")))).count();
        long completed = recent.stream().filter(c -> "COMPLETED".equals(normalizeStatus(asText(c, "status")))).count();
        long failed = recent.stream().filter(c -> "FAILED".equals(normalizeStatus(asText(c, "status")))).count();
        long verifying = recent.stream()
            .filter(c -> Set.of("PENDING_OFFLINE", "PENDING_ONLINE").contains(asText(c, "verificationStatus")))
            .count();
        
        // SCMATE 상태 카운팅
        long scmateReset = recent.stream()
            .filter(c -> "PENDING".equals(asText(c, "scmateResetStatus")))
            .count();
        long activeTotal = queued + delivered + acked + scmateReset + verifying;

        if (recent.isEmpty()) {
            summaryLabel.setText("대기 중인 명령 없음");
            commandTable.setItems(FXCollections.observableArrayList());
            lastUpdatedLabel.setText("마지막 갱신: " + LocalTime.now().format(TIME_FORMAT));
            return;
        }

        summaryLabel.setText(String.format(
            "최근 120건: 진행중 %d (Q:%d D:%d A:%d 검증:%d) / 시점저장 %d / 완료 %d / 실패 %d",
            activeTotal, queued, delivered, acked, verifying, scmateReset, completed, failed));
        applyTableView();
        lastUpdatedLabel.setText("마지막 갱신: " + LocalTime.now().format(TIME_FORMAT));
    }

    private void applyTableView() {
        List<Map<String, Object>> filtered = latestCommands.stream()
                .filter(this::matchesSearch)
                .filter(this::matchesStatusFilter)
                .sorted(commandComparator())
                .toList();
        commandTable.setItems(FXCollections.observableArrayList(filtered));
    }

    private boolean matchesSearch(Map<String, Object> row) {
        String query = searchField == null || searchField.getText() == null
                ? ""
                : searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return true;
        }

        return String.join(" ",
                        asText(row, "groupName", "groupId"),
                        asText(row, "pcName"),
                        asText(row, "ipAddress"),
                        asText(row, "macAddress"),
                        extractCommandDisplayType(row),
                        extractDetailedStatus(row),
                        extractRunMode(row),
                        extractTransferDisplay(row),
                        extractDestinationDisplay(row),
                        asText(row, "scriptUrl"))
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    private boolean matchesStatusFilter(Map<String, Object> row) {
        String filter = statusFilterCombo == null || statusFilterCombo.getValue() == null
                ? "전체"
                : statusFilterCombo.getValue();
        String status = normalizeStatus(asText(row, "status"));
        String detailed = extractDetailedStatus(row);
        return switch (filter) {
            case "진행중" -> Set.of("QUEUED", "DELIVERED", "ACKED").contains(status);
            case "완료" -> "COMPLETED".equals(status) && !detailed.contains("확인중") && !detailed.contains("시점저장");
            case "실패" -> "FAILED".equals(status);
            case "검증중" -> Set.of("PENDING_OFFLINE", "PENDING_ONLINE").contains(asText(row, "verificationStatus"));
            case "시점저장필요" -> "PENDING".equals(asText(row, "scmateResetStatus"));
            default -> true;
        };
    }

    private Comparator<Map<String, Object>> commandComparator() {
        String sort = sortCombo == null || sortCombo.getValue() == null ? "최신순" : sortCombo.getValue();
        Comparator<Map<String, Object>> comparator = switch (sort) {
            case "오래된순" -> Comparator.comparing((Map<String, Object> row) -> extractSortTime(row));
            case "강의실순" -> Comparator.comparing(
                    (Map<String, Object> row) -> extractGroupName(row),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing((Map<String, Object> row) -> extractSortTime(row), Comparator.reverseOrder());
            case "PC명순" -> Comparator.comparing(
                    (Map<String, Object> row) -> asText(row, "pcName"),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing((Map<String, Object> row) -> extractSortTime(row), Comparator.reverseOrder());
            case "상태순" -> Comparator.comparingInt((Map<String, Object> row) -> statusRank(row))
                .thenComparing((Map<String, Object> row) -> extractSortTime(row), Comparator.reverseOrder());
            case "명령종류순" -> Comparator.comparing(
                    (Map<String, Object> row) -> asText(row, "commandType", "type"),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing((Map<String, Object> row) -> extractSortTime(row), Comparator.reverseOrder());
            default -> Comparator.comparing(
                (Map<String, Object> row) -> extractSortTime(row),
                Comparator.reverseOrder());
        };
        return comparator.thenComparing(row -> asText(row, "taskId"), Comparator.reverseOrder());
    }

    private LocalDateTime extractSortTime(Map<String, Object> row) {
        return bestEffortParseDateTime(
                asText(row, "completedAt"),
                asText(row, "ackedAt"),
                asText(row, "deliveredAt"),
                asText(row, "createdAt")
        );
    }

    private LocalDateTime bestEffortParseDateTime(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().replace(" ", "T");
            try {
                return LocalDateTime.parse(normalized);
            } catch (Exception ignored) {
            }
        }
        return LocalDateTime.MIN;
    }

    private int statusRank(Map<String, Object> row) {
        String verificationStatus = asText(row, "verificationStatus");
        if (Set.of("PENDING_OFFLINE", "PENDING_ONLINE").contains(verificationStatus)) {
            return 0;
        }
        if ("PENDING".equals(asText(row, "scmateResetStatus"))) {
            return 1;
        }
        return switch (normalizeStatus(asText(row, "status"))) {
            case "ACKED" -> 2;
            case "DELIVERED" -> 3;
            case "QUEUED" -> 4;
            case "FAILED" -> 5;
            case "COMPLETED" -> 6;
            default -> 9;
        };
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
        String verificationStatus = asText(row, "verificationStatus");
        String type = asText(row, "commandType", "type");

        if ("PENDING_OFFLINE".equals(verificationStatus)) {
            return "REBOOT".equalsIgnoreCase(type) ? "재부팅 종료 확인중" : "종료 확인중";
        } else if ("PENDING_ONLINE".equals(verificationStatus)) {
            return "재부팅 재접속 확인중";
        } else if ("CONFIRMED".equals(verificationStatus)) {
            return "REBOOT".equalsIgnoreCase(type) ? "재부팅 확인완료" : "종료 확인완료";
        }
        
        if ("PENDING".equals(scmateStatus)) {
            return "실행완료 (시점저장필요)";
        } else if ("COMPLETED".equals(scmateStatus)) {
            return "명령완료";
        }
        
        // 기본 상태
        return switch(status) {
            case "QUEUED" -> "대기중";
            case "DELIVERED" -> "전송됨";
            case "ACKED" -> "실행중";
            case "COMPLETED" -> "완료";
            case "FAILED" -> "실패";
            default -> status;
        };
    }

    private String extractGroupName(Map<String, Object> row) {
        String groupName = asText(row, "groupName");
        if (!groupName.isBlank()) return groupName;
        String groupId = asText(row, "groupId");
        if (!groupId.isBlank()) return "그룹 " + groupId;
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

    private String extractRunMode(Map<String, Object> row) {
        String type = asText(row, "commandType", "type");
        if (!"RUN_PS1".equalsIgnoreCase(type)) {
            return "-";
        }
        JsonNode payload = parsePayload(row);
        if (payload == null) {
            return "대기 실행 · 창 숨김";
        }

        boolean transferMode = payload.has("transferMode") && payload.get("transferMode").asBoolean(false);
        String runMode = payload.has("runMode") ? payload.get("runMode").asText("") : "";
        String windowMode = payload.has("windowMode") ? payload.get("windowMode").asText("") : "";
        String runModeLabel = "BACKGROUND".equalsIgnoreCase(runMode) ? "즉시 실행" : "대기 실행";
        String windowModeLabel = "VISIBLE".equalsIgnoreCase(windowMode) ? "창 표시" : "창 숨김";
        if (transferMode) {
            String runAfter = payload.has("runAfterTransfer") && payload.get("runAfterTransfer").asBoolean(false)
                    ? "전송후 실행"
                    : "전송만";
            return runAfter + " · " + runModeLabel + " · " + windowModeLabel;
        }
        return runModeLabel + " · " + windowModeLabel;
    }

    private JsonNode parsePayload(Map<String, Object> row) {
        try {
            String payloadJson = asText(row, "payloadJson");
            if (payloadJson.isBlank()) {
                return null;
            }
            return objectMapper.readTree(payloadJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractCommandDisplayType(Map<String, Object> row) {
        String type = asText(row, "commandType", "type");
        if (!"RUN_PS1".equalsIgnoreCase(type)) {
            return type;
        }
        JsonNode payload = parsePayload(row);
        if (payload != null && payload.has("transferMode") && payload.get("transferMode").asBoolean(false)) {
            return "FILE_TX";
        }
        return "RUN_PS1";
    }

    private String extractTransferDisplay(Map<String, Object> row) {
        String type = asText(row, "commandType", "type");
        if (!"RUN_PS1".equalsIgnoreCase(type)) {
            return "-";
        }
        JsonNode payload = parsePayload(row);
        if (payload == null || !payload.has("transferMode") || !payload.get("transferMode").asBoolean(false)) {
            return "-";
        }
        String sourceType = payload.has("sourceType") ? payload.get("sourceType").asText("file") : "file";
        String displayName = payload.has("displayName") ? payload.get("displayName").asText("-") : "-";
        return ("folder".equalsIgnoreCase(sourceType) ? "폴더" : "파일") + " · " + displayName;
    }

    private String extractDestinationDisplay(Map<String, Object> row) {
        String type = asText(row, "commandType", "type");
        if (!"RUN_PS1".equalsIgnoreCase(type)) {
            return "-";
        }
        JsonNode payload = parsePayload(row);
        if (payload == null || !payload.has("transferMode") || !payload.get("transferMode").asBoolean(false)) {
            return "-";
        }
        String mode = payload.has("destinationMode") ? payload.get("destinationMode").asText("DOWNLOADS") : "DOWNLOADS";
        if ("DESKTOP".equalsIgnoreCase(mode)) {
            return "바탕화면";
        }
        if ("CUSTOM".equalsIgnoreCase(mode)) {
            String custom = payload.has("destinationPath") ? payload.get("destinationPath").asText("") : "";
            return custom.isBlank() ? "사용자 지정(미입력)" : custom;
        }
        return "다운로드 폴더";
    }

    private void showCommandDetail(Map<String, Object> row) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("명령 결과 상세");
        dialog.setHeaderText(asText(row, "commandType", "type") + " - " + asText(row, "pcName", "macAddress"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setPrefWidth(760);
        content.setPrefHeight(560);

        content.getChildren().addAll(
            buildMetaRow("상태", extractDetailedStatus(row)),
            buildMetaRow("실행방식", extractRunMode(row)),
            buildMetaRow("전송대상", extractTransferDisplay(row)),
            buildMetaRow("대상경로", extractDestinationDisplay(row)),
            buildMetaRow("종료코드", asText(row, "exitCode")),
            buildMetaRow("검증상태", verificationLabel(row)),
            buildMetaRow("강의실", extractGroupName(row)),
            buildMetaRow("PC", asText(row, "pcName")),
            buildMetaRow("IP", asText(row, "ipAddress")),
            buildMetaRow("스크립트", asText(row, "scriptUrl")),
            buildMetaRow("Payload", prettyPayload(asText(row, "payloadJson")))
        );

        content.getChildren().add(buildTextSection("stdout", asText(row, "stdout")));
        content.getChildren().add(buildTextSection("stderr", asText(row, "stderr")));

        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private Node buildMetaRow(String label, String value) {
        VBox box = new VBox(4);
        Label title = new Label(label);
        title.setStyle("-fx-font-size: 11; -fx-font-weight: 700; -fx-text-fill: #475569;");
        Label body = new Label((value == null || value.isBlank()) ? "-" : value);
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 12; -fx-text-fill: #0f172a;");
        box.getChildren().addAll(title, body);
        return box;
    }

    private Node buildTextSection(String title, String value) {
        VBox box = new VBox(4);
        Label label = new Label(title);
        label.setStyle("-fx-font-size: 11; -fx-font-weight: 700; -fx-text-fill: #475569;");
        TextArea area = new TextArea(value == null || value.isBlank() ? "-" : value);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(6);
        box.getChildren().addAll(label, area);
        return box;
    }

    private String prettyPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "-";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(payloadJson));
        } catch (Exception e) {
            return payloadJson;
        }
    }

    private String verificationLabel(Map<String, Object> row) {
        String verificationStatus = asText(row, "verificationStatus");
        if (verificationStatus == null || verificationStatus.isBlank()) {
            return "-";
        }
        return verificationStatus
            + " / 시작=" + asText(row, "verificationStartedAt")
            + " / 마감=" + asText(row, "verificationDeadlineAt")
            + " / 오프라인감지=" + asText(row, "verificationObservedOfflineAt")
            + " / 완료=" + asText(row, "verificationCompletedAt");
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
