package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.model.dashboard.IpRangeItem;
import com.example.frontend.model.dashboard.TeacherDeviceItem;
import com.example.util.MultipartUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Cursor;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonBar;
import javafx.scene.layout.HBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardManagementModule {
    private static final Logger log = LoggerFactory.getLogger(DashboardManagementModule.class);
    private static final ExecutorService SCRIPT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "script-deploy");
        t.setDaemon(true);
        return t;
    });

    // 애플리케이션 종료 시 SCRIPT_EXECUTOR 정리
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!SCRIPT_EXECUTOR.isShutdown()) {
                try {
                    SCRIPT_EXECUTOR.shutdownNow();
                    if (!SCRIPT_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        org.slf4j.LoggerFactory.getLogger(DashboardManagementModule.class)
                                .warn("SCRIPT_EXECUTOR did not terminate within 5 seconds");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "script-executor-shutdown"));
    }
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Supplier<javafx.scene.Scene> sceneSupplier;
    private final ApiClient apiClient;

    private ComboBox<ClassroomSummaryDto> groupComboBox;
    private ComboBox<ClassroomSummaryDto> teacherGroupCombo;
    private ComboBox<ClassroomSummaryDto> brokenGroupCombo;
    private TableView<IpRangeItem> ipRangeTable;
    private TableView<TeacherDeviceItem> teacherDeviceTable;
    private TableView<PcStatusDto> brokenPcTable;
    private TableView<Map<String, Object>> agentTable;
    private TextField fileDistJobIdField;
    private Label fileDistAlertLabel;
    private Label fileDistStatusLabel;
    private Label fileDistProgressLabel;
    private TableView<Map<String, Object>> fileDistJobsTable;
    private TableView<Map<String, Object>> fileDistEventsTable;
    private Timeline fileDistAutoRefresh;
    private Label installKeyLabel;
    private final ClassroomSummaryDto allClassroomOption = createAllClassroomOption();
    private List<PcStatusDto> cachedBrokenPcs = List.of();

    public DashboardManagementModule(HttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     String apiBaseUrl,
                                     Supplier<javafx.scene.Scene> sceneSupplier) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.sceneSupplier = sceneSupplier;
        this.apiClient = ApiClient.getInstance();
    }

    public Node createView() {
        VBox container = new VBox(20);
        container.setPadding(new Insets(20));
        container.setFillWidth(true);
        container.setMaxWidth(Double.MAX_VALUE);

        VBox agentBox = buildAgentInstallBox();
        VBox ipBox = buildIpRangeBox();
        VBox teacherBox = buildTeacherDeviceBox();
        VBox brokenPcBox = buildBrokenPcBox();
        VBox reportBox = buildReportBox();

        agentBox.setMaxWidth(Double.MAX_VALUE);
        ipBox.setMaxWidth(Double.MAX_VALUE);
        teacherBox.setMaxWidth(Double.MAX_VALUE);
        brokenPcBox.setMaxWidth(Double.MAX_VALUE);
        reportBox.setMaxWidth(Double.MAX_VALUE);

        container.getChildren().addAll(agentBox, ipBox, teacherBox, brokenPcBox, reportBox);
        reloadData();

        ScrollPane scrollPane = new ScrollPane(container);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setPannable(true);
        scrollPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null && fileDistAutoRefresh != null) {
                fileDistAutoRefresh.stop();
            }
        });
        return scrollPane;
    }

    public void reloadData() {
        loadIpRangesAndGroups();
        loadAgentList();
        loadInstallKey();
        loadBrokenPcs();
    }

    @SuppressWarnings("unchecked")
    private VBox buildAgentInstallBox() {
        Label title = new Label("에이전트 설치 관리");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        // ── 설치 키 행 ──
        Label keyLabel = new Label("설치 키:");
        keyLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        installKeyLabel = new Label("불러오는 중...");
        installKeyLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 13px; -fx-text-fill: #3182ce; " +
                "-fx-background-color: #eff6ff; -fx-padding: 4 10 4 10; -fx-background-radius: 4;");
        Button copyKeyBtn = new Button("복사");
        copyKeyBtn.setStyle("-fx-background-color: #3182ce; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        copyKeyBtn.setOnAction(e -> {
            String key = installKeyLabel.getText();
            if (!key.isBlank() && !key.equals("불러오는 중...") && !key.equals("조회 실패")) {
                ClipboardContent content = new ClipboardContent();
                content.putString(key);
                Clipboard.getSystemClipboard().setContent(content);
                copyKeyBtn.setText("복사됨!");
                javafx.animation.PauseTransition reset =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                reset.setOnFinished(done -> copyKeyBtn.setText("복사"));
                reset.play();
            }
        });
        Button refreshKeyBtn = new Button("↺");
        refreshKeyBtn.setStyle("-fx-background-color: #718096; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        refreshKeyBtn.setOnAction(e -> loadInstallKey());

        // ── config.json 다운로드 ──
        Button downloadConfigBtn = new Button("설정 파일 다운로드");
        downloadConfigBtn.setStyle("-fx-background-color: #22863a; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        downloadConfigBtn.setOnAction(e -> downloadConfigJson());

        HBox keyRow = new HBox(8, keyLabel, installKeyLabel, copyKeyBtn, refreshKeyBtn, downloadConfigBtn);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        // ── 등록 에이전트 테이블 ──
        Label tableTitle = new Label("등록된 에이전트 목록");
        tableTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Button refreshAgentBtn = new Button("새로고침");
        refreshAgentBtn.setStyle("-fx-background-color: #22863a; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        refreshAgentBtn.setOnAction(e -> loadAgentList());
        HBox tableHeader = new HBox(8, tableTitle, refreshAgentBtn);
        tableHeader.setAlignment(Pos.CENTER_LEFT);

        TableColumn<Map<String, Object>, String> pcNameCol = new TableColumn<>("PC명");
        pcNameCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
        pcNameCol.setPrefWidth(130);

        TableColumn<Map<String, Object>, String> ipCol = new TableColumn<>("IP 주소");
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
        ipCol.setPrefWidth(120);

        TableColumn<Map<String, Object>, String> macCol = new TableColumn<>("MAC 주소");
        macCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("macAddress"))));
        macCol.setPrefWidth(140);

        TableColumn<Map<String, Object>, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("status"))));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setStyle(""); return; }
                setText(s);
                setStyle("ONLINE".equalsIgnoreCase(s)
                        ? "-fx-text-fill: #9fd59f; -fx-font-weight: bold;"
                        : "-fx-text-fill: #f4a3a3; -fx-font-weight: bold;");
            }
        });
        statusCol.setPrefWidth(80);

        TableColumn<Map<String, Object>, String> tokenCol = new TableColumn<>("토큰");
        tokenCol.setCellValueFactory(d -> {
            Object active = d.getValue().get("tokenActive");
            boolean isActive = Boolean.TRUE.equals(active);
            return new SimpleStringProperty(isActive ? "활성" : "폐기됨");
        });
        tokenCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setStyle(""); return; }
                setText(s);
                setStyle("활성".equals(s)
                        ? "-fx-text-fill: #22863a; -fx-font-weight: bold;"
                        : "-fx-text-fill: #9ca3af;");
            }
        });
        tokenCol.setPrefWidth(70);

        TableColumn<Map<String, Object>, String> lastSeenCol = new TableColumn<>("마지막 접속");
        lastSeenCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("lastSeen"))));
        lastSeenCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, String> orphanCol = new TableColumn<>("등록상태");
        orphanCol.setCellValueFactory(d -> {
            boolean orphan = Boolean.TRUE.equals(d.getValue().get("orphan"));
            return new SimpleStringProperty(orphan ? "고아토큰" : "정상");
        });
        orphanCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setStyle(""); return; }
                setText(s);
                setStyle("고아토큰".equals(s)
                        ? "-fx-text-fill: #b45309; -fx-font-weight: bold;"
                        : "-fx-text-fill: #475569;");
            }
        });
        orphanCol.setPrefWidth(80);

        TableColumn<Map<String, Object>, Void> actionCol = new TableColumn<>("관리");
        actionCol.setStyle("-fx-alignment: CENTER;");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button revokeBtn = new Button("토큰폐기");
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(4, revokeBtn, deleteBtn);
            {
                revokeBtn.setStyle("-fx-background-color: #e36209; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                deleteBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                box.setAlignment(Pos.CENTER);
                revokeBtn.setOnAction(e -> {
                    Map<String, Object> row = getTableView().getItems().get(getIndex());
                    if (row != null) revokeAgent(str(row.get("macAddress")));
                });
                deleteBtn.setOnAction(e -> {
                    Map<String, Object> row = getTableView().getItems().get(getIndex());
                    if (row != null) deleteAgent(str(row.get("macAddress")), str(row.get("pcName")));
                });
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : box);
            }
        });
        actionCol.setPrefWidth(140);

        agentTable = new TableView<>();
        agentTable.getColumns().addAll(pcNameCol, ipCol, macCol, statusCol, tokenCol, orphanCol, lastSeenCol, actionCol);
        agentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        agentTable.setPrefHeight(260);
        agentTable.setPlaceholder(new Label("등록된 에이전트가 없습니다."));
        agentTable.setStyle("-fx-background-color: white; -fx-font-size: 12px;");

        VBox box = new VBox(10, title, keyRow, tableHeader, agentTable);
        box.setAlignment(Pos.TOP_LEFT);
        box.setStyle("-fx-border-color: #3182ce; -fx-border-width: 2; -fx-padding: 15; -fx-background-color: #eff6ff; -fx-background-radius: 6;");
        return box;
    }

    private String str(Object obj) {
        return obj == null ? "-" : obj.toString();
    }

    private void loadInstallKey() {
        apiClient.getJson("/admin/install-key", new TypeReference<Map<String, Object>>() {})
                .thenAccept(resp -> Platform.runLater(() -> {
                    String key = resp != null ? str(resp.get("installKey")) : "-";
                    installKeyLabel.setText(key);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> installKeyLabel.setText("조회 실패"));
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private void loadAgentList() {
        if (agentTable == null) return;
        apiClient.getPagedList("/admin/agents", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(list -> Platform.runLater(() -> {
                    if (list == null) {
                        agentTable.getItems().setAll(List.of());
                        return;
                    }
                    List<Map<String, Object>> sorted = new ArrayList<>(list);
                    sorted.sort((a, b) -> {
                        boolean ao = Boolean.TRUE.equals(a.get("orphan"));
                        boolean bo = Boolean.TRUE.equals(b.get("orphan"));
                        if (ao == bo) return 0;
                        return ao ? -1 : 1;
                    });
                    agentTable.getItems().setAll(sorted);
                }))
                .exceptionally(ex -> {
                    log.warn("에이전트 목록 조회 실패", ex);
                    return null;
                });
    }

    private void revokeAgent(String mac) {
        if (mac == null || mac.equals("-")) return;
        apiClient.putJson("/admin/agents/" + mac + "/revoke", Map.of())
                .thenAccept(resp -> Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, mac + " 토큰이 폐기되었습니다.").show();
                    loadAgentList();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "토큰 폐기 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void deleteAgent(String mac, String pcName) {
        if (mac == null || mac.equals("-")) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "[" + pcName + "] " + mac + " 에이전트를 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("에이전트 삭제");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                apiClient.delete("/admin/agents/" + mac)
                        .thenAccept(resp -> Platform.runLater(() -> {
                            new Alert(Alert.AlertType.INFORMATION, mac + " 에이전트가 삭제되었습니다.").show();
                            loadAgentList();
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "삭제 실패: " + errorMessage(ex)).show());
                            return null;
                        });
            }
        });
    }

    private void downloadConfigJson() {
        String key = installKeyLabel.getText();
        if (key.isBlank() || key.equals("불러오는 중...") || key.equals("조회 실패")) {
            new Alert(Alert.AlertType.WARNING, "설치키를 먼저 불러와 주세요.").show();
            return;
        }
        
        // 백엔드 API에서 config.json 다운로드
        // (현재 연결된 서버 정보 자동 포함)
        apiClient.getBytes("/agent/config/download")
                .thenAccept(jsonBytes -> Platform.runLater(() -> {
                    FileChooser fc = new FileChooser();
                    fc.setTitle("설정 파일 저장");
                    fc.setInitialFileName("config.json");
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
                    File file = fc.showSaveDialog(sceneSupplier.get().getWindow());
                    if (file == null) return;
                    try {
                        Files.write(file.toPath(), jsonBytes);
                        new Alert(Alert.AlertType.INFORMATION, "설정 파일이 저장되었습니다:\n" + file.getAbsolutePath()).show();
                    } catch (IOException e) {
                        new Alert(Alert.AlertType.ERROR, "파일 저장 실패: " + e.getMessage()).show();
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        new Alert(Alert.AlertType.ERROR, "설정 파일 다운로드 실패: " + ex.getMessage()).show();
                    });
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private VBox buildIpRangeBox() {
        Label ipTitle = new Label("강의실 IP 대역 관리");
        ipTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        Label ipHint = new Label("강의실을 선택하면 해당 강의실 IP 대역만 표시됩니다.");
        ipHint.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 12px;");

        groupComboBox = buildClassroomComboBox();
        groupComboBox.setPromptText("강의실 선택 (전체)");
        groupComboBox.setPrefWidth(180);
        // 강의실 선택 시 해당 IP 대역만 필터링
        groupComboBox.valueProperty().addListener((obs, oldVal, newVal) -> loadIpRangesForGroup(newVal));

        TextField startIpField = new TextField();
        startIpField.setPromptText("시작 IP (예: 192.168.1.10)");
        startIpField.setPrefWidth(170);
        TextField endIpField = new TextField();
        endIpField.setPromptText("끝 IP (예: 192.168.1.30)");
        endIpField.setPrefWidth(170);
        TextField descField = new TextField();
        descField.setPromptText("설명");
        descField.setPrefWidth(180);
        Button addIpRangeBtn = new Button("대역 추가");
        addIpRangeBtn.setOnAction(e -> addIpRange(startIpField, endIpField, descField));

        HBox ipForm = new HBox(10, groupComboBox, startIpField, endIpField, descField, addIpRangeBtn);
        ipForm.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(descField, javafx.scene.layout.Priority.ALWAYS);

        ipRangeTable = new TableView<>();
        ipRangeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ipRangeTable.setPrefHeight(320);

        TableColumn<IpRangeItem, Long> idCol = new TableColumn<>("번호");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setMaxWidth(60);
        TableColumn<IpRangeItem, String> groupNameCol = new TableColumn<>("강의실");
        groupNameCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<IpRangeItem, String> startCol = new TableColumn<>("시작 IP");
        startCol.setCellValueFactory(new PropertyValueFactory<>("startIp"));
        TableColumn<IpRangeItem, String> endCol = new TableColumn<>("끝 IP");
        endCol.setCellValueFactory(new PropertyValueFactory<>("endIp"));
        TableColumn<IpRangeItem, String> descCol = new TableColumn<>("설명");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<IpRangeItem, Void> actionCol = new TableColumn<>("관리");
        actionCol.setMinWidth(80);
        actionCol.setStyle("-fx-alignment: CENTER;");
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(deleteBtn);
            {
                deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                box.setAlignment(Pos.CENTER);
                deleteBtn.setOnAction(event -> {
                    IpRangeItem item = getTableView().getItems().get(getIndex());
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "정말 삭제하시겠습니까?", ButtonType.YES, ButtonType.NO);
                    alert.showAndWait().ifPresent(type -> {
                        if (type == ButtonType.YES) deleteIpRange(item);
                    });
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        ipRangeTable.getColumns().addAll(idCol, groupNameCol, startCol, endCol, descCol, actionCol);

        VBox ipBox = new VBox(10, ipTitle, ipHint, ipForm, ipRangeTable);
        ipBox.setAlignment(Pos.TOP_LEFT);
        ipBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return ipBox;
    }

    private VBox buildTeacherDeviceBox() {
        Label teacherTitle = new Label("교사 PC 관리");
        teacherTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        teacherGroupCombo = buildClassroomComboBox();
        teacherGroupCombo.setPromptText("강의실 선택");
        teacherGroupCombo.setOnAction(e -> loadTeacherDevices());

        TextField teacherIp = new TextField();
        teacherIp.setPromptText("교사 IP");
        TextField teacherMac = new TextField();
        teacherMac.setPromptText("교사 MAC (선택)");
        TextField teacherDesc = new TextField();
        teacherDesc.setPromptText("설명");
        Button addTeacherBtn = new Button("교사 등록");
        addTeacherBtn.setOnAction(e -> addTeacherDevice(teacherIp, teacherMac, teacherDesc));

        teacherDeviceTable = new TableView<>();
        teacherDeviceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<TeacherDeviceItem, Long> teacherIdCol = new TableColumn<>("번호");
        teacherIdCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        TableColumn<TeacherDeviceItem, String> teacherGroupCol = new TableColumn<>("강의실");
        teacherGroupCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<TeacherDeviceItem, String> teacherIpCol = new TableColumn<>("IP 주소");
        teacherIpCol.setCellValueFactory(new PropertyValueFactory<>("ipAddress"));
        TableColumn<TeacherDeviceItem, String> teacherMacCol = new TableColumn<>("MAC 주소");
        teacherMacCol.setCellValueFactory(new PropertyValueFactory<>("macAddress"));
        TableColumn<TeacherDeviceItem, String> teacherDescCol = new TableColumn<>("설명");
        teacherDescCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<TeacherDeviceItem, Void> teacherActionCol = new TableColumn<>("관리");
        teacherActionCol.setStyle("-fx-alignment: CENTER;");
        teacherActionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(deleteBtn);
            {
                deleteBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white;");
                box.setAlignment(Pos.CENTER);
                deleteBtn.setOnAction(event -> {
                    TeacherDeviceItem item = getTableView().getItems().get(getIndex());
                    apiClient.delete("/dashboard/classrooms/teacher-devices/" + item.getId())
                        .thenAccept(r -> Platform.runLater(this::refreshTeacherDevices))
                        .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "교사 PC 삭제 실패: " + errorMessage(ex)).show());
                        return null;
                        });
                });
            }

            private void refreshTeacherDevices() {
                loadTeacherDevices();
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
        teacherDeviceTable.setPrefHeight(220);
        teacherDeviceTable.getColumns().add(teacherIdCol);
        teacherDeviceTable.getColumns().add(teacherGroupCol);
        teacherDeviceTable.getColumns().add(teacherIpCol);
        teacherDeviceTable.getColumns().add(teacherMacCol);
        teacherDeviceTable.getColumns().add(teacherDescCol);
        teacherDeviceTable.getColumns().add(teacherActionCol);

        HBox teacherForm = new HBox(10, teacherGroupCombo, teacherIp, teacherMac, teacherDesc, addTeacherBtn);
        teacherForm.setAlignment(Pos.CENTER_LEFT);
        VBox teacherBox = new VBox(10, teacherTitle, teacherForm, teacherDeviceTable);
        teacherBox.setAlignment(Pos.TOP_LEFT);
        teacherBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return teacherBox;
    }

    @SuppressWarnings("unchecked")
    private VBox buildBrokenPcBox() {
        Label brokenTitle = new Label("고장 PC 등록");
        brokenTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        
        brokenGroupCombo = buildClassroomComboBox();
        brokenGroupCombo.setPromptText("강의실 필터");
        brokenGroupCombo.setOnAction(e -> applyBrokenPcFilter());
        
        TextField brokenIpField = new TextField();
        brokenIpField.setPromptText("IP 주소");
        TextField brokenMacField = new TextField();
        brokenMacField.setPromptText("MAC 주소 (선택)");
        TextField brokenNoteField = new TextField();
        brokenNoteField.setPromptText("고장 사유");
        Button brokenRegisterBtn = new Button("고장 등록");
        brokenRegisterBtn.setOnAction(e -> markBrokenPc(brokenIpField, brokenMacField, brokenNoteField));

        brokenPcTable = buildBrokenPcTable();
        brokenPcTable.setPrefHeight(220);

        HBox brokenFilterBox = new HBox(10, brokenGroupCombo);
        brokenFilterBox.setAlignment(Pos.CENTER_LEFT);
        HBox brokenBox = new HBox(10, brokenIpField, brokenMacField, brokenNoteField, brokenRegisterBtn);
        brokenBox.setAlignment(Pos.CENTER_LEFT);
        
        VBox brokenPcBox = new VBox(10, brokenTitle, brokenFilterBox, brokenBox, brokenPcTable);
        brokenPcBox.setAlignment(Pos.TOP_LEFT);
        brokenPcBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return brokenPcBox;
    }

    @SuppressWarnings("unchecked")
    private TableView<PcStatusDto> buildBrokenPcTable() {
        TableView<PcStatusDto> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("등록된 고장 PC가 없습니다."));

        TableColumn<PcStatusDto, String> macCol = new TableColumn<>("MAC 주소");
        macCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getMacAddress())));
        macCol.setPrefWidth(150);

        TableColumn<PcStatusDto, String> ipCol = new TableColumn<>("IP 주소");
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getIpAddress())));
        ipCol.setPrefWidth(120);

        TableColumn<PcStatusDto, String> groupCol = new TableColumn<>("강의실");
        groupCol.setCellValueFactory(d -> new SimpleStringProperty(resolveGroupName(d.getValue().getGroupId())));
        groupCol.setPrefWidth(120);

        TableColumn<PcStatusDto, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getStatus())));
        statusCol.setPrefWidth(80);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                setStyle("-fx-text-fill: #d73a49; -fx-font-weight: bold;");
            }
        });

        TableColumn<PcStatusDto, String> noteCol = new TableColumn<>("고장 사유");
        noteCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getBrokenNote())));
        noteCol.setPrefWidth(220);

        TableColumn<PcStatusDto, Void> actionCol = new TableColumn<>("관리");
        actionCol.setMinWidth(180);
        actionCol.setStyle("-fx-alignment: CENTER;");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("사유 수정");
            private final Button clearBtn = new Button("해제");
            private final HBox box = new HBox(6, editBtn, clearBtn);
            {
                editBtn.setStyle("-fx-background-color: #e36209; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                clearBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                box.setAlignment(Pos.CENTER);
                editBtn.setOnAction(e -> {
                    PcStatusDto item = getTableView().getItems().get(getIndex());
                    if (item != null) {
                        showBrokenNoteEditDialog(item);
                    }
                });
                clearBtn.setOnAction(e -> {
                    PcStatusDto item = getTableView().getItems().get(getIndex());
                    if (item != null) {
                        clearBrokenPc(item);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        table.getColumns().addAll(macCol, ipCol, groupCol, statusCol, noteCol, actionCol);
        return table;
    }

    private VBox buildReportBox() {
        Label reportTitle = new Label("주간/월간 보고서");
        reportTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        HBox reportBtns = new HBox(15);
        reportBtns.setAlignment(Pos.CENTER_LEFT);
        Button weekBtn = new Button("주간 보고서 생성");
        weekBtn.setStyle("-fx-background-color: #22863a; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        weekBtn.setOnAction(e -> showReportDateDialog("WEEKLY"));

        Button monthBtn = new Button("월간 보고서 생성");
        monthBtn.setStyle("-fx-background-color: #3182ce; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        monthBtn.setOnAction(e -> showReportDateDialog("MONTHLY"));

        reportBtns.getChildren().addAll(weekBtn, monthBtn);
        VBox reportBox = new VBox(10, reportTitle, reportBtns);
        reportBox.setAlignment(Pos.TOP_LEFT);
        reportBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return reportBox;
    }

    @SuppressWarnings("unchecked")
    private VBox buildFileDistributionBox() {
        Label title = new Label("파일 배포 모니터링");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        fileDistAlertLabel = new Label("알림: -");
        fileDistAlertLabel.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #1e3a8a; -fx-padding: 6 10; -fx-background-radius: 4;");

        fileDistJobIdField = new TextField();
        fileDistJobIdField.setPromptText("Job ID 입력");
        fileDistJobIdField.setPrefWidth(140);

        Button refreshBtn = new Button("상태 조회");
        refreshBtn.setOnAction(e -> {
            loadRecentFileDistributionJobs();
            loadFileDistributionIfSelected();
        });

        Button cancelBtn = new Button("Job 취소");
        cancelBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white;");
        cancelBtn.setOnAction(e -> cancelSelectedFileDistributionJob());

        Button retryFailedBtn = new Button("실패 재전송");
        retryFailedBtn.setStyle("-fx-background-color: #e36209; -fx-text-fill: white;");
        retryFailedBtn.setOnAction(e -> retryFailedForSelectedJob());

        Button rollbackBtn = new Button("롤백 실행");
        rollbackBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white;");
        rollbackBtn.setOnAction(e -> rollbackSelectedJob());

        HBox topRow = new HBox(8, new Label("Job ID:"), fileDistJobIdField, refreshBtn, retryFailedBtn, rollbackBtn, cancelBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        fileDistStatusLabel = new Label("상태: -");
        fileDistProgressLabel = new Label("진행률: -");
        HBox statusRow = new HBox(20, fileDistStatusLabel, fileDistProgressLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        fileDistJobsTable = new TableView<>();
        fileDistJobsTable.setPrefHeight(160);
        fileDistJobsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        fileDistJobsTable.setPlaceholder(new Label("최근 배포 Job 없음"));

        TableColumn<Map<String, Object>, String> jobIdCol = new TableColumn<>("Job ID");
        jobIdCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("jobId", "-"))));
        jobIdCol.setPrefWidth(90);
        TableColumn<Map<String, Object>, String> fileCol = new TableColumn<>("파일명");
        fileCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("originalFilename", "-"))));
        fileCol.setPrefWidth(220);
        TableColumn<Map<String, Object>, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("status", "-"))));
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                String c = switch (item) {
                    case "COMPLETED" -> "#15803d";
                    case "FAILED" -> "#b91c1c";
                    case "CANCELLED" -> "#9a3412";
                    case "IN_PROGRESS" -> "#1d4ed8";
                    default -> "#334155";
                };
                setStyle("-fx-text-fill: " + c + "; -fx-font-weight: bold;");
            }
        });
        TableColumn<Map<String, Object>, String> createdCol = new TableColumn<>("생성시각");
        createdCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("createdAt", "-"))));
        createdCol.setPrefWidth(180);
        fileDistJobsTable.getColumns().setAll(jobIdCol, fileCol, statusCol, createdCol);
        fileDistJobsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, row) -> {
            if (row == null) return;
            String jobId = String.valueOf(row.getOrDefault("jobId", "")).trim();
            if (!jobId.isBlank()) {
                fileDistJobIdField.setText(jobId);
                loadFileDistributionIfSelected();
            }
        });

        fileDistEventsTable = new TableView<>();
        fileDistEventsTable.setPrefHeight(220);
        fileDistEventsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Map<String, Object>, String> typeCol = new TableColumn<>("이벤트");
        typeCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("eventType", "-"))));
        typeCol.setPrefWidth(140);
        TableColumn<Map<String, Object>, String> detailCol = new TableColumn<>("상세");
        detailCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("detailJson", "-"))));
        detailCol.setPrefWidth(360);
        TableColumn<Map<String, Object>, String> eventCreatedCol = new TableColumn<>("시각");
        eventCreatedCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().getOrDefault("createdAt", "-"))));
        eventCreatedCol.setPrefWidth(160);
        fileDistEventsTable.getColumns().setAll(typeCol, detailCol, eventCreatedCol);

        VBox box = new VBox(10, title, fileDistAlertLabel, topRow, statusRow, new Label("최근 배포 Job"), fileDistJobsTable, new Label("선택 Job 이벤트"), fileDistEventsTable);
        box.setAlignment(Pos.TOP_LEFT);
        box.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return box;
    }

    private void startFileDistAutoRefresh() {
        if (fileDistAutoRefresh != null) {
            fileDistAutoRefresh.stop();
        }
        fileDistAutoRefresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            loadRecentFileDistributionJobs();
            loadFileDistributionIfSelected();
        }));
        fileDistAutoRefresh.setCycleCount(Timeline.INDEFINITE);
        fileDistAutoRefresh.play();
    }

    private void loadRecentFileDistributionJobs() {
        if (fileDistJobsTable == null) return;
        apiClient.getPagedList("/file-distribution/jobs", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(rows -> Platform.runLater(() -> {
                    List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;
                    fileDistJobsTable.getItems().setAll(safeRows);
                    updateFileDistAlert(safeRows);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        fileDistJobsTable.getItems().clear();
                        if (fileDistAlertLabel != null) {
                            fileDistAlertLabel.setText("알림: 최근 Job 조회 실패");
                            fileDistAlertLabel.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #991b1b; -fx-padding: 6 10; -fx-background-radius: 4;");
                        }
                    });
                    return null;
                });
    }

    private void updateFileDistAlert(List<Map<String, Object>> rows) {
        if (fileDistAlertLabel == null) return;
        List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;
        long inProgress = safeRows.stream().filter(r -> "IN_PROGRESS".equals(String.valueOf(r.get("status")))).count();
        long failed = safeRows.stream().filter(r -> "FAILED".equals(String.valueOf(r.get("status")))).count();
        long cancelled = safeRows.stream().filter(r -> "CANCELLED".equals(String.valueOf(r.get("status")))).count();

        if (failed > 0) {
            fileDistAlertLabel.setText("알림: 실패 Job " + failed + "건");
            fileDistAlertLabel.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #991b1b; -fx-padding: 6 10; -fx-background-radius: 4;");
            return;
        }
        if (cancelled > 0) {
            fileDistAlertLabel.setText("알림: 취소 Job " + cancelled + "건");
            fileDistAlertLabel.setStyle("-fx-background-color: #fff7ed; -fx-text-fill: #9a3412; -fx-padding: 6 10; -fx-background-radius: 4;");
            return;
        }
        if (inProgress > 0) {
            fileDistAlertLabel.setText("알림: 진행중 Job " + inProgress + "건");
            fileDistAlertLabel.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #1e3a8a; -fx-padding: 6 10; -fx-background-radius: 4;");
            return;
        }
        fileDistAlertLabel.setText("알림: 현재 이상 없음");
        fileDistAlertLabel.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #065f46; -fx-padding: 6 10; -fx-background-radius: 4;");
    }

    private void loadFileDistributionIfSelected() {
        if (fileDistJobIdField == null) return;
        String jobId = fileDistJobIdField.getText() == null ? "" : fileDistJobIdField.getText().trim();
        if (jobId.isBlank()) return;

        apiClient.getJson("/file-distribution/jobs/" + jobId, new TypeReference<Map<String, Object>>() {})
                .thenAccept(status -> Platform.runLater(() -> {
                    String s = String.valueOf(status.getOrDefault("status", "-"));
                    String p = String.valueOf(status.getOrDefault("progress", "-"));
                    fileDistStatusLabel.setText("상태: " + s);
                    fileDistProgressLabel.setText("진행률: " + p);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        fileDistStatusLabel.setText("상태: 조회 실패");
                        fileDistProgressLabel.setText("진행률: -");
                    });
                    return null;
                });

        apiClient.getPagedList("/file-distribution/jobs/" + jobId + "/events", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(events -> Platform.runLater(() -> fileDistEventsTable.getItems().setAll(events == null ? List.of() : events)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> fileDistEventsTable.getItems().clear());
                    return null;
                });
    }

    private void cancelSelectedFileDistributionJob() {
        if (fileDistJobIdField == null) return;
        String jobId = fileDistJobIdField.getText() == null ? "" : fileDistJobIdField.getText().trim();
        if (jobId.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Job ID를 입력하세요.").show();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "배포 Job을 취소하시겠습니까?", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(type -> {
            if (type != ButtonType.YES) return;
            apiClient.postJson("/file-distribution/jobs/" + jobId + "/cancel", Map.of())
                    .thenAccept(resp -> Platform.runLater(() -> {
                        new Alert(Alert.AlertType.INFORMATION, "배포 Job이 취소되었습니다.").show();
                        loadFileDistributionIfSelected();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "취소 실패: " + errorMessage(ex)).show());
                        return null;
                    });
        });
    }

    private void retryFailedForSelectedJob() {
        if (fileDistJobIdField == null) return;
        String jobId = fileDistJobIdField.getText() == null ? "" : fileDistJobIdField.getText().trim();
        if (jobId.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Job ID를 입력하세요.").show();
            return;
        }
        apiClient.postJson("/file-distribution/jobs/" + jobId + "/retry-failed", Map.of())
                .thenAccept(resp -> Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "실패 대상 재전송을 시작했습니다.").show();
                    loadRecentFileDistributionJobs();
                    loadFileDistributionIfSelected();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "실패 재전송 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void rollbackSelectedJob() {
        if (fileDistJobIdField == null) return;
        String sourceJobId = fileDistJobIdField.getText() == null ? "" : fileDistJobIdField.getText().trim();
        if (sourceJobId.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "Job ID를 입력하세요.").show();
            return;
        }

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("롤백 실행");
        dialog.setHeaderText("복구 기준(안정) Job ID를 입력하세요");
        dialog.setContentText("rollbackFromJobId:");
        dialog.showAndWait().ifPresent(rollbackFrom -> {
            String rollbackFromId = rollbackFrom == null ? "" : rollbackFrom.trim();
            if (rollbackFromId.isBlank()) {
                new Alert(Alert.AlertType.WARNING, "rollbackFromJobId를 입력하세요.").show();
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rollbackFromJobId", rollbackFromId);
            body.put("waveSize", 20);
            body.put("canaryOnly", false);
            apiClient.postJson("/file-distribution/jobs/" + sourceJobId + "/rollback", body)
                    .thenAccept(resp -> Platform.runLater(() -> {
                        new Alert(Alert.AlertType.INFORMATION, "롤백 배포 Job이 생성되었습니다.").show();
                        loadRecentFileDistributionJobs();
                        loadFileDistributionIfSelected();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "롤백 실행 실패: " + errorMessage(ex)).show());
                        return null;
                    });
        });
    }

    private ComboBox<ClassroomSummaryDto> buildClassroomComboBox() {
        ComboBox<ClassroomSummaryDto> comboBox = new ComboBox<>();
        comboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ClassroomSummaryDto object) {
                return object != null ? object.getGroupName() : "";
            }

            @Override
            public ClassroomSummaryDto fromString(String string) {
                return null;
            }
        });
        return comboBox;
    }

    private ClassroomSummaryDto createAllClassroomOption() {
        ClassroomSummaryDto dto = new ClassroomSummaryDto();
        dto.setGroupId(null);
        dto.setGroupName("전체");
        return dto;
    }

    private void loadIpRangesAndGroups() {
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
            .thenAccept(classrooms -> {
                try {
                    log.info("Loaded {} classrooms from API", classrooms == null ? "null" : classrooms.size());

                    List<ClassroomSummaryDto> options = new ArrayList<>();
                    options.add(allClassroomOption);
                    if (classrooms != null) {
                        options.addAll(classrooms);
                    }

                    Platform.runLater(() -> {
                        groupComboBox.getItems().setAll(options);
                        teacherGroupCombo.getItems().setAll(options);
                        if (brokenGroupCombo != null) {
                            brokenGroupCombo.getItems().setAll(options);
                            brokenGroupCombo.getSelectionModel().selectFirst();
                        }
                        groupComboBox.getSelectionModel().selectFirst();
                        teacherGroupCombo.getSelectionModel().selectFirst();
                        loadTeacherDevices();
                    });

                    loadIpRangesForGroup(null);
                } catch (Exception e) {
                    log.warn("Failed while preparing classroom list", e);
                }
            })
            .exceptionally(ex -> {
                log.warn("Failed to load classrooms", ex);
                if (isAuthExpired(ex)) {
                    return null;
                }
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "강의실 목록 조회 실패: " + errorMessage(ex)).show());
                return null;
            });
    }

    /** 선택한 강의실의 IP 대역만 조회. group == null 이면 전체. */
    private void loadIpRangesForGroup(ClassroomSummaryDto group) {
        String path = (group != null && group.getGroupId() != null)
                ? "/ip-ranges?groupId=" + group.getGroupId()
                : "/ip-ranges";
        apiClient.getPagedList(path, new TypeReference<List<IpRangeItem>>() {})
                .thenAccept(items -> {
                    items.forEach(item -> item.setGroupName(resolveClassroomName(item.getGroupId(), item.getGroupName())));
                    Platform.runLater(() -> ipRangeTable.getItems().setAll(items));
                })
                .exceptionally(ex -> {
                    log.warn("Failed to load IP ranges for group {}", group, ex);
                    if (isAuthExpired(ex)) {
                        return null;
                    }
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "IP 대역 목록 조회 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void loadTeacherDevices() {
        ClassroomSummaryDto selected = teacherGroupCombo.getValue();
        if (selected == null || selected.getGroupId() == null) {
            loadAllTeacherDevices();
            return;
        }
        apiClient.getJson("/dashboard/classrooms/" + selected.getGroupId() + "/teacher-devices", new TypeReference<List<TeacherDeviceItem>>() {})
                .thenAccept(items -> {
                    items.forEach(it -> it.setGroupName(resolveClassroomName(it.getGroupId(), selected.getGroupName())));
                    Platform.runLater(() -> teacherDeviceTable.getItems().setAll(items));
                })
                .exceptionally(ex -> {
                    log.warn("Failed to load teacher devices for group {}", selected.getGroupId(), ex);
                    if (isAuthExpired(ex)) {
                        return null;
                    }
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "교사 PC 목록 조회 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void loadAllTeacherDevices() {
        if (teacherGroupCombo == null || teacherDeviceTable == null) {
            return;
        }

        List<ClassroomSummaryDto> classrooms = teacherGroupCombo.getItems().stream()
                .filter(c -> c != null && c.getGroupId() != null)
                .toList();

        if (classrooms.isEmpty()) {
            Platform.runLater(() -> teacherDeviceTable.getItems().clear());
            return;
        }

        List<CompletableFuture<List<TeacherDeviceItem>>> futures = new ArrayList<>();
        for (ClassroomSummaryDto classroom : classrooms) {
            CompletableFuture<List<TeacherDeviceItem>> future =
                    apiClient.getJson(
                                    "/dashboard/classrooms/" + classroom.getGroupId() + "/teacher-devices",
                                    new TypeReference<List<TeacherDeviceItem>>() {})
                            .thenApply(items -> {
                                items.forEach(it -> it.setGroupName(resolveClassroomName(it.getGroupId(), classroom.getGroupName())));
                                return items;
                            })
                            .exceptionally(ex -> {
                                log.warn("Failed to load teacher devices for group {}", classroom.getGroupId(), ex);
                                return List.of();
                            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    List<TeacherDeviceItem> merged = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .sorted((a, b) -> {
                                String ga = a.getGroupName() == null ? "" : a.getGroupName();
                                String gb = b.getGroupName() == null ? "" : b.getGroupName();
                                int byGroup = ga.compareToIgnoreCase(gb);
                                if (byGroup != 0) return byGroup;
                                String ia = a.getIpAddress() == null ? "" : a.getIpAddress();
                                String ib = b.getIpAddress() == null ? "" : b.getIpAddress();
                                return ia.compareToIgnoreCase(ib);
                            })
                            .toList();
                    Platform.runLater(() -> teacherDeviceTable.getItems().setAll(merged));
                });
    }

    private void addTeacherDevice(TextField ipTF, TextField macTF, TextField descTF) {
        ClassroomSummaryDto selectedGroup = teacherGroupCombo.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
            return;
        }
        if (selectedGroup.getGroupId() == null) {
            new Alert(Alert.AlertType.ERROR, "'전체'에서는 교사 PC를 등록할 수 없습니다. 특정 강의실을 선택하세요.").show();
            return;
        }
        try {
            String ip = ipTF.getText() == null ? "" : ipTF.getText().trim();
            String mac = macTF.getText() == null ? "" : macTF.getText().trim();
            String description = descTF.getText() == null ? "" : descTF.getText().trim();
            if (ip.isBlank()) {
                new Alert(Alert.AlertType.ERROR, "교사 IP를 입력하세요.").show();
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groupId", selectedGroup.getGroupId());
            payload.put("ipAddress", ip);
            if (!mac.isBlank()) {
                payload.put("macAddress", mac);
            }
            if (!description.isBlank()) {
                payload.put("description", description);
            }
            apiClient.postJson("/dashboard/classrooms/" + selectedGroup.getGroupId() + "/teacher-devices", payload)
                    .thenAccept(resp -> Platform.runLater(() -> {
                        ipTF.clear();
                        macTF.clear();
                        descTF.clear();
                        loadTeacherDevices();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "교사 PC 등록 실패: " + errorMessage(ex)).show());
                        return null;
                    });
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "입력 값을 확인하세요.").show();
        }
    }

    private void markBrokenPc(TextField ipTF, TextField macTF, TextField noteTF) {
        String ip = ipTF.getText() == null ? "" : ipTF.getText().trim();
        String mac = macTF.getText() == null ? "" : macTF.getText().trim();
        if (mac.isBlank() && ip.isBlank()) {
            new Alert(Alert.AlertType.ERROR, "IP 주소 또는 MAC 주소를 입력하세요.").show();
            return;
        }
        String note = noteTF.getText() == null ? "" : noteTF.getText().trim();
        Map<String, String> payload = new LinkedHashMap<>();
        if (!ip.isBlank()) payload.put("ipAddress", ip);
        if (!mac.isBlank()) payload.put("macAddress", mac);
        payload.put("note", note.isBlank() ? "broken" : note);
        apiClient.postJson("/dashboard/pcs/broken", payload)
                .thenAccept(resp -> Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "고장 PC로 등록되었습니다.").show();
                    ipTF.clear();
                    macTF.clear();
                    noteTF.clear();
                    loadBrokenPcs();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "등록 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void loadBrokenPcs() {
        if (brokenPcTable == null) {
            return;
        }
        apiClient.getJson("/dashboard/pcs/broken", new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(items -> Platform.runLater(() -> {
                    cachedBrokenPcs = items;
                    applyBrokenPcFilter();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        cachedBrokenPcs = List.of();
                        brokenPcTable.getItems().clear();
                    });
                    return null;
                });
    }

    private void applyBrokenPcFilter() {
        if (brokenPcTable == null) {
            return;
        }
        ClassroomSummaryDto selected = brokenGroupCombo == null ? null : brokenGroupCombo.getValue();
        List<PcStatusDto> filtered = cachedBrokenPcs;
        if (selected != null && selected.getGroupId() != null) {
            filtered = cachedBrokenPcs.stream()
                    .filter(pc -> selected.getGroupId().equals(pc.getGroupId()))
                    .toList();
        }
        brokenPcTable.getItems().setAll(filtered);
    }

    private String resolveGroupName(Long groupId) {
        return resolveClassroomName(groupId, null);
    }

    private String resolveClassroomName(Long groupId, String fallbackName) {
        if (groupId == null) {
            return fallbackName != null && !fallbackName.isBlank() ? fallbackName : "-";
        }

        String resolved = classroomOptions().stream()
                .filter(item -> item != null && groupId.equals(item.getGroupId()))
                .map(ClassroomSummaryDto::getGroupName)
                .filter(name -> name != null && !name.isBlank() && !"전체".equals(name))
                .findFirst()
                .orElse(null);

        if (resolved != null) {
            return resolved;
        }
        if (fallbackName != null && !fallbackName.isBlank() && !"전체".equals(fallbackName)) {
            return fallbackName;
        }
        return "강의실#" + groupId;
    }

    private List<ClassroomSummaryDto> classroomOptions() {
        if (groupComboBox != null && !groupComboBox.getItems().isEmpty()) {
            return groupComboBox.getItems();
        }
        if (teacherGroupCombo != null && !teacherGroupCombo.getItems().isEmpty()) {
            return teacherGroupCombo.getItems();
        }
        if (brokenGroupCombo != null && !brokenGroupCombo.getItems().isEmpty()) {
            return brokenGroupCombo.getItems();
        }
        return List.of();
    }

    private void showBrokenNoteEditDialog(PcStatusDto item) {
        TextInputDialog dialog = new TextInputDialog(item.getBrokenNote());
        dialog.setTitle("고장 사유 수정");
        dialog.setHeaderText(item.getMacAddress() + " / " + safe(item.getIpAddress()));
        dialog.setContentText("고장 사유:");
        dialog.showAndWait().ifPresent(note ->
                apiClient.putJson("/dashboard/pcs/broken/" + item.getMacAddress(), Map.of("note", note == null ? "" : note.trim()))
                        .thenAccept(resp -> Platform.runLater(this::loadBrokenPcs))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "사유 수정 실패: " + errorMessage(ex)).show());
                            return null;
                        }));
    }

    private void clearBrokenPc(PcStatusDto item) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "고장 상태를 해제하시겠습니까?",
                ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.YES) {
                apiClient.delete("/dashboard/pcs/broken/" + item.getMacAddress())
                        .thenAccept(resp -> Platform.runLater(this::loadBrokenPcs))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "고장 해제 실패: " + errorMessage(ex)).show());
                            return null;
                        });
            }
        });
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }

    private void addIpRange(TextField startIp, TextField endIp, TextField desc) {
        ClassroomSummaryDto selectedGroup = groupComboBox.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
            return;
        }
        if (selectedGroup.getGroupId() == null) {
            new Alert(Alert.AlertType.ERROR, "'전체'에서는 IP 대역을 추가할 수 없습니다. 특정 강의실을 선택하세요.").show();
            return;
        }

        try {
            String start = startIp.getText() == null ? "" : startIp.getText().trim();
            String end = endIp.getText() == null ? "" : endIp.getText().trim();
            if (start.isBlank() || end.isBlank()) {
                new Alert(Alert.AlertType.ERROR, "시작 IP와 끝 IP를 모두 입력하세요.").show();
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groupId", selectedGroup.getGroupId());
            payload.put("startIp", start);
            payload.put("endIp", end);
            payload.put("description", desc.getText());

            apiClient.postJson("/ip-ranges", payload)
                    .thenAccept(resp -> Platform.runLater(() -> {
                        startIp.clear();
                        endIp.clear();
                        desc.clear();
                        loadIpRangesForGroup(selectedGroup);
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "추가 실패\n" + errorMessage(ex)).show());
                        return null;
                    });
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "입력 값을 확인하세요.").show();
        }
    }

    /** IP 대역의 개별 PC 제어 팝업을 표시한다. */
    @SuppressWarnings("unchecked")
    private void showIndividualControlDialog(IpRangeItem item) {
        String title = "개별 PC 제어 — " + item.getGroupName()
                + " [" + item.getStartIp() + " ~ " + item.getEndIp() + "]";

        TableColumn<PcStatusDto, String> nameCol = new TableColumn<>("PC명");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getHostname() != null ? d.getValue().getHostname() : "-"));
        nameCol.setPrefWidth(160);

        TableColumn<PcStatusDto, String> ipCol = new TableColumn<>("IP 주소");
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getIpAddress() != null ? d.getValue().getIpAddress() : "-"));
        ipCol.setPrefWidth(130);

        TableColumn<PcStatusDto, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(d -> {
            String s = d.getValue().getStatus();
            if (s == null || s.isBlank()) s = d.getValue().isOnline() ? "ONLINE" : "OFFLINE";
            return new SimpleStringProperty(s);
        });
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) { setText(null); setStyle(""); return; }
                setText(status);
                setStyle("ONLINE".equalsIgnoreCase(status)
                        ? "-fx-text-fill: #22863a; -fx-font-weight: bold;"
                        : "-fx-text-fill: #d73a49; -fx-font-weight: bold;");
            }
        });
        statusCol.setPrefWidth(80);

        TableColumn<PcStatusDto, Void> actionCol = new TableColumn<>("제어");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button shutdownBtn = new Button("종료");
            private final Button rebootBtn   = new Button("재시작");
            private final Button wolBtn      = new Button("켜기(WOL)");
            private final HBox box           = new HBox(4, shutdownBtn, rebootBtn, wolBtn);
            {
                shutdownBtn.setStyle("-fx-background-color: #d73a49; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                rebootBtn.setStyle("-fx-background-color: #e36209; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                wolBtn.setStyle("-fx-background-color: #22863a; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                box.setAlignment(Pos.CENTER);
                shutdownBtn.setOnAction(e -> {
                    PcStatusDto pc = getTableView().getItems().get(getIndex());
                    if (pc != null) sendCommandToIp(pc.getIpAddress(), "SHUTDOWN");
                });
                rebootBtn.setOnAction(e -> {
                    PcStatusDto pc = getTableView().getItems().get(getIndex());
                    if (pc != null) sendCommandToIp(pc.getIpAddress(), "REBOOT");
                });
                wolBtn.setOnAction(e -> {
                    PcStatusDto pc = getTableView().getItems().get(getIndex());
                    if (pc != null) sendCommandToIp(pc.getIpAddress(), "WOL");
                });
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : box);
            }
        });
        actionCol.setPrefWidth(210);

        @SuppressWarnings("unchecked")
        TableView<PcStatusDto> table = new TableView<>();
        table.getColumns().addAll(nameCol, ipCol, statusCol, actionCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("PC 정보를 불러오는 중..."));
        table.setStyle("-fx-background-color: white; -fx-font-size: 13px;");
        table.setPrefHeight(440);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: #f8fbff;");
        Label headerLabel = new Label(title);
        headerLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        root.getChildren().addAll(headerLabel, table);

        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.setTitle(title);
        dialog.setScene(new javafx.scene.Scene(root, 700, 500));
        dialog.show();

        if (item.getGroupId() != null) {
            apiClient.getPagedList("/dashboard/classrooms/" + item.getGroupId() + "/pcs",
                            new TypeReference<List<PcStatusDto>>() {})
                    .thenAccept(pcs -> Platform.runLater(() -> {
                        if (pcs != null && !pcs.isEmpty()) {
                            table.getItems().setAll(pcs);
                        }
                        table.setPlaceholder(new Label("해당 강의실에 등록된 PC가 없습니다."));
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> table.setPlaceholder(
                                new Label("PC 목록 조회 실패: " + errorMessage(ex))));
                        return null;
                    });
        } else {
            table.setPlaceholder(new Label("그룹 ID가 없어 PC 목록을 가져올 수 없습니다."));
        }
    }

    private Button makeCommandBtn(String label, String color, String ip, String commandType) {
        Button btn = new Button(label);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-size: 11px;");
        btn.setOnAction(e -> sendCommandToIp(ip, commandType));
        return btn;
    }

    private void sendCommandToIp(String ip, String commandType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ipAddress", ip);
        payload.put("type", commandType);
        apiClient.postJson("/commands/to-ip", payload)
                .thenAccept(resp -> log.info("Command {} queued for {}", commandType, ip))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            ip + " 명령 전송 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    /** startIp ~ endIp 범위의 IP 목록을 반환. 최대 255개 제한. */
    private List<String> enumerateIps(String startIp, String endIp) {
        List<String> ips = new ArrayList<>();
        if (startIp == null || endIp == null || startIp.isBlank() || endIp.isBlank()) return ips;
        try {
            long start = ipToLong(startIp.trim());
            long end   = ipToLong(endIp.trim());
            long limit = Math.min(end, start + 254); // 최대 255개
            for (long i = start; i <= limit; i++) {
                ips.add(longToIp(i));
            }
        } catch (Exception e) {
            log.warn("IP 열거 실패 {} ~ {}", startIp, endIp, e);
        }
        return ips;
    }

    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (String part : parts) {
            result = result * 256 + Long.parseLong(part.trim());
        }
        return result;
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private void deleteIpRange(IpRangeItem item) {
        apiClient.delete("/ip-ranges/" + item.getId())
                .thenAccept(resp -> Platform.runLater(this::loadIpRangesAndGroups))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "삭제 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    // ─── 스크립트 / 보고서 ────────────────────────────────────────────────

    private void handleScriptUploadAndRun() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("PowerShell 스크립트 선택");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerShell Files", "*.ps1"));
        File file = fileChooser.showOpenDialog(sceneSupplier.get().getWindow());

        if (file != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "'" + file.getName() + "' 파일을 모든 PC에 배포하고 실행하시겠습니까?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(type -> {
                if (type == ButtonType.YES) uploadAndExecuteScript(file);
            });
        }
    }

    private void uploadAndExecuteScript(File file) {
        SCRIPT_EXECUTOR.execute(() -> {
            try {
                HttpRequest uploadRequest = MultipartUtil
                    .newMultipartRequest(apiBaseUrl + "/scripts",
                        Map.of("file", file.toPath().toString()),
                        buildAuthHeaders())
                        .build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());

                if (uploadResponse.statusCode() < 200 || uploadResponse.statusCode() >= 300) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "스크립트 업로드 실패: HTTP " + uploadResponse.statusCode()).show());
                    return;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> uploadResp = objectMapper.readValue(uploadResponse.body(), Map.class);
                String scriptUrl = String.valueOf(uploadResp.getOrDefault("downloadUrl", ""));
                if (scriptUrl.isBlank()) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "스크립트 업로드 응답에 downloadUrl이 없습니다.").show());
                    return;
                }

                // 전체 강의실에 GROUP RUN_PS1 명령 발행
                List<ClassroomSummaryDto> classrooms = apiClient
                        .getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
                        .join();

                int queuedGroups = 0;
                int failedGroups = 0;
                for (ClassroomSummaryDto c : classrooms) {
                    if (c == null || c.getGroupId() == null) continue;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("scope", "GROUP");
                    payload.put("groupId", c.getGroupId());
                    payload.put("role", "ALL");
                    payload.put("type", "RUN_PS1");
                    payload.put("scriptUrl", scriptUrl);
                    payload.put("payloadJson", "{}");
                    try {
                        apiClient.postJson("/commands", payload).join();
                        queuedGroups++;
                    } catch (Exception e) {
                        log.warn("Failed to enqueue RUN_PS1 for group {}", c.getGroupId(), e);
                        failedGroups++;
                    }
                }

                final int successCount = queuedGroups;
                final int failCount = failedGroups;
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                    "스크립트 배포 요청 완료: 성공 " + successCount + "개 강의실, 실패 " + failCount + "개").show());
            } catch (Exception e) {
                log.warn("Failed to upload/execute script {}", file.getName(), e);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "스크립트 업로드 오류: " + e.getMessage()).show());
            }
        });
    }

    private String errorMessage(Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
        if (cause == null) return "알 수 없는 오류";
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.toString() : msg;
    }

    private boolean isAuthExpired(Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause()
                : ex;
        if (cause instanceof com.example.frontend.service.ApiClientException apiEx) {
            return apiEx.getStatusCode() == 401 || apiEx.getStatusCode() == 403;
        }
        return false;
    }

    private Map<String, String> buildAuthHeaders() {
        String bearerToken = apiClient.getBearerToken();
        if (bearerToken == null || bearerToken.isBlank()) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + bearerToken);
    }

    private void showReportDateDialog(String defaultType) {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("보고서 생성");
        dialog.setHeaderText("보고서 유형과 기간을 선택하세요");
        ButtonType generateBtn = new ButtonType("생성", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(generateBtn, ButtonType.CANCEL);

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton weeklyBtn = new RadioButton("주간 보고서");
        weeklyBtn.setToggleGroup(typeGroup);
        RadioButton monthlyBtn = new RadioButton("월간 보고서");
        monthlyBtn.setToggleGroup(typeGroup);
        if ("MONTHLY".equalsIgnoreCase(defaultType)) {
            monthlyBtn.setSelected(true);
        } else {
            weeklyBtn.setSelected(true);
        }

        LocalDate now = LocalDate.now();
        LocalDate monday = now.with(java.time.DayOfWeek.MONDAY);
        LocalDate weekStart = monday;
        LocalDate weekEnd = monday.plusDays(6);
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now.withDayOfMonth(now.lengthOfMonth());

        DatePicker fromPicker = new DatePicker(weeklyBtn.isSelected() ? weekStart : monthStart);
        DatePicker toPicker = new DatePicker(weeklyBtn.isSelected() ? weekEnd : monthEnd);

        weeklyBtn.selectedProperty().addListener((obs, oldVal, selected) -> {
            if (selected) {
                fromPicker.setValue(weekStart);
                toPicker.setValue(weekEnd);
            }
        });
        monthlyBtn.selectedProperty().addListener((obs, oldVal, selected) -> {
            if (selected) {
                fromPicker.setValue(monthStart);
                toPicker.setValue(monthEnd);
            }
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(12));
        form.add(new Label("유형:"), 0, 0);
        form.add(new HBox(10, weeklyBtn, monthlyBtn), 1, 0);
        form.add(new Label("시작일:"), 0, 1);
        form.add(fromPicker, 1, 1);
        form.add(new Label("종료일:"), 0, 2);
        form.add(toPicker, 1, 2);
        dialog.getDialogPane().setContent(form);

        dialog.setResultConverter(btn -> {
            if (btn != generateBtn) return null;
            LocalDate from = fromPicker.getValue();
            LocalDate to = toPicker.getValue();
            if (from == null || to == null) {
                Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "시작일/종료일을 모두 선택하세요.").show());
                return null;
            }
            if (from.isAfter(to)) {
                Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "시작일은 종료일보다 늦을 수 없습니다.").show());
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", monthlyBtn.isSelected() ? "MONTHLY" : "WEEKLY");
            result.put("from", from);
            result.put("to", to);
            return result;
        });

        dialog.showAndWait().ifPresent(result -> {
            String type = String.valueOf(result.get("type"));
            LocalDate from = (LocalDate) result.get("from");
            LocalDate to = (LocalDate) result.get("to");
            handleDownloadReport(type, from, to);
        });
    }

    private void handleDownloadReport(String type, LocalDate fromDate, LocalDate toDate) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(type + " 보고서 PDF 저장");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        String defaultName = "WEEKLY".equals(type) ? "주간보고서" : "월간보고서";
        String periodName = (fromDate != null && toDate != null)
                ? fromDate + "_" + toDate
                : String.valueOf(LocalDate.now());
        fileChooser.setInitialFileName(defaultName + "_" + periodName + ".pdf");

        File file = fileChooser.showSaveDialog(sceneSupplier.get().getWindow());
        if (file == null) return;

        javafx.scene.Scene scene = sceneSupplier.get();
        scene.setCursor(Cursor.WAIT);

        String url = apiBaseUrl + "/reports/pdf?type=" + type
            + (fromDate != null ? "&fromDate=" + fromDate : "")
            + (toDate != null ? "&toDate=" + toDate : "");
        apiClient.getBytes(url)
                .thenAccept(bytes -> {
                    Platform.runLater(() -> scene.setCursor(Cursor.DEFAULT));
                    try {
                        Files.write(file.toPath(), bytes);
                        Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                                "보고서가 저장되었습니다:\n" + file.getAbsolutePath()).show());
                    } catch (IOException e) {
                        log.warn("Failed to save report file {}", file.getAbsolutePath(), e);
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "파일 저장 중 오류가 발생했습니다.").show());
                    }
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        scene.setCursor(Cursor.DEFAULT);
                        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                        String msg = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                                ? "알 수 없는 오류"
                                : cause.getMessage();
                        new Alert(Alert.AlertType.ERROR, "보고서 생성 실패: " + msg).show();
                    });
                    return null;
                });
    }
}


