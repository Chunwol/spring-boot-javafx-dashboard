package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.model.dashboard.IpRangeItem;
import com.example.frontend.model.dashboard.TeacherDeviceItem;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Cursor;
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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.fxml.FXMLLoader;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardManagementModule {
    private static final Logger log = LoggerFactory.getLogger(DashboardManagementModule.class);
    private static final DateTimeFormatter PERF_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
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
    private Label installKeyLabel;
    private Label perfUpdatedAtLabel;
    private Label perfPcOnlineLabel;
    private Label perfPcRatioLabel;
    private Label perfCommandQueueLabel;
    private Label perfCommandFailureLabel;
    private Label perfStaleLabel;
    private Label perfTokenLabel;
    private Label perfCacheLabel;
    private Label perfQueuePressureLabel;
    private Label perfStalePressureLabel;
    private final ClassroomSummaryDto allClassroomOption = createAllClassroomOption();
    private List<PcStatusDto> cachedBrokenPcs = List.of();

    public DashboardManagementModule(String apiBaseUrl,
                                     Supplier<javafx.scene.Scene> sceneSupplier) {
        this.apiBaseUrl = apiBaseUrl;
        this.sceneSupplier = sceneSupplier;
        this.apiClient = ApiClient.getInstance();
    }

    public Node createView() {
        ScrollPane scrollPane;
        StackPane metricsSlot, agentSlot, ipRangeSlot, teacherSlot, brokenPcSlot, reportSlot;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-view.fxml"));
            scrollPane   = loader.load();
            metricsSlot  = (StackPane) loader.getNamespace().get("metricsSlot");
            agentSlot    = (StackPane) loader.getNamespace().get("agentSlot");
            ipRangeSlot  = (StackPane) loader.getNamespace().get("ipRangeSlot");
            teacherSlot  = (StackPane) loader.getNamespace().get("teacherSlot");
            brokenPcSlot = (StackPane) loader.getNamespace().get("brokenPcSlot");
            reportSlot   = (StackPane) loader.getNamespace().get("reportSlot");
        } catch (Exception e) {
            throw new IllegalStateException("관리 뷰 FXML 로딩 실패", e);
        }

        metricsSlot.getChildren().setAll(buildPerformanceBox());
        agentSlot.getChildren().setAll(buildAgentInstallBox());
        ipRangeSlot.getChildren().setAll(buildIpRangeBox());
        teacherSlot.getChildren().setAll(buildTeacherDeviceBox());
        brokenPcSlot.getChildren().setAll(buildBrokenPcBox());
        reportSlot.getChildren().setAll(buildReportBox());

        reloadData();

        return scrollPane;
    }

    public void reloadData() {
        loadPerformanceSnapshot();
        loadIpRangesAndGroups();
        loadAgentList();
        loadInstallKey();
        loadBrokenPcs();
    }

    @SuppressWarnings("unchecked")
    private VBox buildAgentInstallBox() {
        VBox box;
        Button copyKeyBtn;
        Button refreshKeyBtn;
        Button downloadConfigBtn;
        Button refreshAgentBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-agent-section.fxml"));
            box             = loader.load();
            installKeyLabel = (Label)  loader.getNamespace().get("installKeyLabel");
            copyKeyBtn      = (Button) loader.getNamespace().get("copyKeyBtn");
            refreshKeyBtn   = (Button) loader.getNamespace().get("refreshKeyBtn");
            downloadConfigBtn = (Button) loader.getNamespace().get("downloadConfigBtn");
            refreshAgentBtn   = (Button) loader.getNamespace().get("refreshAgentBtn");
            agentTable        = (TableView<Map<String, Object>>) loader.getNamespace().get("agentTable");
        } catch (Exception e) {
            throw new IllegalStateException("에이전트 섹션 FXML 로딩 실패", e);
        }

        // ── 설치 키 행 버튼 동작 연결 ──
        final Button copyKeyBtnFinal = copyKeyBtn;
        copyKeyBtn.setOnAction(e -> {
            String key = installKeyLabel.getText();
            if (!key.isBlank() && !key.equals("불러오는 중...") && !key.equals("조회 실패")) {
                ClipboardContent content = new ClipboardContent();
                content.putString(key);
                Clipboard.getSystemClipboard().setContent(content);
                copyKeyBtnFinal.setText("복사됨!");
                javafx.animation.PauseTransition reset =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                reset.setOnFinished(done -> copyKeyBtnFinal.setText("복사"));
                reset.play();
            }
        });
        refreshKeyBtn.setOnAction(e -> loadInstallKey());
        downloadConfigBtn.setOnAction(e -> downloadConfigJson());
        refreshAgentBtn.setOnAction(e -> loadAgentList());

        // ── 컬럼 바인딩 (FXML에서 정의된 컬럼 사용) ──

        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> pcNameCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(0);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> ipCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(1);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> macCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(2);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> statusCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(3);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> tokenCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(4);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> orphanCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(5);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, String> lastSeenCol = (TableColumn<Map<String, Object>, String>) agentTable.getColumns().get(6);
        @SuppressWarnings("unchecked")
        TableColumn<Map<String, Object>, Void> actionCol = (TableColumn<Map<String, Object>, Void>) agentTable.getColumns().get(7);

        pcNameCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
        macCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("macAddress"))));
        lastSeenCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("lastSeen"))));

        statusCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("status"))));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); getStyleClass().removeAll("status-online", "status-offline"); return; }
                setText(s);
                getStyleClass().removeAll("status-online", "status-offline");
                getStyleClass().add("ONLINE".equalsIgnoreCase(s) ? "status-online" : "status-offline");
            }
        });

        tokenCol.setCellValueFactory(d -> {
            Object active = d.getValue().get("tokenActive");
            boolean isActive = Boolean.TRUE.equals(active);
            return new SimpleStringProperty(isActive ? "활성" : "폐기됨");
        });
        tokenCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); getStyleClass().removeAll("status-token-active", "status-token-revoked"); return; }
                setText(s);
                getStyleClass().removeAll("status-token-active", "status-token-revoked");
                getStyleClass().add("활성".equals(s) ? "status-token-active" : "status-token-revoked");
            }
        });

        orphanCol.setCellValueFactory(d -> {
            boolean orphan = Boolean.TRUE.equals(d.getValue().get("orphan"));
            return new SimpleStringProperty(orphan ? "고아토큰" : "정상");
        });
        orphanCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); getStyleClass().removeAll("status-orphan", "status-normal"); return; }
                setText(s);
                getStyleClass().removeAll("status-orphan", "status-normal");
                getStyleClass().add("고아토큰".equals(s) ? "status-orphan" : "status-normal");
            }
        });

        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button revokeBtn = new Button("토큰폐기");
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(4, revokeBtn, deleteBtn);
            {
                revokeBtn.getStyleClass().addAll("btn-warning", "btn-sm");
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");
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
        VBox ipBox;
        TextField startIpField;
        TextField endIpField;
        TextField descField;
        Button addIpRangeBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-ip-range-section.fxml"));
            ipBox         = loader.load();
            groupComboBox = (ComboBox<ClassroomSummaryDto>) loader.getNamespace().get("groupComboBox");
            startIpField  = (TextField) loader.getNamespace().get("startIpField");
            endIpField    = (TextField) loader.getNamespace().get("endIpField");
            descField     = (TextField) loader.getNamespace().get("descField");
            addIpRangeBtn = (Button)    loader.getNamespace().get("addIpRangeBtn");
            ipRangeTable  = (TableView<IpRangeItem>) loader.getNamespace().get("ipRangeTable");
        } catch (Exception e) {
            throw new IllegalStateException("IP 대역 섹션 FXML 로딩 실패", e);
        }
        groupComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(ClassroomSummaryDto o) { return o != null ? o.getGroupName() : ""; }
            @Override public ClassroomSummaryDto fromString(String s) { return null; }
        });
        groupComboBox.valueProperty().addListener((obs, oldVal, newVal) -> loadIpRangesForGroup(newVal));

        final TextField startIpFinal = startIpField;
        final TextField endIpFinal   = endIpField;
        final TextField descFinal    = descField;
        addIpRangeBtn.setOnAction(e -> addIpRange(startIpFinal, endIpFinal, descFinal));

        ipRangeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        @SuppressWarnings("unchecked")
        TableColumn<IpRangeItem, Void> actionCol = (TableColumn<IpRangeItem, Void>) ipRangeTable.getColumns().get(5);
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(deleteBtn);
            {
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");
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
        return ipBox;
    }

    @SuppressWarnings("unchecked")
    private VBox buildTeacherDeviceBox() {
        VBox teacherBox;
        TextField teacherIp;
        TextField teacherMac;
        TextField teacherDesc;
        Button addTeacherBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-teacher-section.fxml"));
            teacherBox         = loader.load();
            teacherGroupCombo  = (ComboBox<ClassroomSummaryDto>) loader.getNamespace().get("teacherGroupCombo");
            teacherIp          = (TextField) loader.getNamespace().get("teacherIp");
            teacherMac         = (TextField) loader.getNamespace().get("teacherMac");
            teacherDesc        = (TextField) loader.getNamespace().get("teacherDesc");
            addTeacherBtn      = (Button)    loader.getNamespace().get("addTeacherBtn");
            teacherDeviceTable = (TableView<TeacherDeviceItem>) loader.getNamespace().get("teacherDeviceTable");
        } catch (Exception e) {
            throw new IllegalStateException("교사 PC 섹션 FXML 로딩 실패", e);
        }
        teacherGroupCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ClassroomSummaryDto o) { return o != null ? o.getGroupName() : ""; }
            @Override public ClassroomSummaryDto fromString(String s) { return null; }
        });
        teacherGroupCombo.setOnAction(e -> loadTeacherDevices());

        final TextField teacherIpFinal   = teacherIp;
        final TextField teacherMacFinal  = teacherMac;
        final TextField teacherDescFinal = teacherDesc;
        addTeacherBtn.setOnAction(e -> addTeacherDevice(teacherIpFinal, teacherMacFinal, teacherDescFinal));

        teacherDeviceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        @SuppressWarnings("unchecked")
        TableColumn<TeacherDeviceItem, Void> teacherActionCol = (TableColumn<TeacherDeviceItem, Void>) teacherDeviceTable.getColumns().get(5);
        teacherActionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            private final HBox box = new HBox(deleteBtn);
            {
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");
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
        return teacherBox;
    }

    @SuppressWarnings("unchecked")
    private VBox buildBrokenPcBox() {
        VBox brokenPcVBox;
        TextField brokenIpField;
        TextField brokenMacField;
        TextField brokenNoteField;
        Button brokenRegisterBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-broken-pc-section.fxml"));
            brokenPcVBox      = loader.load();
            brokenGroupCombo  = (ComboBox<ClassroomSummaryDto>) loader.getNamespace().get("brokenGroupCombo");
            brokenIpField     = (TextField) loader.getNamespace().get("brokenIpField");
            brokenMacField    = (TextField) loader.getNamespace().get("brokenMacField");
            brokenNoteField   = (TextField) loader.getNamespace().get("brokenNoteField");
            brokenRegisterBtn = (Button)    loader.getNamespace().get("brokenRegisterBtn");
            brokenPcTable     = (TableView<PcStatusDto>) loader.getNamespace().get("brokenPcTable");
        } catch (Exception e) {
            throw new IllegalStateException("고장 PC 섹션 FXML 로딩 실패", e);
        }
        brokenGroupCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ClassroomSummaryDto o) { return o != null ? o.getGroupName() : ""; }
            @Override public ClassroomSummaryDto fromString(String s) { return null; }
        });
        brokenGroupCombo.setOnAction(e -> applyBrokenPcFilter());

        final TextField brokenIpFinal   = brokenIpField;
        final TextField brokenMacFinal  = brokenMacField;
        final TextField brokenNoteFinal = brokenNoteField;
        brokenRegisterBtn.setOnAction(e -> markBrokenPc(brokenIpFinal, brokenMacFinal, brokenNoteFinal));
        configureBrokenPcTable(brokenPcTable);
        return brokenPcVBox;
    }

    @SuppressWarnings("unchecked")
    private void configureBrokenPcTable(TableView<PcStatusDto> table) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Wire CellValueFactory on FXML-defined columns
        TableColumn<PcStatusDto, String> macCol    = (TableColumn<PcStatusDto, String>) table.getColumns().get(0);
        TableColumn<PcStatusDto, String> ipCol     = (TableColumn<PcStatusDto, String>) table.getColumns().get(1);
        TableColumn<PcStatusDto, String> groupCol  = (TableColumn<PcStatusDto, String>) table.getColumns().get(2);
        TableColumn<PcStatusDto, String> statusCol = (TableColumn<PcStatusDto, String>) table.getColumns().get(3);
        TableColumn<PcStatusDto, String> noteCol   = (TableColumn<PcStatusDto, String>) table.getColumns().get(4);

        macCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getMacAddress())));
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getIpAddress())));
        groupCol.setCellValueFactory(d -> new SimpleStringProperty(resolveGroupName(d.getValue().getGroupId())));
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getStatus())));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().remove("status-broken");
                    return;
                }
                setText(item);
                if (!getStyleClass().contains("status-broken")) getStyleClass().add("status-broken");
            }
        });
        noteCol.setCellValueFactory(d -> new SimpleStringProperty(safe(d.getValue().getBrokenNote())));

        @SuppressWarnings("unchecked")
        TableColumn<PcStatusDto, Void> actionCol = (TableColumn<PcStatusDto, Void>) table.getColumns().get(5);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("사유 수정");
            private final Button clearBtn = new Button("해제");
            private final HBox box = new HBox(6, editBtn, clearBtn);
            {
                editBtn.getStyleClass().addAll("btn-warning", "btn-sm");
                clearBtn.getStyleClass().addAll("btn-danger", "btn-sm");
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
    }

    private VBox buildReportBox() {
        VBox reportBox;
        Button weekBtn;
        Button monthBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-report-section.fxml"));
            reportBox = loader.load();
            weekBtn   = (Button) loader.getNamespace().get("weekBtn");
            monthBtn  = (Button) loader.getNamespace().get("monthBtn");
        } catch (Exception e) {
            throw new IllegalStateException("보고서 섹션 FXML 로딩 실패", e);
        }
        weekBtn.setOnAction(e -> showReportDateDialog("WEEKLY"));
        monthBtn.setOnAction(e -> showReportDateDialog("MONTHLY"));
        return reportBox;
    }

    private VBox buildPerformanceBox() {
        VBox box;
        Button refreshBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-performance-section.fxml"));
            box = loader.load();
            perfUpdatedAtLabel = (Label) loader.getNamespace().get("perfUpdatedAtLabel");
            perfPcOnlineLabel = (Label) loader.getNamespace().get("perfPcOnlineLabel");
            perfPcRatioLabel = (Label) loader.getNamespace().get("perfPcRatioLabel");
            perfCommandQueueLabel = (Label) loader.getNamespace().get("perfCommandQueueLabel");
            perfCommandFailureLabel = (Label) loader.getNamespace().get("perfCommandFailureLabel");
            perfStaleLabel = (Label) loader.getNamespace().get("perfStaleLabel");
            perfTokenLabel = (Label) loader.getNamespace().get("perfTokenLabel");
            perfCacheLabel = (Label) loader.getNamespace().get("perfCacheLabel");
            perfQueuePressureLabel = (Label) loader.getNamespace().get("perfQueuePressureLabel");
            perfStalePressureLabel = (Label) loader.getNamespace().get("perfStalePressureLabel");
            refreshBtn = (Button) loader.getNamespace().get("perfRefreshBtn");
        } catch (Exception e) {
            throw new IllegalStateException("운영 메트릭 섹션 FXML 로딩 실패", e);
        }

        refreshBtn.setOnAction(e -> loadPerformanceSnapshot());
        return box;
    }

    private void loadPerformanceSnapshot() {
        apiClient.getJson("/status/performance", new TypeReference<Map<String, Object>>() {})
                .thenAccept(snapshot -> Platform.runLater(() -> applyPerformanceSnapshot(snapshot)))
                .exceptionally(ex -> {
                    log.warn("운영 메트릭 조회 실패", ex);
                    Platform.runLater(() -> {
                        if (perfUpdatedAtLabel != null) {
                            perfUpdatedAtLabel.setText("조회 실패");
                        }
                    });
                    return null;
                });
    }

    private void applyPerformanceSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return;
        }

        Map<String, Object> pc = toMap(snapshot.get("pc"));
        Map<String, Object> command = toMap(snapshot.get("command"));
        Map<String, Object> token = toMap(snapshot.get("token"));
        Map<String, Object> cache = toMap(snapshot.get("cache"));
        Map<String, Object> pressure = toMap(snapshot.get("pressure"));

        long totalPc = toLong(pc.get("total"));
        long onlinePc = toLong(pc.get("online"));
        double onlineRatio = toDouble(pc.get("onlineRatio"));
        long queued = toLong(command.get("queued"));
        long inFlight = toLong(command.get("inFlight"));
        long completed = toLong(command.get("completed"));
        long failed = toLong(command.get("failed"));
        long staleDelivered = toLong(command.get("staleDelivered"));
        long staleAcked = toLong(command.get("staleAcked"));
        long activeToken = toLong(token.get("active"));
        long revokedToken = toLong(token.get("revoked"));
        long cacheSize = toLong(cache.get("estimatedSize"));
        double hitRate = toDouble(cache.get("hitRate"));

        if (perfUpdatedAtLabel != null) {
            perfUpdatedAtLabel.setText(formatSnapshotTime(snapshot.get("timestamp")));
        }
        if (perfPcOnlineLabel != null) {
            perfPcOnlineLabel.setText(onlinePc + " / " + totalPc);
        }
        if (perfPcRatioLabel != null) {
            perfPcRatioLabel.setText(String.format("온라인 비율 %.1f%%", onlineRatio));
        }
        if (perfCommandQueueLabel != null) {
            perfCommandQueueLabel.setText("대기 " + queued + " | 진행 " + inFlight);
        }
        if (perfCommandFailureLabel != null) {
            perfCommandFailureLabel.setText("완료 " + completed + " | 실패 " + failed);
        }
        if (perfStaleLabel != null) {
            perfStaleLabel.setText("전달지연 " + staleDelivered + " | ACK지연 " + staleAcked);
        }
        if (perfTokenLabel != null) {
            perfTokenLabel.setText("활성 " + activeToken + " | 폐기 " + revokedToken);
        }
        if (perfCacheLabel != null) {
            perfCacheLabel.setText(String.format("hit %.1f%% | size %d", hitRate * 100.0, cacheSize));
        }

        String queuePressure = normalizePressure(pressure.get("queuePressure"));
        String stalePressure = normalizePressure(pressure.get("stalePressure"));
        if (perfQueuePressureLabel != null) {
            perfQueuePressureLabel.setText(queuePressure);
            applyPressureStyle(perfQueuePressureLabel, queuePressure);
        }
        if (perfStalePressureLabel != null) {
            perfStalePressureLabel.setText(stalePressure);
            applyPressureStyle(perfStalePressureLabel, stalePressure);
        }
    }

    private void applyPressureStyle(Label label, String pressure) {
        if (label == null) {
            return;
        }
        label.getStyleClass().removeAll("status-online", "status-warning", "status-failed");
        switch (pressure) {
            case "HIGH" -> label.getStyleClass().add("status-failed");
            case "MEDIUM" -> label.getStyleClass().add("status-warning");
            default -> label.getStyleClass().add("status-online");
        }
    }

    private String normalizePressure(Object value) {
        if (value == null) {
            return "LOW";
        }
        String normalized = value.toString().trim().toUpperCase();
        return switch (normalized) {
            case "HIGH", "MEDIUM", "LOW" -> normalized;
            default -> "LOW";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String formatSnapshotTime(Object timestamp) {
        if (timestamp == null) {
            return "-";
        }
        if (timestamp instanceof LocalDateTime localDateTime) {
            return localDateTime.format(PERF_TIME_FORMAT);
        }
        String raw = timestamp.toString();
        try {
            return LocalDateTime.parse(raw).format(PERF_TIME_FORMAT);
        } catch (Exception ignore) {
            return raw;
        }
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
                })
                .exceptionally(ex -> {
                    log.warn("교사 PC 목록 병합 실패", ex);
                    return null;
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

        VBox root;
        Label headerLabel;
        TableView<PcStatusDto> table;
        TableColumn<PcStatusDto, String> nameCol, ipCol, statusCol;
        TableColumn<PcStatusDto, Void> actionCol;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-individual-control-dialog.fxml"));
            root        = loader.load();
            headerLabel = (Label) loader.getNamespace().get("headerLabel");
            table       = (TableView<PcStatusDto>) loader.getNamespace().get("controlTable");
            nameCol     = (TableColumn<PcStatusDto, String>) table.getColumns().get(0);
            ipCol       = (TableColumn<PcStatusDto, String>) table.getColumns().get(1);
            statusCol   = (TableColumn<PcStatusDto, String>) table.getColumns().get(2);
            actionCol   = (TableColumn<PcStatusDto, Void>)   table.getColumns().get(3);
        } catch (Exception e) {
            throw new IllegalStateException("개별 PC 제어 다이얼로그 FXML 로딩 실패", e);
        }

        headerLabel.setText(title);

        nameCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getPcName() != null ? d.getValue().getPcName() : "-"));
        ipCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getIpAddress() != null ? d.getValue().getIpAddress() : "-"));
        statusCol.setCellValueFactory(d -> {
            String s = d.getValue().getStatus();
            if (s == null || s.isBlank()) s = d.getValue().isOnline() ? "ONLINE" : "OFFLINE";
            return new SimpleStringProperty(s);
        });
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-online", "status-offline");
                if (empty || status == null) { setText(null); return; }
                setText(status);
                getStyleClass().add("ONLINE".equalsIgnoreCase(status)
                    ? "status-online"
                    : "status-offline");
            }
        });
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button shutdownBtn = new Button("종료");
            private final Button rebootBtn   = new Button("재시작");
            private final Button wolBtn      = new Button("켜기(WOL)");
            private final HBox box           = new HBox(4, shutdownBtn, rebootBtn, wolBtn);
            {
                shutdownBtn.getStyleClass().addAll("btn-danger", "btn-sm");
                rebootBtn.getStyleClass().addAll("btn-warning", "btn-sm");
                wolBtn.getStyleClass().addAll("btn-success", "btn-sm");
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

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

    private Button makeCommandBtn(String label, String cssClass, String ip, String commandType) {
        Button btn = new Button(label);
        btn.getStyleClass().addAll(cssClass, "btn-sm");
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


    private void deleteIpRange(IpRangeItem item) {
        apiClient.delete("/ip-ranges/" + item.getId())
                .thenAccept(resp -> Platform.runLater(this::loadIpRangesAndGroups))
                .exceptionally(ex -> {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "삭제 실패: " + errorMessage(ex)).show());
                    return null;
                });
    }

    // ─── 보고서 ────────────────────────────────────────────────

    private String errorMessage(Throwable ex) {
        return ApiClient.userFacingErrorMessage(ex, "요청을 처리하지 못했습니다.");
    }

    private boolean isAuthExpired(Throwable ex) {
        return ApiClient.isUnauthorized(ex);
    }

    private void showReportDateDialog(String defaultType) {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("보고서 생성");
        dialog.setHeaderText("보고서 유형과 기간을 선택하세요");
        ButtonType generateBtn = new ButtonType("생성", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(generateBtn, ButtonType.CANCEL);

        GridPane form;
        RadioButton weeklyBtn, monthlyBtn;
        DatePicker fromPicker, toPicker;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/mgmt-report-date-dialog-content.fxml"));
            form        = loader.load();
            weeklyBtn   = (RadioButton) loader.getNamespace().get("weeklyBtn");
            monthlyBtn  = (RadioButton) loader.getNamespace().get("monthlyBtn");
            fromPicker  = (DatePicker)  loader.getNamespace().get("fromPicker");
            toPicker    = (DatePicker)  loader.getNamespace().get("toPicker");
        } catch (Exception e) {
            throw new IllegalStateException("보고서 날짜 다이얼로그 FXML 로딩 실패", e);
        }

        ToggleGroup typeGroup = new ToggleGroup();
        weeklyBtn.setToggleGroup(typeGroup);
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

        fromPicker.setValue(weeklyBtn.isSelected() ? weekStart : monthStart);
        toPicker.setValue(weeklyBtn.isSelected() ? weekEnd : monthEnd);

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
