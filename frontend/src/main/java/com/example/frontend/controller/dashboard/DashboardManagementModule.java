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
import javafx.scene.layout.HBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

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
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final Supplier<javafx.scene.Scene> sceneSupplier;
    private final ApiClient apiClient;

    private ComboBox<ClassroomSummaryDto> groupComboBox;
    private ComboBox<ClassroomSummaryDto> teacherGroupCombo;
    private TableView<IpRangeItem> ipRangeTable;
    private TableView<TeacherDeviceItem> teacherDeviceTable;
    private TableView<Map<String, Object>> agentTable;
    private Label installKeyLabel;

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

        VBox agentBox = buildAgentInstallBox();
        VBox scriptBox = buildScriptBox();
        VBox ipBox = buildIpRangeBox();
        VBox teacherBox = buildTeacherBox();
        VBox reportBox = buildReportBox();

        container.getChildren().addAll(agentBox, scriptBox, ipBox, teacherBox, reportBox);
        loadIpRangesAndGroups();
        loadAgentList();
        loadInstallKey();
        return new ScrollPane(container);
    }

    @SuppressWarnings("unchecked")
    private VBox buildAgentInstallBox() {
        Label title = new Label("에이전트 설치 관리");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        // ── Install Key 행 ──
        Label keyLabel = new Label("설치키 (Install-Key):");
        keyLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        installKeyLabel = new Label("불러오는 중...");
        installKeyLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 13px; -fx-text-fill: #1e40af; " +
                "-fx-background-color: #eff6ff; -fx-padding: 4 10 4 10; -fx-background-radius: 4;");
        Button copyKeyBtn = new Button("복사");
        copyKeyBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        copyKeyBtn.setOnAction(e -> {
            String key = installKeyLabel.getText();
            if (!key.isBlank() && !key.equals("불러오는 중...") && !key.equals("조회 실패")) {
                ClipboardContent content = new ClipboardContent();
                content.putString(key);
                Clipboard.getSystemClipboard().setContent(content);
                copyKeyBtn.setText("복사됨!");
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2))
                        .play();
                copyKeyBtn.setOnAction(ev -> {
                    ClipboardContent c2 = new ClipboardContent();
                    c2.putString(installKeyLabel.getText());
                    Clipboard.getSystemClipboard().setContent(c2);
                });
                copyKeyBtn.setText("복사됨!");
            }
        });
        Button refreshKeyBtn = new Button("↺");
        refreshKeyBtn.setStyle("-fx-background-color: #6b7280; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        refreshKeyBtn.setOnAction(e -> loadInstallKey());

        // ── config.json 다운로드 ──
        Button downloadConfigBtn = new Button("config.json 템플릿 다운로드");
        downloadConfigBtn.setStyle("-fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        downloadConfigBtn.setOnAction(e -> downloadConfigJson());

        HBox keyRow = new HBox(8, keyLabel, installKeyLabel, copyKeyBtn, refreshKeyBtn, downloadConfigBtn);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        // ── 등록 에이전트 테이블 ──
        Label tableTitle = new Label("등록된 에이전트 목록");
        tableTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Button refreshAgentBtn = new Button("새로고침");
        refreshAgentBtn.setStyle("-fx-background-color: #0f766e; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 4;");
        refreshAgentBtn.setOnAction(e -> loadAgentList());
        HBox tableHeader = new HBox(8, tableTitle, refreshAgentBtn);
        tableHeader.setAlignment(Pos.CENTER_LEFT);

        TableColumn<Map<String, Object>, String> pcNameCol = new TableColumn<>("PC명");
        pcNameCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
        pcNameCol.setPrefWidth(130);

        TableColumn<Map<String, Object>, String> ipCol = new TableColumn<>("IP");
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
        ipCol.setPrefWidth(120);

        TableColumn<Map<String, Object>, String> macCol = new TableColumn<>("MAC");
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
                        ? "-fx-text-fill: #16a34a; -fx-font-weight: bold;"
                        : "-fx-text-fill: #dc2626; -fx-font-weight: bold;");
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
                        ? "-fx-text-fill: #16a34a; -fx-font-weight: bold;"
                        : "-fx-text-fill: #9ca3af;");
            }
        });
        tokenCol.setPrefWidth(70);

        TableColumn<Map<String, Object>, String> lastSeenCol = new TableColumn<>("마지막 접속");
        lastSeenCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("lastSeen"))));
        lastSeenCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, Void> actionCol = new TableColumn<>("관리");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button revokeBtn = new Button("토큰폐기");
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(4, revokeBtn, deleteBtn);
            {
                revokeBtn.setStyle("-fx-background-color: #d97706; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                deleteBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
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
        agentTable.getColumns().addAll(pcNameCol, ipCol, macCol, statusCol, tokenCol, lastSeenCol, actionCol);
        agentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        agentTable.setPrefHeight(260);
        agentTable.setPlaceholder(new Label("등록된 에이전트가 없습니다."));
        agentTable.setStyle("-fx-background-color: white; -fx-font-size: 12px;");

        VBox box = new VBox(10, title, keyRow, tableHeader, agentTable);
        box.setStyle("-fx-border-color: #3b82f6; -fx-border-width: 2; -fx-padding: 15; -fx-background-color: #f0f7ff; -fx-background-radius: 6;");
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
                    agentTable.getItems().setAll(list != null ? list : List.of());
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
        String serverUrl = apiBaseUrl.replaceAll("/api/v1$", "");
        String json = "{\n" +
                "  \"serverUrl\": \"" + serverUrl + "\",\n" +
                "  \"installKey\": \"" + key + "\",\n" +
                "  \"agentToken\": \"\",\n" +
                "  \"heartbeatIntervalSec\": 120,\n" +
                "  \"jitterSec\": 30\n" +
                "}\n";
        FileChooser fc = new FileChooser();
        fc.setTitle("config.json 저장");
        fc.setInitialFileName("config.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = fc.showSaveDialog(sceneSupplier.get().getWindow());
        if (file == null) return;
        try {
            java.nio.file.Files.writeString(file.toPath(), json);
            new Alert(Alert.AlertType.INFORMATION, "config.json 저장 완료:\n" + file.getAbsolutePath()).show();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "저장 실패: " + e.getMessage()).show();
        }
    }

    private VBox buildScriptBox() {
        Label scriptTitle = new Label("원격 명령 및 스크립트 배포");
        scriptTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        Button uploadPs1Btn = new Button("PowerShell(.ps1) 파일 선택 및 배포...");
        uploadPs1Btn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-size: 14px;");
        uploadPs1Btn.setOnAction(e -> handleScriptUploadAndRun());

        VBox scriptBox = new VBox(10, scriptTitle, uploadPs1Btn);
        scriptBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return scriptBox;
    }

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

        TableColumn<IpRangeItem, Long> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setMaxWidth(60);
        TableColumn<IpRangeItem, String> groupNameCol = new TableColumn<>("강의실");
        groupNameCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<IpRangeItem, String> startCol = new TableColumn<>("Start IP");
        startCol.setCellValueFactory(new PropertyValueFactory<>("startIp"));
        TableColumn<IpRangeItem, String> endCol = new TableColumn<>("End IP");
        endCol.setCellValueFactory(new PropertyValueFactory<>("endIp"));
        TableColumn<IpRangeItem, String> descCol = new TableColumn<>("Desc");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<IpRangeItem, Void> actionCol = new TableColumn<>("액션");
        actionCol.setMinWidth(220);
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button controlBtn = new Button("▶ 개별 제어");
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(6, controlBtn, deleteBtn);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                controlBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white;");
                controlBtn.setOnAction(event -> {
                    IpRangeItem item = getTableView().getItems().get(getIndex());
                    showIndividualControlDialog(item);
                });
                deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
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
        ipBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return ipBox;
    }

    private VBox buildTeacherBox() {
        Label teacherTitle = new Label("교사 PC 관리");
        teacherTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        teacherGroupCombo = buildClassroomComboBox();
        teacherGroupCombo.setPromptText("강의실 선택");
        teacherGroupCombo.setOnAction(e -> loadTeacherDevices());

        TextField teacherIp = new TextField();
        teacherIp.setPromptText("교사 IP");
        TextField teacherMac = new TextField();
        teacherMac.setPromptText("교사 MAC");
        TextField teacherDesc = new TextField();
        teacherDesc.setPromptText("설명");
        Button addTeacherBtn = new Button("교사 등록");
        addTeacherBtn.setOnAction(e -> addTeacherDevice(teacherIp, teacherMac, teacherDesc));

        teacherDeviceTable = new TableView<>();
        teacherDeviceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<TeacherDeviceItem, Long> teacherIdCol = new TableColumn<>("ID");
        teacherIdCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        TableColumn<TeacherDeviceItem, String> teacherGroupCol = new TableColumn<>("강의실");
        teacherGroupCol.setCellValueFactory(new PropertyValueFactory<>("groupName"));
        TableColumn<TeacherDeviceItem, String> teacherIpCol = new TableColumn<>("IP");
        teacherIpCol.setCellValueFactory(new PropertyValueFactory<>("ipAddress"));
        TableColumn<TeacherDeviceItem, String> teacherMacCol = new TableColumn<>("MAC");
        teacherMacCol.setCellValueFactory(new PropertyValueFactory<>("macAddress"));
        TableColumn<TeacherDeviceItem, String> teacherDescCol = new TableColumn<>("설명");
        teacherDescCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        TableColumn<TeacherDeviceItem, Void> teacherActionCol = new TableColumn<>("액션");
        teacherActionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            {
                deleteBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
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
                setGraphic(empty ? null : deleteBtn);
            }
        });
        teacherDeviceTable.getColumns().add(teacherIdCol);
        teacherDeviceTable.getColumns().add(teacherGroupCol);
        teacherDeviceTable.getColumns().add(teacherIpCol);
        teacherDeviceTable.getColumns().add(teacherMacCol);
        teacherDeviceTable.getColumns().add(teacherDescCol);
        teacherDeviceTable.getColumns().add(teacherActionCol);

        Label brokenTitle = new Label("고장 PC 등록");
        brokenTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        TextField brokenMacField = new TextField();
        brokenMacField.setPromptText("MAC 주소");
        TextField brokenNoteField = new TextField();
        brokenNoteField.setPromptText("고장 사유");
        Button brokenRegisterBtn = new Button("고장 등록");
        brokenRegisterBtn.setOnAction(e -> markBrokenPc(brokenMacField, brokenNoteField));

        HBox brokenBox = new HBox(10, brokenMacField, brokenNoteField, brokenRegisterBtn);
        HBox teacherForm = new HBox(10, teacherGroupCombo, teacherIp, teacherMac, teacherDesc, addTeacherBtn);
        VBox teacherBox = new VBox(10, teacherTitle, teacherForm, teacherDeviceTable, brokenTitle, brokenBox);
        teacherBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return teacherBox;
    }

    private VBox buildReportBox() {
        Label reportTitle = new Label("주간/월간 보고서");
        reportTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        HBox reportBtns = new HBox(15);
        Button weekBtn = new Button("주간 보고서 생성");
        weekBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        weekBtn.setOnAction(e -> handleDownloadReport("WEEKLY"));

        Button monthBtn = new Button("월간 보고서 생성");
        monthBtn.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15;");
        monthBtn.setOnAction(e -> handleDownloadReport("MONTHLY"));

        reportBtns.getChildren().addAll(weekBtn, monthBtn);
        VBox reportBox = new VBox(10, reportTitle, reportBtns);
        reportBox.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-padding: 15; -fx-background-color: white;");
        return reportBox;
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

    private void loadIpRangesAndGroups() {
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
            .thenAccept(classrooms -> {
                try {
                    Platform.runLater(() -> {
                        groupComboBox.getItems().setAll(classrooms);
                        teacherGroupCombo.getItems().setAll(classrooms);
                        if (!classrooms.isEmpty()) {
                            teacherGroupCombo.getSelectionModel().selectFirst();
                            loadTeacherDevices();
                        }
                    });
                    // 초기에는 전체 IP 대역 로드
                    loadIpRangesForGroup(null);
                } catch (Exception e) {
                    log.warn("Failed while preparing classroom list", e);
                }
            })
            .exceptionally(ex -> {
                log.warn("Failed to load classrooms", ex);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "강의실 목록 조회 실패: " + errorMessage(ex)).show());
                return null;
            });
    }

    /** 선택한 강의실의 IP 대역만 조회. group == null 이면 전체. */
    private void loadIpRangesForGroup(ClassroomSummaryDto group) {
        String path = (group != null)
                ? "/ip-ranges?groupId=" + group.getGroupId()
                : "/ip-ranges";
        apiClient.getPagedList(path, new TypeReference<List<IpRangeItem>>() {})
                .thenAccept(items -> {
                    if (group != null) {
                        items.forEach(item -> item.setGroupName(group.getGroupName()));
                    }
                    Platform.runLater(() -> ipRangeTable.getItems().setAll(items));
                })
                .exceptionally(ex -> {
                    log.warn("Failed to load IP ranges for group {}", group, ex);
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "IP 대역 목록 조회 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void loadTeacherDevices() {
        ClassroomSummaryDto selected = teacherGroupCombo.getValue();
        if (selected == null) {
            return;
        }
        apiClient.getJson("/dashboard/classrooms/" + selected.getGroupId() + "/teacher-devices", new TypeReference<List<TeacherDeviceItem>>() {})
                .thenAccept(items -> {
                    items.forEach(it -> it.setGroupName(selected.getGroupName()));
                    Platform.runLater(() -> teacherDeviceTable.getItems().setAll(items));
                })
                .exceptionally(ex -> {
                    log.warn("Failed to load teacher devices for group {}", selected.getGroupId(), ex);
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "교사 PC 목록 조회 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void addTeacherDevice(TextField ipTF, TextField macTF, TextField descTF) {
        ClassroomSummaryDto selectedGroup = teacherGroupCombo.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groupId", selectedGroup.getGroupId());
            payload.put("ipAddress", ipTF.getText());
            payload.put("macAddress", macTF.getText());
            payload.put("description", descTF.getText());
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

    private void markBrokenPc(TextField macTF, TextField noteTF) {
        String mac = macTF.getText();
        if (mac == null || mac.isBlank()) {
            new Alert(Alert.AlertType.ERROR, "MAC 주소를 입력하세요.").show();
            return;
        }
        String note = noteTF.getText();
        apiClient.postJson("/dashboard/pcs/broken", Map.of("macAddress", mac, "note", note == null ? "broken" : note))
                .thenAccept(resp -> Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "고장 PC로 등록되었습니다.").show();
                    macTF.clear();
                    noteTF.clear();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "등록 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    private void addIpRange(TextField startIp, TextField endIp, TextField desc) {
        ClassroomSummaryDto selectedGroup = groupComboBox.getValue();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.ERROR, "강의실을 선택하세요.").show();
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
                        ? "-fx-text-fill: #16a34a; -fx-font-weight: bold;"
                        : "-fx-text-fill: #dc2626; -fx-font-weight: bold;");
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
                shutdownBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                rebootBtn.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
                wolBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
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
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("PC 정보를 불러오는 중..."));
        table.setStyle("-fx-background-color: white; -fx-font-size: 13px;");
        table.setPrefHeight(440);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: #f8fafc;");
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
                        .newMultipartRequest(apiBaseUrl + "/scripts", Map.of("file", file.toPath().toString()))
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

    private void handleDownloadReport(String type) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(type + " 보고서 PDF 저장");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        String defaultName = "WEEKLY".equals(type) ? "주간보고서" : "월간보고서";
        fileChooser.setInitialFileName(defaultName + "_" + LocalDate.now() + ".pdf");

        File file = fileChooser.showSaveDialog(sceneSupplier.get().getWindow());
        if (file == null) return;

        javafx.scene.Scene scene = sceneSupplier.get();
        scene.setCursor(Cursor.WAIT);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + "/reports/pdf?type=" + type))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    Platform.runLater(() -> scene.setCursor(Cursor.DEFAULT));
                    if (response.statusCode() == 200) {
                        try {
                            Files.write(file.toPath(), response.body());
                            Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                                    "보고서가 저장되었습니다:\n" + file.getAbsolutePath()).show());
                        } catch (IOException e) {
                            log.warn("Failed to save report file {}", file.getAbsolutePath(), e);
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "파일 저장 중 오류가 발생했습니다.").show());
                        }
                    } else {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "보고서 생성 실패 (상태 코드: " + response.statusCode() + ")").show());
                    }
                })
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        scene.setCursor(Cursor.DEFAULT);
                        new Alert(Alert.AlertType.ERROR, "네트워크 오류: " + e.getMessage()).show();
                    });
                    return null;
                });
    }
}


