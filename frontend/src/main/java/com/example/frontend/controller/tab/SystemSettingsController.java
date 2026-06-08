package com.example.frontend.controller.tab;

import com.example.frontend.controller.dashboard.DashboardAlertApiClient;
import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시스템 설정 탭 — 알림 표시 필터 / 비밀번호 변경 / 사용자 계정 관리.
 */
public class SystemSettingsController {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsController.class);

    // Alert type 상수
    private static final String TYPE_PC_OFFLINE        = "PC_OFFLINE";
    private static final String TYPE_TRAFFIC_HIGH      = "TRAFFIC_HIGH";
    private static final String TYPE_SCMATE            = "SCMATE";
    private static final String TYPE_METRICS_ONLINE    = "METRICS_ONLINE_RATIO_DROP";
    private static final String TYPE_METRICS_PERF      = "METRICS_PERF";   // 성능 메트릭 그룹 가상 키
    private static final String TYPE_OTA               = "OTA_UPDATE";

    // 성능 메트릭 타입들 (filterMetricsPerf 체크박스 하나로 제어)
    private static final List<String> METRICS_PERF_TYPES = List.of(
            "METRICS_TRANSFER_FAILURE_RATE", "METRICS_ACK_LATENCY",
            "METRICS_RESULT_LATENCY", "METRICS_REQUEST_ERROR_RATE", "METRICS_FORBIDDEN_RATE",
            "METRICS_COMMAND_FAILURE_RATE");

    // ── 알림 필터 ──────────────────────────────────────────────────────────
    @FXML private CheckBox filterPcOffline;
    @FXML private CheckBox filterTrafficHigh;
    @FXML private CheckBox filterScmate;
    @FXML private CheckBox filterMetricsOnline;
    @FXML private CheckBox filterMetricsPerf;
    @FXML private CheckBox filterOtaUpdate;
    @FXML private Button   saveAlertBtn;
    @FXML private Label    alertSavedLabel;

    // ── 비밀번호 변경 ────────────────────────────────────────────────────────
    @FXML private PasswordField currentPwField;
    @FXML private PasswordField newPwField;
    @FXML private PasswordField confirmPwField;
    @FXML private Button changePwBtn;
    @FXML private Label  pwMessageLabel;

    // ── 사용자 계정 관리 ─────────────────────────────────────────────────────
    @FXML private TextField     newUsernameField;
    @FXML private TextField     newEmailField;
    @FXML private PasswordField newUserPwField;
    @FXML private ComboBox<String> roleCombo;
    @FXML private Button addUserBtn;
    @FXML private Label  addUserMsgLabel;
    @FXML private Label  userCountLabel;
    @FXML private Button refreshUserBtn;
    @FXML private TableView<Map<String, Object>> userTable;
    @FXML private TableColumn<Map<String, Object>, String> colUsername;
    @FXML private TableColumn<Map<String, Object>, String> colEmail;
    @FXML private TableColumn<Map<String, Object>, Void>   colAction;

    private final ApiClient api = ApiClient.getInstance();

    public Node createView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/system-settings-tab.fxml"));
            loader.setController(this);
            Node root = loader.load();
            init();
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("시스템 설정 탭 로딩 실패", e);
        }
    }

    private void init() {
        // 역할 선택
        roleCombo.getItems().addAll("ADMIN", "VIEWER");

        // 버튼 핸들러
        saveAlertBtn.setOnAction(e -> saveAlertFilter());
        changePwBtn.setOnAction(e -> changePassword());
        addUserBtn.setOnAction(e -> addUser());
        refreshUserBtn.setOnAction(e -> loadUsers());

        // 초기 데이터 로드
        loadAlertFilter();
        loadUsers();
    }

    public void reloadData() {
        loadAlertFilter();
        loadUsers();
    }

    // ── 알림 필터 ──────────────────────────────────────────────────────────

    /**
     * 서버에 저장된 필터 설정을 로드해 체크박스에 반영하고
     * DashboardAlertApiClient의 HIDDEN_TYPES도 갱신한다.
     * 기본값: PC_OFFLINE, TRAFFIC_HIGH는 꺼짐 / 나머지는 켜짐.
     */
    private void loadAlertFilter() {
        api.getJson("/settings/alert-type-filter", new TypeReference<Map<String, Object>>() {})
                .thenAccept(data -> Platform.runLater(() -> {
                    String raw = data != null ? String.valueOf(data.getOrDefault("value", "")) : "";
                    Set<String> hidden = parseHiddenTypes(raw);
                    applyFilterToCheckboxes(hidden);
                    DashboardAlertApiClient.setHiddenTypes(hidden);
                }))
                .exceptionally(ex -> {
                    // 설정 없으면 기본값 적용 (PC_OFFLINE, TRAFFIC_HIGH 꺼짐)
                    Platform.runLater(() -> {
                        filterPcOffline.setSelected(false);
                        filterTrafficHigh.setSelected(false);
                        filterScmate.setSelected(true);
                        filterMetricsOnline.setSelected(true);
                        filterMetricsPerf.setSelected(true);
                        filterOtaUpdate.setSelected(true);
                        DashboardAlertApiClient.setHiddenTypes(
                                Set.of(TYPE_PC_OFFLINE, TYPE_TRAFFIC_HIGH));
                    });
                    return null;
                });
    }

    private void applyFilterToCheckboxes(Set<String> hidden) {
        filterPcOffline.setSelected(!hidden.contains(TYPE_PC_OFFLINE));
        filterTrafficHigh.setSelected(!hidden.contains(TYPE_TRAFFIC_HIGH));
        filterScmate.setSelected(!hidden.contains(TYPE_SCMATE));
        filterMetricsOnline.setSelected(!hidden.contains(TYPE_METRICS_ONLINE));
        filterMetricsPerf.setSelected(!METRICS_PERF_TYPES.stream().allMatch(hidden::contains));
        filterOtaUpdate.setSelected(!hidden.contains(TYPE_OTA));
    }

    private void saveAlertFilter() {
        Set<String> hidden = buildHiddenTypes();
        String json = hidden.stream()
                .sorted()
                .collect(java.util.stream.Collectors.joining(",", "[\"", "\"]"))
                .replace(",", "\",\"");
        if (hidden.isEmpty()) json = "[]";

        api.putJson("/settings/alert-type-filter", Map.of("value", json))
                .thenRun(() -> Platform.runLater(() -> {
                    DashboardAlertApiClient.setHiddenTypes(hidden);
                    alertSavedLabel.setText("저장됨");
                    alertSavedLabel.setStyle("-fx-text-fill: #16a34a;");
                    PauseTransition p = new PauseTransition(Duration.seconds(3));
                    p.setOnFinished(ev -> alertSavedLabel.setText(""));
                    p.play();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        alertSavedLabel.setText("저장 실패");
                        alertSavedLabel.setStyle("-fx-text-fill: #dc2626;");
                    });
                    return null;
                });
    }

    /** 체크 해제된 체크박스 → hidden type set 구성 */
    private Set<String> buildHiddenTypes() {
        Set<String> hidden = new HashSet<>();
        if (!filterPcOffline.isSelected())    hidden.add(TYPE_PC_OFFLINE);
        if (!filterTrafficHigh.isSelected())  hidden.add(TYPE_TRAFFIC_HIGH);
        if (!filterScmate.isSelected())       hidden.add(TYPE_SCMATE);
        if (!filterMetricsOnline.isSelected()) hidden.add(TYPE_METRICS_ONLINE);
        if (!filterMetricsPerf.isSelected())  hidden.addAll(METRICS_PERF_TYPES);
        if (!filterOtaUpdate.isSelected())    hidden.add(TYPE_OTA);
        return hidden;
    }

    /** JSON 배열 문자열 → hidden types set */
    private Set<String> parseHiddenTypes(String json) {
        Set<String> set = new HashSet<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return set;
        String cleaned = json.trim().replaceAll("[\\[\\]\"]", "");
        for (String tok : cleaned.split(",")) {
            String t = tok.trim();
            if (!t.isBlank()) set.add(t);
        }
        return set;
    }

    // ── 비밀번호 변경 ────────────────────────────────────────────────────────

    private void changePassword() {
        String current = currentPwField.getText();
        String newPw   = newPwField.getText();
        String confirm = confirmPwField.getText();

        if (current.isBlank() || newPw.isBlank()) {
            showPwMsg("현재 비밀번호와 새 비밀번호를 입력하세요.", false); return;
        }
        if (newPw.length() < 8) {
            showPwMsg("새 비밀번호는 8자 이상이어야 합니다.", false); return;
        }
        if (!newPw.equals(confirm)) {
            showPwMsg("새 비밀번호가 일치하지 않습니다.", false); return;
        }

        changePwBtn.setDisable(true);
        api.putJson("/admin/change-password", Map.of("currentPassword", current, "newPassword", newPw))
                .thenRun(() -> Platform.runLater(() -> {
                    showPwMsg("비밀번호가 변경되었습니다.", true);
                    currentPwField.clear(); newPwField.clear(); confirmPwField.clear();
                    changePwBtn.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        showPwMsg("변경 실패: 현재 비밀번호를 확인하세요.", false);
                        changePwBtn.setDisable(false);
                    });
                    return null;
                });
    }

    private void showPwMsg(String msg, boolean ok) {
        pwMessageLabel.setText(msg);
        pwMessageLabel.setStyle(ok ? "-fx-text-fill: #16a34a;" : "-fx-text-fill: #dc2626;");
        if (ok) {
            PauseTransition p = new PauseTransition(Duration.seconds(4));
            p.setOnFinished(e -> pwMessageLabel.setText(""));
            p.play();
        }
    }

    // ── 사용자 계정 관리 ─────────────────────────────────────────────────────

    private void loadUsers() {
        refreshUserBtn.setDisable(true);
        // UserController 경로: /api/v1/users (AdminController의 /admin/users 아님)
        api.getJson("/users", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(list -> Platform.runLater(() -> {
                    userTable.getItems().setAll(list != null ? list : List.of());
                    userCountLabel.setText("총 " + userTable.getItems().size() + "개 계정");
                    refreshUserBtn.setDisable(false);
                    setupUserTableColumns();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        userCountLabel.setText("조회 실패");
                        refreshUserBtn.setDisable(false);
                    });
                    log.warn("사용자 목록 조회 실패: {}", ex.getMessage());
                    return null;
                });
    }

    private void setupUserTableColumns() {
        colUsername.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().getOrDefault("username", ""))));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().getOrDefault("email", ""))));

        colAction.setCellFactory(col -> new TableCell<>() {
            private final Button deleteBtn = new Button("삭제");
            {
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");
                HBox box = new HBox(deleteBtn);
                box.setAlignment(javafx.geometry.Pos.CENTER);

                deleteBtn.setOnAction(e -> {
                    Map<String, Object> row = getTableView().getItems().get(getIndex());
                    Object id    = row.get("id");
                    String uname = String.valueOf(row.getOrDefault("username", "?"));
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "'" + uname + "' 계정을 삭제하시겠습니까?",
                            ButtonType.OK, ButtonType.CANCEL);
                    confirm.setTitle("계정 삭제 확인");
                    confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                        api.delete("/users/" + id)
                                .thenRun(() -> Platform.runLater(SystemSettingsController.this::loadUsers))
                                .exceptionally(ex -> { log.warn("삭제 실패: {}", ex.getMessage()); return null; })
                    );
                });
                setGraphic(box);
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : getGraphic());
            }
        });
    }

    private void addUser() {
        String username = newUsernameField.getText().trim();
        String email    = newEmailField.getText().trim();
        String password = newUserPwField.getText();

        if (username.isBlank() || password.isBlank()) {
            addUserMsgLabel.setText("사용자명과 비밀번호는 필수입니다.");
            addUserMsgLabel.setStyle("-fx-text-fill: #dc2626;");
            return;
        }
        // 이메일 기본값: username@local
        String effectiveEmail = email.isBlank() ? username + "@local" : email;

        addUserBtn.setDisable(true);
        // UserController POST /users — UserSaveRequestDto: username, password, email
        api.postJson("/users", Map.of("username", username, "password", password, "email", effectiveEmail))
                .thenRun(() -> Platform.runLater(() -> {
                    newUsernameField.clear(); newEmailField.clear(); newUserPwField.clear();
                    addUserMsgLabel.setText("계정이 추가되었습니다.");
                    addUserMsgLabel.setStyle("-fx-text-fill: #16a34a;");
                    addUserBtn.setDisable(false);
                    loadUsers();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        addUserMsgLabel.setText("추가 실패: " + ex.getMessage());
                        addUserMsgLabel.setStyle("-fx-text-fill: #dc2626;");
                        addUserBtn.setDisable(false);
                    });
                    return null;
                });
    }
}
