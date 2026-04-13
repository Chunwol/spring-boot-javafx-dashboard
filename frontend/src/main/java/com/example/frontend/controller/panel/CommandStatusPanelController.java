package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.MouseButton;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
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
    @FXML
    private Label summaryLabel;
    @FXML
    private Label lastUpdatedLabel;
    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<String> statusFilterCombo;
    @FXML
    private ComboBox<String> sortCombo;
    @FXML
    private Button refreshButton;
    @FXML
    private Button clearButton;
    @FXML
    private StackPane commandTableSlot;
    private List<Map<String, Object>> latestCommands = List.of();
    private ScheduledExecutorService scheduler;

    public CommandStatusPanelController() {
    }

    /** 패널 VBox 반환 */
    public VBox getPanel() {
        if (rootPanel == null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/command-status-panel.fxml"));
                loader.setController(this);
                rootPanel = loader.load();
                initializePanel();
            } catch (Exception e) {
                throw new IllegalStateException("명령 상태 패널 FXML 로딩 실패", e);
            }
        }
        return rootPanel;
    }

    private void initializePanel() {
        if (refreshButton != null) {
            refreshButton.getStyleClass().add("btn-sm");
            refreshButton.setOnAction(e -> refresh());
        }

        if (searchField != null) {
            searchField.setPromptText("검색: 강의실, PC명, IP, MAC, 명령 종류");
            searchField.textProperty().addListener((obs, oldValue, newValue) -> applyTableView());
        }

        if (statusFilterCombo != null) {
            statusFilterCombo.setItems(FXCollections.observableArrayList(
                    "전체",
                    "진행중",
                    "완료",
                    "실패",
                    "검증중",
                    "시점저장필요"
            ));
            statusFilterCombo.setValue("전체");
            statusFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());
        }

        if (sortCombo != null) {
            sortCombo.setItems(FXCollections.observableArrayList(
                    "최신순",
                    "오래된순",
                    "강의실순",
                    "PC명순",
                    "상태순",
                    "명령종류순"
            ));
            sortCombo.setValue("최신순");
            sortCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());
        }

        if (clearButton != null) {
            clearButton.getStyleClass().add("btn-sm");
            clearButton.setOnAction(e -> {
                if (searchField != null) searchField.clear();
                if (statusFilterCombo != null) statusFilterCombo.setValue("전체");
                if (sortCombo != null) sortCombo.setValue("최신순");
            });
        }

        commandTable = createCommandTable();
        if (commandTableSlot != null) {
            commandTableSlot.getChildren().setAll(commandTable);
        }

        // 자동 폴링 (10초)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cmd-status-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, 10, TimeUnit.SECONDS);

        // 패널이 scene에서 제거될 때 scheduler 자동 종료
        rootPanel.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) shutdown();
        });
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

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createCommandTable() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/command-table.fxml"));
            TableView<Map<String, Object>> table = loader.load();
            var ns = loader.getNamespace();

            TableColumn<Map<String, Object>, String> timeCol = (TableColumn<Map<String, Object>, String>) ns.get("timeCol");
            TableColumn<Map<String, Object>, String> roomCol = (TableColumn<Map<String, Object>, String>) ns.get("roomCol");
            TableColumn<Map<String, Object>, String> pcNameCol = (TableColumn<Map<String, Object>, String>) ns.get("pcNameCol");
            TableColumn<Map<String, Object>, String> ipCol = (TableColumn<Map<String, Object>, String>) ns.get("ipCol");
            TableColumn<Map<String, Object>, String> macCol = (TableColumn<Map<String, Object>, String>) ns.get("macCol");
            TableColumn<Map<String, Object>, String> typeCol = (TableColumn<Map<String, Object>, String>) ns.get("typeCol");
            TableColumn<Map<String, Object>, String> transferCol = (TableColumn<Map<String, Object>, String>) ns.get("transferCol");
            TableColumn<Map<String, Object>, String> destinationCol = (TableColumn<Map<String, Object>, String>) ns.get("destinationCol");
            TableColumn<Map<String, Object>, String> modeCol = (TableColumn<Map<String, Object>, String>) ns.get("modeCol");
            TableColumn<Map<String, Object>, String> statusCol = (TableColumn<Map<String, Object>, String>) ns.get("statusCol");
            TableColumn<Map<String, Object>, String> exitCol = (TableColumn<Map<String, Object>, String>) ns.get("exitCol");
            TableColumn<Map<String, Object>, Void> detailCol = (TableColumn<Map<String, Object>, Void>) ns.get("detailCol");

            timeCol.setCellValueFactory(data -> new SimpleStringProperty(extractTime(data.getValue())));
            roomCol.setCellValueFactory(data -> new SimpleStringProperty(extractGroupName(data.getValue())));
            pcNameCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "pcName")));
            ipCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "ipAddress")));
            macCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "macAddress")));
            typeCol.setCellValueFactory(data -> new SimpleStringProperty(extractCommandDisplayType(data.getValue())));
            transferCol.setCellValueFactory(data -> new SimpleStringProperty(extractTransferDisplay(data.getValue())));
            destinationCol.setCellValueFactory(data -> new SimpleStringProperty(extractDestinationDisplay(data.getValue())));
            modeCol.setCellValueFactory(data -> new SimpleStringProperty(extractRunMode(data.getValue())));
            exitCol.setCellValueFactory(data -> new SimpleStringProperty(asText(data.getValue(), "exitCode")));

            statusCol.setCellValueFactory(data -> new SimpleStringProperty(extractDetailedStatus(data.getValue())));
            statusCol.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        getStyleClass().removeAll("status-warning", "status-completed", "status-delivered", "status-acked", "status-failed");
                    } else {
                        setText(item);
                        getStyleClass().removeAll("status-warning", "status-completed", "status-delivered", "status-acked", "status-failed");
                        if (item.contains("시점저장")) getStyleClass().add("status-warning");
                        else if (item.contains("COMPLETED")) getStyleClass().add("status-completed");
                        else if (item.contains("DELIVERED")) getStyleClass().add("status-delivered");
                        else if (item.contains("ACKED")) getStyleClass().add("status-acked");
                        else if (item.contains("FAILED")) getStyleClass().add("status-failed");
                    }
                }
            });

            detailCol.setCellFactory(col -> new TableCell<>() {
                private final Button button = new Button("보기");
                {
                    button.getStyleClass().addAll("btn-neutral", "btn-sm");
                    button.setOnAction(e -> {
                        Map<String, Object> row = getTableRow() == null ? null : getTableRow().getItem();
                        if (row != null) showCommandDetail(row);
                    });
                }
                @Override protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    setGraphic(empty || getTableRow() == null || getTableRow().getItem() == null ? null : button);
                }
            });

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
        } catch (Exception e) {
            throw new IllegalStateException("명령 테이블 FXML 로딩 실패", e);
        }
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
        if ("COLLECT_FILES".equalsIgnoreCase(type)) {
            return "FILE_RX";
        }
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

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/command-detail-dialog-content.fxml"));
            Node content = loader.load();
            var ns = loader.getNamespace();

            setLabelText(ns, "statusValue", extractDetailedStatus(row));
            setLabelText(ns, "runModeValue", extractRunMode(row));
            setLabelText(ns, "transferTargetValue", extractTransferDisplay(row));
            setLabelText(ns, "destinationValue", extractDestinationDisplay(row));
            setLabelText(ns, "exitCodeValue", asText(row, "exitCode"));
            setLabelText(ns, "verificationValue", verificationLabel(row));
            setLabelText(ns, "groupNameValue", extractGroupName(row));
            setLabelText(ns, "pcNameValue", asText(row, "pcName"));
            setLabelText(ns, "ipAddressValue", asText(row, "ipAddress"));
            String detailUrl = resolveDetailUrl(row);
            setLabelText(ns, "scriptUrlValue", detailUrl);
            setLabelText(ns, "payloadValue", prettyPayload(asText(row, "payloadJson")));

            setAreaText(ns, "stdoutArea", asText(row, "stdout"));
            setAreaText(ns, "stderrArea", asText(row, "stderr"));

            Object dlNode = ns.get("downloadButton");
            if (dlNode instanceof Button downloadButton) {
                boolean enabled = detailUrl != null && !detailUrl.isBlank() && !"-".equals(detailUrl);
                downloadButton.setDisable(!enabled);
                downloadButton.setOnAction(e -> handleDownloadFromDetail(detailUrl, suggestDownloadFilename(row)));
            }

            dialog.getDialogPane().setContent(content);
        } catch (Exception e) {
            e.printStackTrace();
            dialog.getDialogPane().setContent(new Label("다이얼로그 로드 실패: " + e.getMessage()));
        }
        dialog.showAndWait();
    }

    private String resolveDetailUrl(Map<String, Object> row) {
        String direct = asText(row, "scriptUrl");
        if (!direct.isBlank()) {
            return direct;
        }
        String stdout = asText(row, "stdout");
        if (stdout == null || stdout.isBlank()) {
            return "-";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(/api/v1/agent/uploads/\\d+/download)")
                .matcher(stdout);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "-";
    }

    private String suggestDownloadFilename(Map<String, Object> row) {
        try {
            JsonNode payload = parsePayload(row);
            if (payload != null && payload.has("outputName")) {
                String n = payload.get("outputName").asText("").trim();
                if (!n.isBlank()) {
                    return n.toLowerCase(Locale.ROOT).endsWith(".zip") ? n : n + ".zip";
                }
            }
        } catch (Exception ignored) {
        }
        String taskId = asText(row, "taskId");
        return (taskId == null || taskId.isBlank()) ? "collected.zip" : "collected-" + taskId + ".zip";
    }

    private void handleDownloadFromDetail(String downloadUrl, String suggestedName) {
        if (downloadUrl == null || downloadUrl.isBlank() || "-".equals(downloadUrl)) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("수집 파일 저장");
        chooser.setInitialFileName(suggestedName == null || suggestedName.isBlank() ? "collected.zip" : suggestedName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 파일", "*.zip"));
        File target = chooser.showSaveDialog(null);
        if (target == null) {
            return;
        }

        Thread t = new Thread(() -> {
            try {
                byte[] data = apiClient.getBytes(downloadUrl).join();
                Files.write(target.toPath(), data);
                Platform.runLater(() -> {
                    Alert ok = new Alert(Alert.AlertType.INFORMATION);
                    ok.setTitle("다운로드 완료");
                    ok.setHeaderText(null);
                    ok.setContentText("저장 완료: " + target.getAbsolutePath());
                    ok.showAndWait();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("다운로드 실패");
                    err.setHeaderText(null);
                    err.setContentText(ex.getMessage());
                    err.showAndWait();
                });
            }
        }, "cmd-detail-download");
        t.setDaemon(true);
        t.start();
    }

    private void setLabelText(Map<String, Object> ns, String fxId, String value) {
        Object node = ns.get(fxId);
        if (node instanceof Label label) {
            label.setText((value == null || value.isBlank()) ? "-" : value);
        }
    }

    private void setAreaText(Map<String, Object> ns, String fxId, String value) {
        Object node = ns.get(fxId);
        if (node instanceof TextArea area) {
            area.setText((value == null || value.isBlank()) ? "-" : value);
        }
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
