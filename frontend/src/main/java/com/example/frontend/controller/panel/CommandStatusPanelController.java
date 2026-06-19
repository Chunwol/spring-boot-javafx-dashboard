package com.example.frontend.controller.panel;

import com.example.frontend.service.ApiClient;
import com.example.frontend.util.ApiUrlUtil;
import com.example.frontend.util.Dialogs;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
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

    private static final DateTimeFormatter UPDATED_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final Logger log = LoggerFactory.getLogger(CommandStatusPanelController.class);
    private static final String FETCH_STATUS_QUERY = "QUEUED,DELIVERED,ACKED,COMPLETED,FAILED";
    private static final String ALL_FILTER = "전체";

    private enum CommandStatus {
        QUEUED("QUEUED", "대기중"),
        DELIVERED("DELIVERED", "전송됨"),
        ACKED("ACKED", "실행중"),
        COMPLETED("COMPLETED", "완료"),
        FAILED("FAILED", "실패"),
        UNKNOWN("", "");

        private final String code;
        private final String label;

        CommandStatus(String code, String label) {
            this.code = code;
            this.label = label;
        }

        static CommandStatus fromRaw(String rawStatus) {
            if (rawStatus == null || rawStatus.isBlank()) {
                return UNKNOWN;
            }
            String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
            for (CommandStatus status : values()) {
                if (status.code.equals(normalized)) {
                    return status;
                }
            }
            return UNKNOWN;
        }

        boolean isActive() {
            return this == QUEUED || this == DELIVERED || this == ACKED;
        }

        String label() {
            return label;
        }
    }

    private final ApiClient apiClient = ApiClient.getInstance();
    private final ObjectMapper objectMapper = ApiClient.getInstance().getObjectMapper();

    private Region rootPanel;
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
    private Button typeFilterBtn;           // 체크박스 팝업 트리거
    /** 체크 해제 = 숨김. 없으면 전체 표시. */
    private final Set<String> hiddenTypes = new LinkedHashSet<>();
    /** 데이터에서 수집한 전체 종류 목록. */
    private final Set<String> knownTypes  = new LinkedHashSet<>();
    @FXML
    private ComboBox<String> roomFilterCombo;
    @FXML
    private ComboBox<String> sortCombo;
    @FXML
    private Button refreshButton;
    @FXML
    private Button clearButton;
    @FXML
    private StackPane commandTableSlot;

    // 우측 슬라이드 상세 패널
    @FXML private VBox detailPanel;
    @FXML private Label detailTitleLabel;
    @FXML private Button detailCloseBtn;
    @FXML private HBox progressStepsBox;
    @FXML private Label detailStatusLine;
    @FXML private VBox detailMetaBox;
    @FXML private TextArea detailStdout;
    @FXML private TextArea detailStderr;
    @FXML private Button detailDownloadBtn;

    /** 진행 단계 라벨 (대기중 → 전송됨 → 실행중 → 완료). */
    private static final String[] STEP_LABELS = {"대기중", "전송됨", "실행중", "완료"};
    private static final double DETAIL_PANEL_WIDTH = 480.0;
    /** 상세 패널에 표시 중인 명령 키 (폴링 갱신 시 추적용). */
    private String selectedKey;
    /** 메타 행의 값 라벨 — 선택/폴링마다 값만 갱신 (재생성 안 함). */
    private final Map<String, Label> metaValueLabels = new LinkedHashMap<>();
    private javafx.animation.Timeline panelAnim;

    private List<Map<String, Object>> latestCommands = List.of();
    private ScheduledExecutorService scheduler;

    public CommandStatusPanelController() {
    }

    /** 패널 루트 반환 (좌측 테이블 + 우측 상세 패널 HBox) */
    public Region getPanel() {
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
                    ALL_FILTER,
                    "진행중",
                    "완료",
                    "실패",
                    "검증중",
                    "시점저장필요"
            ));
            statusFilterCombo.setValue(ALL_FILTER);
            statusFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());
        }

        if (typeFilterBtn != null) {
            typeFilterBtn.setOnAction(e -> showTypeFilterPopup());
        }

        if (roomFilterCombo != null) {
            roomFilterCombo.setItems(FXCollections.observableArrayList(ALL_FILTER));
            roomFilterCombo.setValue(ALL_FILTER);
            roomFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyTableView());
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
                if (statusFilterCombo != null) statusFilterCombo.setValue(ALL_FILTER);
                hiddenTypes.clear();
                updateTypeFilterBtnLabel();
                if (roomFilterCombo != null) roomFilterCombo.setValue(ALL_FILTER);
                if (sortCombo != null) sortCombo.setValue("최신순");
                applyTableView();
            });
        }

        commandTable = createCommandTable();
        if (commandTableSlot != null) {
            commandTableSlot.getChildren().setAll(commandTable);
        }

        // 우측 상세 패널 초기화
        if (detailCloseBtn != null) detailCloseBtn.setOnAction(e -> hideDetail());
        buildDetailMeta();

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
        apiClient.getPagedList("/commands?status=" + FETCH_STATUS_QUERY + "&size=500",
                new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(commands -> Platform.runLater(() -> updateUI(commands)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        summaryLabel.setText("서버 연결 오류");
                        lastUpdatedLabel.setText(LocalDateTime.now().format(UPDATED_FORMAT) + " 갱신 실패");
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

            // 잘릴 수 있는 동적 컬럼은 셀에 마우스를 올리면 전체 값을 툴팁으로 보여준다
            applyTooltipCellFactory(pcNameCol);
            applyTooltipCellFactory(ipCol);
            applyTooltipCellFactory(macCol);
            applyTooltipCellFactory(transferCol);
            applyTooltipCellFactory(destinationCol);
            applyTooltipCellFactory(modeCol);

            statusCol.setCellValueFactory(data -> new SimpleStringProperty(extractDetailedStatus(data.getValue())));
            statusCol.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    Map<String, Object> row = getTableRow() == null ? null : getTableRow().getItem();
                    if (empty || item == null) {
                        setText(null);
                        getStyleClass().removeAll("status-warning", "status-completed", "status-delivered", "status-acked", "status-failed");
                    } else {
                        setText(item);
                        getStyleClass().removeAll("status-warning", "status-completed", "status-delivered", "status-acked", "status-failed");
                        if (item.contains("시점저장")) getStyleClass().add("status-warning");
                        else if (row != null) {
                            switch (resolveCommandStatus(row)) {
                                case COMPLETED -> getStyleClass().add("status-completed");
                                case DELIVERED -> getStyleClass().add("status-delivered");
                                case ACKED -> getStyleClass().add("status-acked");
                                case FAILED -> getStyleClass().add("status-failed");
                                default -> {
                                    // Keep default text style for queued/unknown states.
                                }
                            }
                        }
                    }
                }
            });

            // 핵심 컬럼(시간/강의실/PC/종류/상태)만 기본 표시.
            // 나머지(IP/MAC/전송대상/대상경로/실행옵션/종료코드)는 … 메뉴로 켤 수 있음.
            // 상세보기 버튼 컬럼 제거 — 행 클릭으로 우측 패널에서 상세 표시됨.
            for (TableColumn<Map<String, Object>, ?> hidden :
                    List.of(ipCol, macCol, transferCol, destinationCol, modeCol, exitCol)) {
                hidden.setVisible(false);
                // 컬럼 가시성 변경 시 → 나머지 컬럼이 공간을 자동으로 나눠 갖도록 리사이즈
                hidden.visibleProperty().addListener((obs, ov, nv) ->
                        Platform.runLater(() -> {
                            table.refresh();
                            table.layout();
                        }));
            }
            table.setTableMenuButtonVisible(true);

            table.setRowFactory(tv -> {
                TableRow<Map<String, Object>> row = new TableRow<>();
                row.setOnMouseClicked(event -> {
                    if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY) {
                        showDetailInPanel(row.getItem());
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
        List<Map<String, Object>> recent = commands == null ? List.of() : commands.stream().limit(500).toList();
        latestCommands = recent;
        refreshFilterCombos(recent);

        long queued = recent.stream().filter(c -> resolveCommandStatus(c) == CommandStatus.QUEUED).count();
        long delivered = recent.stream().filter(c -> resolveCommandStatus(c) == CommandStatus.DELIVERED).count();
        long acked = recent.stream().filter(c -> resolveCommandStatus(c) == CommandStatus.ACKED).count();
        long completed = recent.stream().filter(c -> resolveCommandStatus(c) == CommandStatus.COMPLETED).count();
        long failed = recent.stream().filter(c -> resolveCommandStatus(c) == CommandStatus.FAILED).count();
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
            lastUpdatedLabel.setText(LocalDateTime.now().format(UPDATED_FORMAT) + " 갱신됨");
            return;
        }

        summaryLabel.setText(String.format(
            "최근 120건: 진행중 %d (Q:%d D:%d A:%d 검증:%d) / 시점저장 %d / 완료 %d / 실패 %d",
            activeTotal, queued, delivered, acked, verifying, scmateReset, completed, failed));
        applyTableView();
        lastUpdatedLabel.setText(LocalDateTime.now().format(UPDATED_FORMAT) + " 갱신됨");

        // 선택된 명령이 있으면 최신 상태로 패널 갱신 → 진행 단계가 자동으로 진행
        if (selectedKey != null) {
            recent.stream().filter(r -> selectedKey.equals(keyOf(r))).findFirst()
                    .ifPresent(this::renderDetail);
        }
    }

    private void applyTableView() {
        List<Map<String, Object>> filtered = latestCommands.stream()
                .filter(this::matchesSearch)
                .filter(this::matchesStatusFilter)
                .filter(this::matchesTypeFilter)
                .filter(this::matchesRoomFilter)
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
        String filter = selectedFilterValue(statusFilterCombo);
        CommandStatus status = resolveCommandStatus(row);
        String detailed = extractDetailedStatus(row);
        return switch (filter) {
            case "진행중" -> status.isActive();
            case "완료" -> status == CommandStatus.COMPLETED && !detailed.contains("확인중") && !detailed.contains("시점저장");
            case "실패" -> status == CommandStatus.FAILED;
            case "검증중" -> Set.of("PENDING_OFFLINE", "PENDING_ONLINE").contains(asText(row, "verificationStatus"));
            case "시점저장필요" -> "PENDING".equals(asText(row, "scmateResetStatus"));
            default -> true;
        };
    }

    private boolean matchesTypeFilter(Map<String, Object> row) {
        if (hiddenTypes.isEmpty()) return true;
        return !hiddenTypes.contains(extractCommandDisplayType(row));
    }

    private boolean matchesRoomFilter(Map<String, Object> row) {
        String filter = selectedFilterValue(roomFilterCombo);
        if (ALL_FILTER.equals(filter)) {
            return true;
        }
        return filter.equals(extractGroupName(row));
    }

    private String selectedFilterValue(ComboBox<String> combo) {
        if (combo == null || combo.getValue() == null || combo.getValue().isBlank()) {
            return ALL_FILTER;
        }
        return combo.getValue();
    }

    private void refreshFilterCombos(List<Map<String, Object>> recent) {
        // 종류 목록 갱신 (체크박스 팝업용)
        Set<String> fresh = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        recent.forEach(r -> {
            String t = extractCommandDisplayType(r);
            if (t != null && !t.isBlank() && !"-".equals(t)) fresh.add(t);
        });
        knownTypes.clear();
        knownTypes.addAll(fresh);
        updateTypeFilterBtnLabel();

        refreshFilterCombo(roomFilterCombo, recent.stream()
                .map(this::extractGroupName)
                .toList());
    }

    private void refreshFilterCombo(ComboBox<String> combo, List<String> values) {
        if (combo == null) {
            return;
        }

        String current = selectedFilterValue(combo);
        Set<String> uniqueValues = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (normalized.isBlank() || "-".equals(normalized)) {
                continue;
            }
            uniqueValues.add(normalized);
        }

        List<String> items = new ArrayList<>();
        items.add(ALL_FILTER);
        items.addAll(uniqueValues);
        combo.setItems(FXCollections.observableArrayList(items));
        combo.setValue(items.contains(current) ? current : ALL_FILTER);
    }

    /** 종류 필터 버튼 레이블 갱신 (숨긴 개수 표시). */
    private void updateTypeFilterBtnLabel() {
        if (typeFilterBtn == null) return;
        if (hiddenTypes.isEmpty()) {
            typeFilterBtn.setText("종류 ▾");
        } else {
            typeFilterBtn.setText("종류 (-" + hiddenTypes.size() + ") ▾");
        }
    }

    /**
     * 종류 체크박스 팝업.
     * 체크 = 표시, 해제 = 숨김. LIST_FILES 같이 잡음이 많은 항목을 쉽게 끌 수 있다.
     */
    private void showTypeFilterPopup() {
        if (typeFilterBtn == null) return;

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);

        VBox box = new VBox(4);
        // 인라인 스타일로 AtlantaFX 테마 덮어쓰기 — 흰 배경 강제
        box.setStyle(
            "-fx-background-color: #ffffff !important; " +
            "-fx-background: #ffffff; " +
            "-fx-border-color: #e2e8f0; " +
            "-fx-border-radius: 8; -fx-background-radius: 8; " +
            "-fx-padding: 10; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.14), 10, 0, 0, 4);");
        box.setMinWidth(180);

        // 데이터가 아직 없으면 안내
        if (knownTypes.isEmpty()) {
            box.getChildren().add(new javafx.scene.control.Label("데이터 조회 후 표시됩니다."));
        } else {
            for (String type : knownTypes) {
                javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(type);
                cb.setSelected(!hiddenTypes.contains(type));
                cb.setStyle("-fx-font-size: 13px; -fx-padding: 2 4;");
                cb.selectedProperty().addListener((obs, ov, nv) -> {
                    if (nv) hiddenTypes.remove(type);
                    else    hiddenTypes.add(type);
                    updateTypeFilterBtnLabel();
                    applyTableView();
                });
                box.getChildren().add(cb);
            }
        }

        popup.getContent().add(box);
        javafx.geometry.Bounds b = typeFilterBtn.localToScreen(typeFilterBtn.getBoundsInLocal());
        popup.show(typeFilterBtn, b.getMinX(), b.getMaxY() + 2);
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
            } catch (java.time.format.DateTimeParseException ex) {
                // Try next candidate timestamp.
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
        return switch (resolveCommandStatus(row)) {
            case ACKED -> 2;
            case DELIVERED -> 3;
            case QUEUED -> 4;
            case FAILED -> 5;
            case COMPLETED -> 6;
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

    private CommandStatus resolveCommandStatus(Map<String, Object> row) {
        return CommandStatus.fromRaw(asText(row, "status"));
    }

    /** 상세 상태 추출: SCMATE 상태 포함 */
    private String extractDetailedStatus(Map<String, Object> row) {
        String rawStatus = normalizeStatus(asText(row, "status"));
        CommandStatus commandStatus = resolveCommandStatus(row);
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
        
        // 기본 상태 (FAILED는 stderr 첫 줄을 함께 표시)
        return switch(commandStatus) {
            case QUEUED, DELIVERED, ACKED, COMPLETED -> commandStatus.label();
            case FAILED -> {
                String err = asText(row, "stderr").trim();
                if (!err.isBlank()) {
                    int nl = err.indexOf('\n');
                    String firstLine = nl > 0 ? err.substring(0, nl).trim() : err;
                    if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "…";
                    yield "실패 — " + firstLine;
                }
                yield commandStatus.label();
            }
            default -> rawStatus.isBlank() ? "-" : rawStatus;
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

    /** 좁은 컬럼에서 잘리는 텍스트를 마우스 호버 시 전체 값 툴팁으로 노출한다. */
    private void applyTooltipCellFactory(TableColumn<Map<String, Object>, String> column) {
        column.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });
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
        } catch (Exception ex) {
            log.debug("Failed to parse payloadJson", ex);
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
        if ("COLLECT_FILES".equalsIgnoreCase(type)) {
            JsonNode payload = parsePayload(row);
            if (payload == null) {
                return "수집";
            }
            int pathCount = payload.has("paths") && payload.get("paths").isArray()
                    ? payload.get("paths").size() : (payload.has("path") ? 1 : 0);
            String outputName = payload.has("outputName") ? payload.get("outputName").asText("") : "";
            String base = pathCount > 0 ? ("경로 " + pathCount + "개 수집") : "수집";
            return outputName == null || outputName.isBlank() ? base : (base + " · " + outputName);
        }
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
        if ("COLLECT_FILES".equalsIgnoreCase(type)) {
            return "백엔드 업로드 -> 프론트 다운로드";
        }
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

    // ─────────────────────────────────────────────────────────────────────
    // 우측 상세 패널 (팝업 대체)
    // ─────────────────────────────────────────────────────────────────────

    /** 명령 고유 키 — 폴링 갱신 후에도 같은 명령을 추적. id 우선, 없으면 mac+생성시각. */
    private String keyOf(Map<String, Object> row) {
        String id = asText(row, "id", "commandId");
        if (!id.isBlank()) return id;
        return asText(row, "macAddress") + "|" + asText(row, "createdAt");
    }

    /** 메타 행(키-값)을 1회 생성하고 값 라벨을 metaValueLabels에 보관. */
    private void buildDetailMeta() {
        if (detailMetaBox == null) return;
        String[] keys = {"강의실", "PC", "IP", "오류 내용", "실행방식", "전송대상", "대상경로", "종료코드", "검증상태", "스크립트"};
        for (String key : keys) {
            Label k = new Label(key);
            k.getStyleClass().add("meta-key");
            k.setMinWidth(78);
            k.setPrefWidth(78);
            Label v = new Label("-");
            v.getStyleClass().add("meta-value");
            v.setWrapText(true);
            HBox.setHgrow(v, Priority.ALWAYS);
            metaValueLabels.put(key, v);
            HBox row = new HBox(10, k, v);
            row.getStyleClass().add("cmd-meta-row");
            row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
            detailMetaBox.getChildren().add(row);
        }
    }

    /** 행 클릭/버튼 → 우측 패널에 표시하고 슬라이드 인. */
    private void showDetailInPanel(Map<String, Object> row) {
        if (row == null) return;
        String newKey = keyOf(row);
        // 이미 다른 항목이 열려 있으면, 이전 패널을 닫았다가 새 항목으로 다시 연다.
        if (detailPanel.isVisible() && selectedKey != null && !selectedKey.equals(newKey)) {
            selectedKey = newKey;
            slideOutThenIn(() -> renderDetail(row));
            return;
        }
        selectedKey = newKey;
        renderDetail(row);
        slideIn();
    }

    /** 현재 패널을 빠르게 닫은 뒤 내용 갱신 + 다시 연다 (다른 항목 선택 시 전환 효과). */
    private void slideOutThenIn(Runnable contentUpdate) {
        if (panelAnim != null) panelAnim.stop();
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
        panelAnim = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(130),
                new javafx.animation.KeyValue(detailPanel.prefWidthProperty(), 0),
                new javafx.animation.KeyValue(detailPanel.minWidthProperty(), 0)));
        panelAnim.setOnFinished(e -> {
            contentUpdate.run();
            slideIn();
        });
        panelAnim.play();
    }

    /** 패널 내용 갱신 (선택 시 + 폴링 시 호출). */
    private void renderDetail(Map<String, Object> row) {
        detailTitleLabel.setText(asText(row, "commandType", "type") + " · " + asText(row, "pcName", "macAddress"));
        detailStatusLine.setText(extractDetailedStatus(row));
        renderProgressSteps(row);

        metaValueLabels.get("강의실").setText(orDash(extractGroupName(row)));
        metaValueLabels.get("PC").setText(orDash(asText(row, "pcName")));
        metaValueLabels.get("IP").setText(orDash(asText(row, "ipAddress")));
        String errDetail = resolveCommandStatus(row) == CommandStatus.FAILED ? asText(row, "stderr") : "";
        metaValueLabels.get("오류 내용").setText(orDash(errDetail));
        metaValueLabels.get("실행방식").setText(orDash(extractRunMode(row)));
        metaValueLabels.get("전송대상").setText(orDash(extractTransferDisplay(row)));
        metaValueLabels.get("대상경로").setText(orDash(extractDestinationDisplay(row)));
        metaValueLabels.get("종료코드").setText(orDash(asText(row, "exitCode")));
        metaValueLabels.get("검증상태").setText(orDash(verificationLabel(row)));
        metaValueLabels.get("스크립트").setText(orDash(resolveDetailUrl(row)));

        detailStdout.setText(orDash(asText(row, "stdout")));
        detailStderr.setText(orDash(asText(row, "stderr")));

        String downloadUrl = resolveCollectDownloadUrl(row);
        boolean canDownload = downloadUrl != null && !downloadUrl.isBlank();
        detailDownloadBtn.setVisible(canDownload);
        detailDownloadBtn.setManaged(canDownload);
        if (canDownload) {
            detailDownloadBtn.setOnAction(e -> handleDownloadFromDetail(downloadUrl, suggestDownloadFilename(row)));
        }
    }

    /** 진행 단계 스텝퍼 렌더: 대기중 → 전송됨 → 실행중 → 완료. */
    private void renderProgressSteps(Map<String, Object> row) {
        progressStepsBox.getChildren().clear();
        CommandStatus status = resolveCommandStatus(row);
        boolean failed = status == CommandStatus.FAILED;
        int current = switch (status) {
            case QUEUED -> 0;
            case DELIVERED -> 1;
            case ACKED -> 2;
            case COMPLETED -> 3;
            case FAILED -> 3;   // 실패는 마지막 단계에 빨강으로 표시
            default -> 0;
        };
        for (int i = 0; i < STEP_LABELS.length; i++) {
            boolean isFailed = failed && i == current;
            boolean isDone = i < current;
            boolean isActive = i == current && !failed;

            Label badge = new Label(isFailed ? "!" : (isDone ? "✓" : String.valueOf(i + 1)));
            badge.getStyleClass().add("cmd-step-badge");
            if (isFailed) badge.getStyleClass().add("cmd-step-badge-failed");
            else if (isDone) badge.getStyleClass().add("cmd-step-badge-done");
            else if (isActive) badge.getStyleClass().add("cmd-step-badge-active");
            else badge.getStyleClass().add("cmd-step-badge-pending");

            Label caption = new Label(STEP_LABELS[i]);
            caption.getStyleClass().add("cmd-step-label");
            if (isFailed) caption.getStyleClass().add("cmd-step-label-failed");
            else if (isDone) caption.getStyleClass().add("cmd-step-label-done");
            else if (isActive) caption.getStyleClass().add("cmd-step-label-active");
            else caption.getStyleClass().add("cmd-step-label-pending");

            VBox stepBox = new VBox(6, badge, caption);
            stepBox.getStyleClass().add("cmd-step-item");
            stepBox.setAlignment(javafx.geometry.Pos.TOP_CENTER);
            progressStepsBox.getChildren().add(stepBox);

            if (i < STEP_LABELS.length - 1) {
                StackPane connectorBox = new StackPane();
                connectorBox.getStyleClass().add("cmd-step-connector-wrap");
                Region connector = new Region();
                connector.getStyleClass().add("cmd-step-connector");
                connector.setMaxWidth(Double.MAX_VALUE);
                if (i < current) connector.getStyleClass().add("cmd-step-connector-done");
                connectorBox.getChildren().add(connector);
                connectorBox.setPadding(new javafx.geometry.Insets(14, 0, 0, 0));
                connectorBox.setAlignment(javafx.geometry.Pos.TOP_CENTER);
                progressStepsBox.getChildren().add(connectorBox);
                HBox.setHgrow(connectorBox, Priority.ALWAYS);
            }
        }
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    private void slideIn() {
        if (panelAnim != null) panelAnim.stop();
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
        detailPanel.setMaxWidth(DETAIL_PANEL_WIDTH);
        panelAnim = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(220),
                new javafx.animation.KeyValue(detailPanel.prefWidthProperty(), DETAIL_PANEL_WIDTH),
                new javafx.animation.KeyValue(detailPanel.minWidthProperty(), DETAIL_PANEL_WIDTH)));
        panelAnim.play();
    }

    private void hideDetail() {
        if (panelAnim != null) panelAnim.stop();
        selectedKey = null;
        detailPanel.setMaxWidth(Double.MAX_VALUE);
        panelAnim = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(180),
                new javafx.animation.KeyValue(detailPanel.prefWidthProperty(), 0),
                new javafx.animation.KeyValue(detailPanel.minWidthProperty(), 0)));
        panelAnim.setOnFinished(e -> {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
        });
        panelAnim.play();
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

    private String resolveCollectDownloadUrl(Map<String, Object> row) {
        String direct = asText(row, "scriptUrl");
        String fromDirect = extractCollectDownloadUrl(direct);
        if (!fromDirect.isBlank()) {
            return fromDirect;
        }
        String stdout = asText(row, "stdout");
        String fromStdout = extractCollectDownloadUrl(stdout);
        if (!fromStdout.isBlank()) {
            return fromStdout;
        }
        return "";
    }

    private String extractCollectDownloadUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(https?://[^\\s]+/api/v1/agent/uploads/\\d+/download|/api/v1/agent/uploads/\\d+/download)")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String suggestDownloadFilename(Map<String, Object> row) {
        JsonNode payload = parsePayload(row);
        if (payload != null && payload.has("outputName")) {
            String n = payload.get("outputName").asText("").trim();
            if (!n.isBlank()) {
                return n.toLowerCase(Locale.ROOT).endsWith(".zip") ? n : n + ".zip";
            }
        }
        String taskId = asText(row, "taskId");
        return (taskId == null || taskId.isBlank()) ? "collected.zip" : "collected-" + taskId + ".zip";
    }

    private void handleDownloadFromDetail(String downloadUrl, String suggestedName) {
        if (downloadUrl == null || downloadUrl.isBlank() || "-".equals(downloadUrl)) {
            return;
        }
        String requestPath = normalizeDownloadRequestPath(downloadUrl);
        if (requestPath.isBlank()) {
            Dialogs.error(null, "다운로드 실패", "다운로드 실패",
                    "다운로드 URL 형식이 올바르지 않습니다: " + downloadUrl);
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
                byte[] data = apiClient.getBytes(requestPath).join();
                Files.write(target.toPath(), data);
                Platform.runLater(() -> {
                    Dialogs.info(null, "다운로드 완료", "다운로드 완료",
                            "저장 완료: " + target.getAbsolutePath());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Dialogs.error(null, "다운로드 실패", "다운로드 실패", ex.getMessage());
                });
            }
        }, "cmd-detail-download");
        t.setDaemon(true);
        t.start();
    }

    private String normalizeDownloadRequestPath(String downloadUrl) {
        return ApiUrlUtil.normalizeDownloadPath(downloadUrl);
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
