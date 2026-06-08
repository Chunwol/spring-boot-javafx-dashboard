package com.example.frontend.controller.tab;

import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 토큰 관리 탭 — 설치키 + 신규 등록 제어 + 에이전트별 토큰 인증 상태.
 */
public class TokenMgmtTabController {

    private static final Logger log = LoggerFactory.getLogger(TokenMgmtTabController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    @FXML private Label  updatedLabel;
    @FXML private Label  installKeyLabel;
    @FXML private Label  registrationStatusLabel;
    @FXML private Button copyKeyBtn;
    @FXML private Button refreshKeyBtn;
    @FXML private Button downloadConfigBtn;
    @FXML private Button toggleRegistrationBtn;
    @FXML private Button refreshBtn;

    @FXML private TableView<Map<String, Object>>           tokenTable;
    @FXML private TableColumn<Map<String, Object>, Boolean> colSelect;
    @FXML private TableColumn<Map<String, Object>, String>  colPcName;
    @FXML private TableColumn<Map<String, Object>, String>  colIp;
    @FXML private TableColumn<Map<String, Object>, String>  colMac;
    @FXML private TableColumn<Map<String, Object>, String>  colStatus;
    @FXML private TableColumn<Map<String, Object>, String>  colToken;
    @FXML private TableColumn<Map<String, Object>, String>  colLastSeen;

    @FXML private Button revokeBtn;
    @FXML private Button deleteBtn;

    private final ApiClient api = ApiClient.getInstance();
    private final List<Map<String, Object>> allAgents = new ArrayList<>();
    /** MAC → 체크박스 선택 상태 */
    private final Map<String, SimpleBooleanProperty> checkMap = new HashMap<>();
    private String currentKey = "";
    private boolean registrationEnabled = true;

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public Node createView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/token-mgmt-tab.fxml"));
            loader.setController(this);
            return loader.load();
        } catch (Exception e) {
            throw new IllegalStateException("토큰 관리 탭 로딩 실패", e);
        }
    }

    @FXML
    public void initialize() {
        setupTable();
        wireButtons();
        load();
    }

    public void reloadData() {
        load();
    }

    // ── 테이블 설정 ───────────────────────────────────────────────────────────

    private void setupTable() {
        tokenTable.setEditable(true);
        tokenTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // 체크박스 컬럼
        colSelect.setCellValueFactory(data -> {
            String mac = str(data.getValue().get("macAddress"));
            return checkMap.computeIfAbsent(mac, k -> {
                SimpleBooleanProperty p = new SimpleBooleanProperty(false);
                p.addListener((obs, ov, nv) -> refreshActionButtons());
                return p;
            });
        });
        colSelect.setCellFactory(CheckBoxTableCell.forTableColumn(colSelect));
        colSelect.setEditable(true);
        colSelect.setSortable(false);

        // 데이터 컬럼
        colPcName.setCellValueFactory(d   -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
        colIp.setCellValueFactory(d       -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
        colMac.setCellValueFactory(d      -> new SimpleStringProperty(str(d.getValue().get("macAddress"))));
        colLastSeen.setCellValueFactory(d  -> new SimpleStringProperty(str(d.getValue().get("lastSeen"))));

        colStatus.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("status"))));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); setText(null); return; }
                Label badge = new Label(s);
                badge.getStyleClass().addAll("status-badge",
                        "ONLINE".equalsIgnoreCase(s) ? "status-badge-ok" : "status-badge-offline");
                setGraphic(badge); setText(null);
            }
        });

        colToken.setCellValueFactory(d -> {
            boolean active = Boolean.TRUE.equals(d.getValue().get("tokenActive"));
            return new SimpleStringProperty(active ? "정상" : "폐기됨");
        });
        colToken.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); setText(null); return; }
                Label badge = new Label(s);
                badge.getStyleClass().addAll("status-badge",
                        "정상".equals(s) ? "status-badge-ok" : "status-badge-offline");
                setGraphic(badge); setText(null);
            }
        });
    }

    private void refreshActionButtons() {
        boolean anyChecked = checkMap.values().stream().anyMatch(SimpleBooleanProperty::get);
        revokeBtn.setDisable(!anyChecked);
        deleteBtn.setDisable(!anyChecked);
    }

    // ── 버튼 연결 ─────────────────────────────────────────────────────────────

    private void wireButtons() {
        refreshBtn.setOnAction(e -> load());

        copyKeyBtn.setOnAction(e -> {
            if (!currentKey.isBlank() && !currentKey.equals("불러오는 중...")) {
                ClipboardContent c = new ClipboardContent();
                c.putString(currentKey);
                Clipboard.getSystemClipboard().setContent(c);
                copyKeyBtn.setText("복사됨!");
                javafx.animation.PauseTransition t =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                t.setOnFinished(ev -> copyKeyBtn.setText("설치키 복사"));
                t.play();
            }
        });

        refreshKeyBtn.setOnAction(e -> loadInstallKey());
        downloadConfigBtn.setOnAction(e -> downloadConfig());
        toggleRegistrationBtn.setOnAction(e -> toggleRegistration());

        revokeBtn.setOnAction(e -> revokeSelected());
        deleteBtn.setOnAction(e -> deleteSelected());
    }

    // ── 데이터 로드 ───────────────────────────────────────────────────────────

    private void load() {
        loadInstallKey();
        loadRegistrationStatus();
        loadAgents();
    }

    private void loadInstallKey() {
        api.getJson("/admin/install-key", new TypeReference<Map<String, Object>>() {})
                .thenAccept(resp -> Platform.runLater(() -> {
                    if (resp == null) { installKeyLabel.setText("-"); return; }
                    String key = str(resp.get("key"));
                    if (key.isBlank() || "-".equals(key)) key = str(resp.get("keyPreview"));
                    currentKey = key;
                    installKeyLabel.setText(key.isBlank() ? "-" : key);
                }))
                .exceptionally(ex -> {
                    log.warn("설치키 조회 실패: {}", ex.getMessage());
                    Platform.runLater(() -> installKeyLabel.setText("조회 실패"));
                    return null;
                });
    }

    private void loadRegistrationStatus() {
        api.getJson("/agent/registration-status", new TypeReference<Map<String, Object>>() {})
                .thenAccept(resp -> Platform.runLater(() -> {
                    boolean enabled = resp != null && Boolean.TRUE.equals(resp.get("enabled"));
                    applyRegistrationState(enabled);
                }))
                .exceptionally(ex -> {
                    log.warn("등록 상태 조회 실패: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        registrationStatusLabel.setText("—");
                        toggleRegistrationBtn.setDisable(true);
                    });
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private void loadAgents() {
        tokenTable.setPlaceholder(new Label("로딩 중..."));
        api.getPagedList("/admin/agents", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(list -> Platform.runLater(() -> {
                    tokenTable.setPlaceholder(new Label("등록된 에이전트가 없습니다."));
                    allAgents.clear();
                    checkMap.clear();
                    if (list != null) allAgents.addAll(list);
                    tokenTable.getItems().setAll(allAgents);
                    updatedLabel.setText(LocalDateTime.now().format(FMT) + " 갱신됨");
                    refreshActionButtons();
                }))
                .exceptionally(ex -> {
                    log.warn("에이전트 목록 조회 실패: {}", ex.getMessage());
                    Platform.runLater(() -> tokenTable.setPlaceholder(new Label("목록 조회 실패")));
                    return null;
                });
    }

    // ── 액션 ─────────────────────────────────────────────────────────────────

    private void toggleRegistration() {
        boolean next = !registrationEnabled;
        String msg = next ? "에이전트 신규 등록을 허용하시겠습니까?" : "에이전트 신규 등록을 차단하시겠습니까?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("등록 상태 변경");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            toggleRegistrationBtn.setDisable(true);
            api.putJson("/agent/registration-status", Map.of("enabled", next))
                    .thenRun(() -> Platform.runLater(this::loadRegistrationStatus))
                    .exceptionally(ex -> {
                        log.warn("등록 토글 실패: {}", ex.getMessage());
                        Platform.runLater(() -> toggleRegistrationBtn.setDisable(false));
                        return null;
                    });
        });
    }

    private void applyRegistrationState(boolean enabled) {
        registrationEnabled = enabled;
        registrationStatusLabel.setText(enabled ? "✔ 허용 중" : "✖ 차단 중");
        registrationStatusLabel.getStyleClass().removeAll("status-online", "status-offline");
        registrationStatusLabel.getStyleClass().add(enabled ? "status-online" : "status-offline");
        toggleRegistrationBtn.setText(enabled ? "차단으로 전환" : "허용으로 전환");
        toggleRegistrationBtn.getStyleClass().removeAll("btn-danger", "btn-success", "btn-neutral");
        toggleRegistrationBtn.getStyleClass().add(enabled ? "btn-danger" : "btn-success");
        toggleRegistrationBtn.setDisable(false);
    }

    private void revokeSelected() {
        List<Map<String, Object>> selected = getCheckedItems();
        if (selected.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                selected.size() + "개 에이전트의 토큰을 폐기하시겠습니까?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("토큰 폐기");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            for (Map<String, Object> row : selected) {
                String mac = str(row.get("macAddress"));
                api.putJson("/admin/agents/" + mac + "/revoke", Map.of())
                        .exceptionally(ex -> { log.warn("토큰 폐기 실패 {}: {}", mac, ex.getMessage()); return null; });
            }
            // 모두 요청 후 목록 갱신
            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            t.setOnFinished(e -> Platform.runLater(this::loadAgents));
            t.play();
        });
    }

    private void deleteSelected() {
        List<Map<String, Object>> selected = getCheckedItems();
        if (selected.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                selected.size() + "개 에이전트를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("에이전트 삭제");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            for (Map<String, Object> row : selected) {
                String mac = str(row.get("macAddress"));
                api.delete("/admin/agents/" + mac)
                        .exceptionally(ex -> { log.warn("에이전트 삭제 실패 {}: {}", mac, ex.getMessage()); return null; });
            }
            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            t.setOnFinished(e -> Platform.runLater(this::loadAgents));
            t.play();
        });
    }

    private void downloadConfig() {
        api.getBytes("/agent/config/download")
                .thenAccept(bytes -> Platform.runLater(() -> {
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle("설정 파일 저장");
                    fc.setInitialFileName("config.json");
                    fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
                    java.io.File file = fc.showSaveDialog(tokenTable.getScene().getWindow());
                    if (file == null) return;
                    try {
                        java.nio.file.Files.write(file.toPath(), bytes);
                        new Alert(Alert.AlertType.INFORMATION, "설정 파일 저장 완료").show();
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR, "파일 저장 실패: " + ex.getMessage()).show();
                    }
                }))
                .exceptionally(ex -> {
                    log.warn("설정파일 다운로드 실패: {}", ex.getMessage());
                    Platform.runLater(() ->
                            new Alert(Alert.AlertType.ERROR, "다운로드 실패: " + ex.getMessage()).show());
                    return null;
                });
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getCheckedItems() {
        return allAgents.stream()
                .filter(a -> {
                    SimpleBooleanProperty p = checkMap.get(str(a.get("macAddress")));
                    return p != null && p.get();
                })
                .toList();
    }

    private static String str(Object o) { return o == null ? "-" : o.toString(); }
}
