package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.config.FrontendEnvConfig;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.ApiClientException;
import com.example.frontend.util.ApiUrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PC 관리 및 명령 전송 모듈
 * - 전체 / 강의실별 / 교사 PC만 보기
 * - 테이블 멀티셀렉션
 * - 명령 대상: 전체 / 선택 강의실 / 교사 PC / 선택 PC
 * - 전송 전 확인 다이얼로그(온/오프라인 구분)
 * - 온라인 PC만 실제 전송, 오프라인은 미전송
 */
public class DashboardPcManagementModule {
    private static final Logger log = LoggerFactory.getLogger(DashboardPcManagementModule.class);
    private static final long LARGE_TRANSFER_WARNING_BYTES = 8L * 1024L * 1024L * 1024L; // 8GB+
    private static final double ESTIMATED_TRANSFER_MB_PER_SEC = 30.0;
    private static final int REMOTE_LIST_PAGE_SIZE = 200;
    private static final int REMOTE_LIST_POLL_INTERVAL_MS = 150;
    private static final int REMOTE_LIST_TIMEOUT_SECONDS = resolveRemoteListTimeoutSeconds();
    private static final double DEFAULT_SIDE_PANEL_WIDTH = 330.0;
    private static final double COLLECT_SIDE_PANEL_WIDTH = 520.0;
    private static final String SOURCE_MODE_SERVER_LABEL = "서버 단독 배포";
    private static final String SOURCE_MODE_HYBRID_LABEL = "하이브리드 배포(서버+에이전트)";
    private static final int AGENT_ROLLOUT_INITIAL_WAVE_SIZE = 5;
    private static final double AGENT_ROLLOUT_RAMP_UP_FACTOR = 1.4;

    private final ApiClient apiClient = ApiClient.getInstance();
    private final ObjectMapper objectMapper;

    // 뷰 컨트롤
    @FXML private VBox     rootContainer;
    @FXML private StackPane viewControlsSlot;
    @FXML private StackPane pcTableSlot;
    @FXML private StackPane commandSectionSlot;

    // 전송 확인 슬라이드 패널
    @FXML private VBox   sendPanel;
    @FXML private Label  sendPanelTitleLabel;
    @FXML private VBox   sendSummaryBox;
    @FXML private ScrollPane sendOptionsScroll;
    @FXML private VBox   sendOptionsBox;
    @FXML private Button sendConfirmBtn;
    @FXML private Button sendCancelBtn;
    @FXML private Button sendPanelCloseBtn;

    private ComboBox<String> viewTypeCombo;
    private ComboBox<String> viewClassroomCombo;   // 강의실별 보기 시 표시
    private ComboBox<String> statusFilterCombo;    // 상태 필터 (전체/온라인/오프라인/고장)
    private TextField searchField;                 // PC명/IP/MAC 검색
    private Label summaryLabel;

    // 테이블
    private TableView<PcStatusDto> pcTable;

    // 체크박스 선택 (MAC 기준)
    private final Set<String> checkedMacs = new LinkedHashSet<>();
    private javafx.scene.control.CheckBox headerCheckBox;

    // 명령 전송
    private Label selectionHintLabel;
    private ComboBox<String> commandCombo;
    private Button rolloutBtn;
    private javafx.animation.Timeline sidePanelAnim;
    private Runnable sidePanelCleanup;

    // 데이터 (동기화된 컬렉션 - 백그라운드 스레드와 FX 스레드 간 동기화)
    private final List<PcStatusDto> allPcs = Collections.synchronizedList(new ArrayList<>());
    // groupName → groupId (ConcurrentHashMap - 스레드 안전)
    private final Map<String, Long> classroomMap = Collections.synchronizedMap(new LinkedHashMap<>());

    public DashboardPcManagementModule(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static int resolveRemoteListTimeoutSeconds() {
        int heartbeatIntervalSec = FrontendEnvConfig.getIntProperty("AGENT_HEARTBEAT_INTERVAL_SEC", 30);
        int calculatedByHeartbeat = Math.max(90, (heartbeatIntervalSec * 2) + 30);
        int envOverride = FrontendEnvConfig.getIntProperty("REMOTE_LIST_TIMEOUT_SECONDS", calculatedByHeartbeat);
        return Math.max(30, envOverride);
    }

    // ─────────────────────────────────────────
    // 뷰 생성
    // ─────────────────────────────────────────

    public Node createView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pc-management-view.fxml"));
            loader.setController(this);
            HBox rootHBox = loader.load(); // 루트가 HBox로 변경됨

            if (viewControlsSlot != null) viewControlsSlot.getChildren().setAll(buildViewControls());
            if (pcTableSlot != null)      pcTableSlot.getChildren().setAll(buildPcTable());
            if (commandSectionSlot != null) commandSectionSlot.getChildren().setAll(buildCommandSection());

            // 전송 패널 닫기 버튼 연결
            if (sendPanelCloseBtn != null) sendPanelCloseBtn.setOnAction(e -> closeSendPanel());
            if (sendCancelBtn != null)     sendCancelBtn.setOnAction(e -> closeSendPanel());

            loadAllData();
            return rootHBox;
        } catch (Exception e) {
            throw new IllegalStateException("PC 관리 뷰 FXML 로딩 실패", e);
        }
    }

    // ─────────────────────────────────────────
    // 보기 선택 영역
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Node buildViewControls() {
        HBox row;
        Button refreshBtn;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pcmgmt-view-controls.fxml"));
            row               = loader.load();
            viewTypeCombo      = (ComboBox<String>) loader.getNamespace().get("viewTypeCombo");
            viewClassroomCombo = (ComboBox<String>) loader.getNamespace().get("viewClassroomCombo");
            statusFilterCombo  = (ComboBox<String>) loader.getNamespace().get("statusFilterCombo");
            searchField        = (TextField)        loader.getNamespace().get("searchField");
            refreshBtn         = (Button)           loader.getNamespace().get("refreshBtn");
            summaryLabel       = (Label)            loader.getNamespace().get("summaryLabel");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load pcmgmt-view-controls.fxml", e);
        }

        viewTypeCombo.setItems(FXCollections.observableArrayList("전체 PC", "강의실별", "교사 PC만"));
        viewTypeCombo.setValue("전체 PC");

        statusFilterCombo.setItems(FXCollections.observableArrayList("전체", "온라인", "오프라인", "고장"));
        statusFilterCombo.setValue("전체");
        statusFilterCombo.setOnAction(e -> applyViewFilter());

        searchField.textProperty().addListener((obs, ov, nv) -> applyViewFilter());

        viewClassroomCombo.setOnAction(e -> applyViewFilter());

        viewTypeCombo.setOnAction(e -> {
            boolean isClassroom = "강의실별".equals(viewTypeCombo.getValue());
            viewClassroomCombo.setVisible(isClassroom);
            viewClassroomCombo.setManaged(isClassroom);
            applyViewFilter();
        });

        refreshBtn.setOnAction(e -> loadAllData());
        return row;
    }

    // ─────────────────────────────────────────
    // PC 목록 테이블 (멀티셀렉션)
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Node buildPcTable() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pcmgmt-pc-table.fxml"));
            VBox box = loader.load();
            var ns = loader.getNamespace();
            pcTable = (TableView<PcStatusDto>) ns.get("pcTable");

            TableColumn<PcStatusDto, Void> checkCol = (TableColumn<PcStatusDto, Void>) ns.get("checkCol");
            TableColumn<PcStatusDto, String> classroomCol = (TableColumn<PcStatusDto, String>) ns.get("classroomCol");
            TableColumn<PcStatusDto, String> ipCol = (TableColumn<PcStatusDto, String>) ns.get("ipCol");
            TableColumn<PcStatusDto, String> hostnameCol = (TableColumn<PcStatusDto, String>) ns.get("hostnameCol");
            TableColumn<PcStatusDto, Void> roleCol = (TableColumn<PcStatusDto, Void>) ns.get("roleCol");
            TableColumn<PcStatusDto, Void> statusCol = (TableColumn<PcStatusDto, Void>) ns.get("statusCol");
            TableColumn<PcStatusDto, String> agentVersionCol = (TableColumn<PcStatusDto, String>) ns.get("agentVersionCol");
            TableColumn<PcStatusDto, String> beatCol = (TableColumn<PcStatusDto, String>) ns.get("beatCol");

            // 체크박스 컬럼 — 헤더는 전체선택, 셀은 개별 체크
            headerCheckBox = new javafx.scene.control.CheckBox();
            headerCheckBox.setOnAction(e -> {
                if (headerCheckBox.isSelected()) {
                    pcTable.getItems().forEach(pc -> {
                        if (pc.getMacAddress() != null) checkedMacs.add(pc.getMacAddress());
                    });
                } else {
                    checkedMacs.clear();
                }
                pcTable.refresh();
                updateSelectionHint();
            });
            checkCol.setGraphic(headerCheckBox);
            checkCol.setSortable(false);
            checkCol.setCellFactory(col -> new TableCell<>() {
                private final javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox();
                {
                    cb.setOnAction(e -> {
                        PcStatusDto pc = getTableRow() == null ? null : getTableRow().getItem();
                        if (pc == null || pc.getMacAddress() == null) return;
                        if (cb.isSelected()) checkedMacs.add(pc.getMacAddress());
                        else checkedMacs.remove(pc.getMacAddress());
                        updateHeaderCheckState();
                        updateSelectionHint();
                    });
                }
                @Override protected void updateItem(Void v, boolean empty) {
                    super.updateItem(v, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        setGraphic(null); return;
                    }
                    String mac = getTableRow().getItem().getMacAddress();
                    cb.setSelected(mac != null && checkedMacs.contains(mac));
                    setGraphic(cb);
                }
            });

            pcTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

            classroomCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getGroupName() != null ? c.getValue().getGroupName() : "-"));
            ipCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getIpAddress() != null ? c.getValue().getIpAddress() : "-"));
            hostnameCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getPcName() != null ? c.getValue().getPcName() : "-"));
            agentVersionCol.setCellValueFactory(c -> Bindings.createStringBinding(() -> {
                String version = c.getValue().getAgentVersion();
                return version != null && !version.isBlank() ? version : "-";
            }));
            beatCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getLastSeen() != null ? c.getValue().getLastSeen().toString() : "-"));

            roleCol.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                    String role = getTableRow().getItem().getRole();
                    Label lbl = new Label("TEACHER".equals(role) ? "교사" : "학생");
                    lbl.getStyleClass().add("TEACHER".equals(role) ? "pcmgmt-role-teacher" : "pcmgmt-role-student");
                    setGraphic(lbl);
                }
            });
            statusCol.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                    PcStatusDto pc = getTableRow().getItem();
                    Label lbl = new Label(pc.isOnline() ? "● 온라인" : "○ 오프라인");
                    lbl.getStyleClass().add(pc.isOnline() ? "pcmgmt-status-online" : "pcmgmt-status-offline");
                    setGraphic(lbl);
                }
            });
            agentVersionCol.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null || item.isBlank() || "-".equals(item)) {
                        setText(null);
                        setGraphic(null);
                        return;
                    }
                    Label lbl = new Label(item);
                    lbl.getStyleClass().add("code-tag");
                    setText(null);
                    setGraphic(lbl);
                }
            });

            // 체크박스 기반으로 변경됐으므로 selectionModel 리스너 불필요


            VBox.setVgrow(box, Priority.ALWAYS);
            return box;
        } catch (Exception e) {
            throw new IllegalStateException("PC 테이블 FXML 로딩 실패", e);
        }
    }

    // ─────────────────────────────────────────
    // 명령 전송 섹션
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Node buildCommandSection() {
        VBox vbox;
        Button sendBtn;
        FlowPane commandControlsFlow;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pcmgmt-command-section.fxml"));
            vbox                 = loader.load();
                commandCombo         = (ComboBox<String>) loader.getNamespace().get("commandCombo");
            sendBtn              = (Button)           loader.getNamespace().get("sendBtn");
            rolloutBtn           = (Button)           loader.getNamespace().get("rolloutBtn");
            selectionHintLabel   = (Label)            loader.getNamespace().get("selectionHintLabel");
            commandControlsFlow  = (FlowPane)         loader.getNamespace().get("commandControlsFlow");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load pcmgmt-command-section.fxml", e);
        }

        commandCombo.setItems(FXCollections.observableArrayList(
                "PC 켜기(PCON)", "재시작", "종료", "스크립트 실행", "파일/폴더 전송", "파일/폴더 수집(에이전트→클라이언트)"));
        commandCombo.setValue("재시작");

        sendBtn.setOnAction(e -> handleSendCommand());
        if (rolloutBtn != null) {
            rolloutBtn.setOnAction(e -> openAgentRolloutDialog());
        }
        if (commandControlsFlow != null) {
            Runnable updateWrap = () -> {
                double width = vbox.getWidth();
                if (width <= 1) {
                    return;
                }
                commandControlsFlow.setPrefWrapLength(Math.max(340.0, width - 20.0));
            };
            vbox.widthProperty().addListener((obs, oldW, newW) -> updateWrap.run());
            Platform.runLater(updateWrap);
        }
        updateSelectionHint();
        return vbox;
    }

    /**
     * 에이전트 업데이트 배포 — 간소화된 3단계 UI.
     *
     * ① MSI 업로드 → ② 시점해제 경고 확인 → ③ 배포 시작
     *
     * 대상: 체크된 PC (없으면 전체). 내부는 기존 file-distribution 백엔드를 그대로 사용.
     * 시점(SCMATE) 해제 없이 재부팅하면 작업이 날아가므로 배포 전 관리자에게 반드시 고지.
     */
    private void openAgentRolloutDialog() {
        if (apiClient.isViewerRole()) {
            showAlert("권한 없음", ApiClient.viewerWriteDeniedMessage("에이전트 업데이트 배포"), Alert.AlertType.INFORMATION);
            return;
        }

        // ── 대상 PC 결정 ──
        List<PcStatusDto> targetPcs = checkedMacs.isEmpty() ? resolveAllPcs() : resolveCheckedPcs();
        int totalCount = targetPcs.size();
        long onlineCount = targetPcs.stream().filter(PcStatusDto::isOnline).count();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("에이전트 업데이트 배포");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().setMinWidth(440);

        VBox root = new VBox(16);
        root.setPadding(new Insets(16));

        // ── 대상 요약 ──
        String targetDesc = checkedMacs.isEmpty()
                ? String.format("전체 PC %d대 (온라인 %d대)", totalCount, onlineCount)
                : String.format("선택 PC %d대 (온라인 %d대)", totalCount, onlineCount);
        Label targetLabel = new Label("배포 대상: " + targetDesc);
        targetLabel.getStyleClass().add("pcmgmt-label-title");

        // ── ① MSI 업로드 ──
        Label step1Label = new Label("① MSI 파일 업로드");
        step1Label.getStyleClass().add("pcmgmt-command-title");

        Label uploadStatusLabel = new Label("MSI 파일을 선택해 업로드하세요.");
        uploadStatusLabel.setWrapText(true);
        uploadStatusLabel.getStyleClass().add("pcmgmt-selection-hint");

        final long[] uploadedArtifactId = {0};
        final String[] uploadedFilename = {""};
        final String[] uploadedVersion = {""};

        Button uploadBtn = new Button("📁 MSI 파일 선택 및 업로드");
        uploadBtn.getStyleClass().addAll("btn-primary");
        uploadBtn.setMaxWidth(Double.MAX_VALUE);

        // ── ⚠ 시점해제 경고 ──
        Label step2Label = new Label("② 배포 전 필수 확인");
        step2Label.getStyleClass().add("pcmgmt-command-title");

        javafx.scene.control.CheckBox scmateCheckBox = new javafx.scene.control.CheckBox(
                "학생들이 시점을 해제(저장)했음을 확인했습니다.\n" +
                "시점 미해제 상태에서 재부팅하면 작업 내용이 사라집니다.");
        scmateCheckBox.setWrapText(true);
        scmateCheckBox.getStyleClass().add("pcmgmt-hint-label");

        // ── ③ 배포 시작 ──
        Label step3Label = new Label("③ 배포 시작");
        step3Label.getStyleClass().add("pcmgmt-command-title");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(6);
        logArea.setPromptText("배포 결과가 여기에 표시됩니다.");

        Button deployBtn = new Button("⬆ 지금 배포 시작");
        deployBtn.getStyleClass().addAll("btn-warning");
        deployBtn.setMaxWidth(Double.MAX_VALUE);
        deployBtn.setDisable(true); // 업로드 + 시점 확인 후 활성화

        Runnable checkReady = () -> {
            boolean ready = uploadedArtifactId[0] > 0 && scmateCheckBox.isSelected();
            deployBtn.setDisable(!ready);
        };
        scmateCheckBox.selectedProperty().addListener((obs, ov, nv) -> checkReady.run());

        // ── 업로드 액션 ──
        uploadBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("에이전트 MSI 선택");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("에이전트 설치 파일 (MSI 권장)", "*.msi"),
                    new FileChooser.ExtensionFilter("레거시 바이너리", "*.jar", "*.exe", "*.zip"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            File selected = chooser.showOpenDialog(
                    dialog.getDialogPane().getScene() == null ? null : dialog.getDialogPane().getScene().getWindow());
            if (selected == null) return;

            uploadBtn.setDisable(true);
            uploadStatusLabel.setText("업로드 중: " + selected.getName() + " ...");
            apiClient.uploadScriptFile(selected, (uploadedBytes, totalBytes) -> Platform.runLater(() -> {
                        uploadStatusLabel.setText("업로드 중: " + selected.getName()
                                + " - " + formatSize(uploadedBytes) + " / " + formatSize(totalBytes)
                                + " (" + formatProgressPercent(uploadedBytes, totalBytes) + ")");
                    }))
                    .thenAccept(resp -> {
                        try {
                            Map<String, Object> body = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
                            long artId = toLong(body.get("id"));
                            String ver = guessVersionFromFilename(selected.getName());
                            Platform.runLater(() -> {
                                uploadedArtifactId[0] = artId;
                                uploadedFilename[0] = selected.getName();
                                uploadedVersion[0] = ver != null ? ver : "";
                                uploadStatusLabel.setText("✓ 업로드 완료 — " + selected.getName()
                                        + (ver != null && !ver.isBlank() ? " (v" + ver + ")" : "")
                                        + " [ID: " + artId + "]");
                                uploadBtn.setDisable(false);
                                checkReady.run();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                uploadStatusLabel.setText("✗ 업로드 실패: " + ex.getMessage());
                                uploadBtn.setDisable(false);
                            });
                        }
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String msg = ApiClient.userFacingErrorMessage(ex, "업로드 실패");
                            uploadStatusLabel.setText("✗ " + msg);
                            uploadBtn.setDisable(false);
                        });
                        return null;
                    });
        });

        // ── 배포 액션 ──
        deployBtn.setOnAction(e -> {
            if (uploadedArtifactId[0] <= 0) { return; }
            deployBtn.setDisable(true);
            logArea.setText("배포 잡 생성 중...");

            String uploadId = "agent-update-" + uploadedArtifactId[0] + "-" + System.currentTimeMillis();
            String filename = uploadedFilename[0].isBlank() ? "agent-update-package" : uploadedFilename[0];

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uploadId", uploadId);
            body.put("originalFilename", filename);
            body.put("totalChunks", 1);
            body.put("artifactId", uploadedArtifactId[0]);
            body.put("targetVersion", uploadedVersion[0]);
            body.put("waveSize", Math.max(1, Math.min(AGENT_ROLLOUT_INITIAL_WAVE_SIZE, totalCount)));
            body.put("canaryOnly", false);
            body.put("sourceMode", "HYBRID_PEER");
            body.put("rampUpFactor", AGENT_ROLLOUT_RAMP_UP_FACTOR);
            // 체크된 강의실이 단일이면 groupId 추가
            if (!checkedMacs.isEmpty()) {
                targetPcs.stream()
                        .map(PcStatusDto::getGroupId)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .ifPresent(gid -> body.put("groupId", gid));
            }

            apiClient.postJson("/file-distribution/jobs", body)
                    .thenAccept(resp -> {
                        try {
                            Map<String, Object> created = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
                            Platform.runLater(() -> {
                                long jobId = toLong(created.get("jobId"));
                                logArea.setText("✓ 배포 잡 생성 완료\n"
                                        + "Job ID: " + jobId + "\n"
                                        + "상태: " + created.getOrDefault("status", "-") + "\n"
                                        + "방식: 하이브리드 피어 배포\n"
                                        + "초기 웨이브: " + body.get("waveSize") + "대 / 확장률: " + AGENT_ROLLOUT_RAMP_UP_FACTOR + "\n"
                                        + "대상: " + targetDesc + "\n\n"
                                        + "에이전트가 명령을 수신하면 MSI를 설치하고 재부팅합니다.\n"
                                        + "명령 탭에서 진행 상태를 확인하세요.");
                                deployBtn.setDisable(false);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                logArea.setText("✗ 응답 파싱 실패: " + ex.getMessage());
                                deployBtn.setDisable(false);
                            });
                        }
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String msg = ApiClient.userFacingErrorMessage(ex, "배포 잡 생성 실패");
                            logArea.setText("✗ " + msg);
                            showAlert("실패", msg, Alert.AlertType.ERROR);
                            deployBtn.setDisable(false);
                        });
                        return null;
                    });
        });

        root.getChildren().addAll(
                targetLabel,
                new javafx.scene.control.Separator(),
                step1Label, uploadBtn, uploadStatusLabel,
                new javafx.scene.control.Separator(),
                step2Label, scmateCheckBox,
                new javafx.scene.control.Separator(),
                step3Label, deployBtn, logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        dialog.getDialogPane().setContent(root);
        dialog.showAndWait();
    }

    private void loadRolloutJobsIntoList(ListView<Map<String, Object>> jobsListView, TextArea detailArea) {
        apiClient.getJson("/file-distribution/jobs", new TypeReference<List<Map<String, Object>>>() {})
                .thenAccept(rows -> Platform.runLater(() -> {
                    jobsListView.setItems(FXCollections.observableArrayList(rows == null ? List.of() : rows));
                    if (rows == null || rows.isEmpty()) {
                        detailArea.setText("최근 배포 작업이 없습니다.");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        String msg = ApiClient.userFacingErrorMessage(ex, "최근 작업 조회 실패");
                        detailArea.setText(msg);
                    });
                    return null;
                });
    }

    private void loadRolloutDetail(long jobId, TextArea detailArea) {
        apiClient.getJson("/file-distribution/jobs/" + jobId, new TypeReference<Map<String, Object>>() {})
                .thenAccept(status -> apiClient.getJson(
                                "/file-distribution/jobs/" + jobId + "/events",
                                new TypeReference<List<Map<String, Object>>>() {})
                        .thenAccept(events -> Platform.runLater(() -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("[작업 상태]\n");
                            sb.append("Job ID: ").append(toLong(status.get("jobId"))).append('\n');
                            sb.append("상태: ").append(status.getOrDefault("status", "-")).append('\n');
                            sb.append("진행: ").append(status.getOrDefault("completed", 0)).append("/")
                                    .append(status.getOrDefault("totalAgents", 0)).append('\n');
                            sb.append("실패: ").append(status.getOrDefault("failed", 0)).append('\n');
                            sb.append("배포 모드: ").append(sourceModeLabel(status.get("sourceMode"))).append('\n');
                            sb.append("타깃버전: ").append(status.getOrDefault("targetVersion", "-")).append("\n\n");

                            sb.append("[최근 이벤트]\n");
                            if (events == null || events.isEmpty()) {
                                sb.append("- 이벤트 없음\n");
                            } else {
                                int max = Math.min(20, events.size());
                                for (int i = 0; i < max; i++) {
                                    Map<String, Object> event = events.get(i);
                                    sb.append("- ")
                                            .append(event.getOrDefault("createdAt", "-"))
                                            .append(" | ")
                                            .append(event.getOrDefault("eventType", "-"))
                                            .append(" | ")
                                            .append(event.getOrDefault("detailJson", "{}"))
                                            .append('\n');
                                }
                            }
                            detailArea.setText(sb.toString());
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> detailArea.setText(ApiClient.userFacingErrorMessage(ex, "이벤트 조회 실패")));
                            return null;
                        }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> detailArea.setText(ApiClient.userFacingErrorMessage(ex, "작업 상세 조회 실패")));
                    return null;
                });
    }

    private String sourceModeCode(String selectedLabel) {
        if (SOURCE_MODE_SERVER_LABEL.equals(selectedLabel)) {
            return "SERVER_ONLY";
        }
        return "HYBRID_PEER";
    }

    private String sourceModeLabel(Object sourceModeCode) {
        if (sourceModeCode == null) {
            return "-";
        }
        String normalized = String.valueOf(sourceModeCode).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SERVER_ONLY" -> SOURCE_MODE_SERVER_LABEL;
            case "HYBRID_PEER" -> SOURCE_MODE_HYBRID_LABEL;
            default -> normalized;
        };
    }

    private String formatCreatedAtDisplay(Object createdAtRaw) {
        if (createdAtRaw == null) {
            return "-";
        }
        String text = String.valueOf(createdAtRaw).trim();
        if (text.isBlank()) {
            return "-";
        }
        String normalized = text.replace('T', ' ');
        if (normalized.length() > 19) {
            return normalized.substring(0, 19);
        }
        return normalized;
    }

    private Long parsePositiveLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private double parseDoubleOrDefault(String raw, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return defaultValue;
            }
            return Math.max(1.0d, Math.min(3.0d, parsed));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String guessVersionFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile("(?i)v?(\\d+\\.\\d+(?:\\.\\d+){0,2}(?:[-._]?[a-z0-9]+)*)");
        Matcher matcher = pattern.matcher(filename);
        if (!matcher.find()) {
            return "";
        }
        String value = matcher.group(1);
        return value == null ? "" : value.trim();
    }

    // ─────────────────────────────────────────
    // 데이터 로드
    // ─────────────────────────────────────────

    private void loadAllData() {
        apiClient.get("/status/all").thenAccept(body -> {
            try {
                List<PcStatusDto> parsed = objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PcStatusDto.class));

                LinkedHashMap<String, Long> map = new LinkedHashMap<>();
                parsed.stream()
                    .filter(p -> p.getGroupId() != null && p.getGroupName() != null && !"-".equals(p.getGroupName()))
                    .sorted(Comparator.comparing(p -> Objects.requireNonNull(p.getGroupName())))
                    .forEach(p -> map.putIfAbsent(p.getGroupName(), p.getGroupId()));

                synchronized (allPcs) {
                    allPcs.clear();
                    allPcs.addAll(parsed);
                }
                synchronized (classroomMap) {
                    classroomMap.clear();
                    classroomMap.putAll(map);
                }

                Platform.runLater(() -> {
                    refreshClassroomCombos();
                    applyViewFilter();
                    updateSelectionHint();
                });
            } catch (Exception ex) {
                log.error("loadAllData parse failed", ex);
            }
        }).exceptionally(ex -> {
            log.error("loadAllData failed", ex);
            return null;
        });
    }

    /** 강의실 ComboBox 목록 동기화 */
    private void refreshClassroomCombos() {
        List<String> names = new ArrayList<>(classroomMap.keySet());
        viewClassroomCombo.setItems(FXCollections.observableArrayList(names));
        if (!names.isEmpty() && viewClassroomCombo.getValue() == null) {
            viewClassroomCombo.setValue(names.get(0));
        }
    }

    // ─────────────────────────────────────────
    // 뷰 필터 적용
    // ─────────────────────────────────────────

    private void applyViewFilter() {
        String viewType = viewTypeCombo.getValue();
        List<PcStatusDto> display;

        List<PcStatusDto> snapshot;
        synchronized (allPcs) {
            snapshot = new ArrayList<>(allPcs);
        }

        if ("교사 PC만".equals(viewType)) {
            display = snapshot.stream()
                .filter(p -> "TEACHER".equals(p.getRole()))
                .collect(Collectors.toList());
        } else if ("강의실별".equals(viewType) && viewClassroomCombo.getValue() != null) {
            Long gid = classroomMap.get(viewClassroomCombo.getValue());
            display = snapshot.stream()
                .filter(p -> gid != null && gid.equals(p.getGroupId()))
                .collect(Collectors.toList());
        } else {
            display = new ArrayList<>(snapshot);
        }

        // 상태 필터 (온라인/오프라인/고장)
        String statusSel = statusFilterCombo != null ? statusFilterCombo.getValue() : "전체";
        if (statusSel != null && !"전체".equals(statusSel)) {
            display = display.stream()
                    .filter(p -> statusSel.equals(statusLabel(p)))
                    .collect(Collectors.toList());
        }

        // 검색 (PC명/IP/MAC, 대소문자 무시)
        String q = searchField != null && searchField.getText() != null
                ? searchField.getText().trim().toLowerCase() : "";
        if (!q.isEmpty()) {
            display = display.stream().filter(p -> contains(p.getPcName(), q)
                    || contains(p.getIpAddress(), q)
                    || contains(p.getMacAddress(), q)).collect(Collectors.toList());
        }

        // 교사PC 우선, 호스트명(pcName) 오름차순
        display.sort((a, b) -> {
            boolean aTeacher = "TEACHER".equals(a.getRole());
            boolean bTeacher = "TEACHER".equals(b.getRole());
            if (aTeacher != bTeacher) return aTeacher ? -1 : 1;
            String na = a.getPcName() != null ? a.getPcName() : "";
            String nb = b.getPcName() != null ? b.getPcName() : "";
            return na.compareToIgnoreCase(nb);
        });

        pcTable.setItems(FXCollections.observableArrayList(display));
        updateHeaderCheckState();
        updateSelectionHint();

        long online = display.stream().filter(PcStatusDto::isOnline).count();
        summaryLabel.setText(String.format("총 %d대 — 온라인 %d / 오프라인 %d", display.size(), online, display.size() - online));
    }

    /** PC 상태 → 필터 라벨 (온라인/오프라인/고장). */
    private static String statusLabel(PcStatusDto pc) {
        String s = pc.getStatus() == null ? "" : pc.getStatus().toUpperCase();
        return switch (s) {
            case "BROKEN", "FAILED" -> "고장";
            case "ONLINE", "IDLE" -> "온라인";
            default -> pc.isOnline() ? "온라인" : "오프라인";
        };
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase().contains(lowerQuery);
    }

    // ─────────────────────────────────────────
    // 선택 힌트 업데이트
    // ─────────────────────────────────────────

    private void updateSelectionHint() {
        if (selectionHintLabel == null) return;
        int checked = checkedMacs.size();
        if (checked > 0) {
            // 체크된 PC만
            List<PcStatusDto> targets = resolveCheckedPcs();
            long online = targets.stream().filter(PcStatusDto::isOnline).count();
            selectionHintLabel.setText(String.format(
                "✓ %d대 체크됨 (온라인 %d · 오프라인 %d) — 체크된 PC에만 전송",
                checked, online, checked - online));
        } else {
            // 체크 없으면 전체
            List<PcStatusDto> snapshot;
            synchronized (allPcs) { snapshot = new ArrayList<>(allPcs); }
            long online = snapshot.stream().filter(PcStatusDto::isOnline).count();
            selectionHintLabel.setText(snapshot.isEmpty()
                ? "PC 없음"
                : String.format("전체 %d대 (온라인 %d) — 체크 없으면 전체 전송",
                    snapshot.size(), online));
        }
    }

    /** 헤더 체크박스의 tristate 갱신 (전체 체크/일부 체크/미체크). */
    private void updateHeaderCheckState() {
        if (headerCheckBox == null || pcTable == null) return;
        List<PcStatusDto> items = pcTable.getItems();
        if (items == null || items.isEmpty()) {
            headerCheckBox.setSelected(false);
            headerCheckBox.setIndeterminate(false);
            return;
        }
        long total = items.size();
        long checked = items.stream()
                .filter(pc -> pc.getMacAddress() != null && checkedMacs.contains(pc.getMacAddress()))
                .count();
        if (checked == 0) {
            headerCheckBox.setIndeterminate(false);
            headerCheckBox.setSelected(false);
        } else if (checked == total) {
            headerCheckBox.setIndeterminate(false);
            headerCheckBox.setSelected(true);
        } else {
            headerCheckBox.setSelected(false);
            headerCheckBox.setIndeterminate(true);
        }
    }

    /** checkedMacs에 해당하는 PcStatusDto 목록 반환. */
    private List<PcStatusDto> resolveCheckedPcs() {
        synchronized (allPcs) {
            return allPcs.stream()
                    .filter(pc -> pc.getMacAddress() != null && checkedMacs.contains(pc.getMacAddress()))
                    .collect(Collectors.toList());
        }
    }

    // ─────────────────────────────────────────
    // 명령 전송
    // ─────────────────────────────────────────

    private void handleSendCommand() {
        String commandLabel = commandCombo.getValue();
        if (commandLabel == null) {
            showAlert("선택 오류", "명령을 선택해주세요.", Alert.AlertType.WARNING);
            return;
        }
        String command = toCommandCode(commandLabel);
        List<PcStatusDto> targetPcs = checkedMacs.isEmpty() ? resolveAllPcs() : resolveCheckedPcs();
        if (targetPcs.isEmpty()) {
            showAlert("대상 없음", "조건에 해당하는 PC가 없습니다.", Alert.AlertType.INFORMATION);
            return;
        }
        // 파일 수집은 우측 사이드 패널에서 원격 탐색 후 바로 실행
        if ("COLLECT_FILES".equals(command)) {
            showCollectPanel(commandLabel, targetPcs);
            return;
        }
        // 나머지 모든 명령 → 슬라이드 패널 직접 오픈 (팝업 없음)
        showSendPanel(command, commandLabel, targetPcs, null, null);
    }

    /** 전체 PC 목록 (현재 allPcs 스냅샷). */
    private List<PcStatusDto> resolveAllPcs() {
        synchronized (allPcs) { return new ArrayList<>(allPcs); }
    }

    private void prepareSidePanel(String title, String confirmText, String cancelText, double targetWidth) {
        disposeSidePanelResources();
        if (sendPanel == null) {
            return;
        }
        sendPanel.getStyleClass().remove("pcmgmt-side-panel-wide");
        if (targetWidth > DEFAULT_SIDE_PANEL_WIDTH) {
            sendPanel.getStyleClass().add("pcmgmt-side-panel-wide");
        }
        sendPanel.setMaxWidth(targetWidth);
        if (sendPanelTitleLabel != null) {
            sendPanelTitleLabel.setText(title);
        }
        if (sendConfirmBtn != null) {
            sendConfirmBtn.disableProperty().unbind();
            sendConfirmBtn.setDisable(false);
            sendConfirmBtn.setText(confirmText);
        }
        if (sendCancelBtn != null) {
            sendCancelBtn.setText(cancelText);
        }
        if (sendSummaryBox != null) {
            sendSummaryBox.getChildren().clear();
        }
        if (sendOptionsBox != null) {
            sendOptionsBox.getChildren().clear();
        }
        if (sendOptionsScroll != null) {
            sendOptionsScroll.setVvalue(0);
        }
    }

    private void openSidePanel(double targetWidth) {
        if (sendPanel == null) {
            return;
        }
        if (sidePanelAnim != null) {
            sidePanelAnim.stop();
        }
        sendPanel.setManaged(true);
        sendPanel.setVisible(true);
        if (sendPanel.getPrefWidth() <= 0) {
            sendPanel.setPrefWidth(0);
        }
        sidePanelAnim = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(220),
                        new javafx.animation.KeyValue(sendPanel.prefWidthProperty(), targetWidth,
                                javafx.animation.Interpolator.EASE_BOTH)));
        sidePanelAnim.play();
    }

    private void disposeSidePanelResources() {
        if (sidePanelCleanup == null) {
            return;
        }
        try {
            sidePanelCleanup.run();
        } catch (Exception ex) {
            log.debug("사이드 패널 정리 중 예외: {}", ex.getMessage());
        } finally {
            sidePanelCleanup = null;
        }
    }

    // ─────────────────────────────────────────
    // 전송 확인 슬라이드 패널
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void showSendPanel(String commandType, String commandLabel, List<PcStatusDto> targetPcs,
                               TransferSource transferSource, String collectPayloadJson) {
        if (sendPanel == null) return;
        prepareSidePanel("명령 전송 확인", "전송 실행", "취소", DEFAULT_SIDE_PANEL_WIDTH);
        long onlineCount = targetPcs.stream().filter(PcStatusDto::isOnline).count();
        long offlineCount = targetPcs.size() - onlineCount;
        boolean isFileTransfer  = "RUN_FILE_TRANSFER".equals(commandType);
        boolean isScriptNoFile  = "RUN_PS1".equals(commandType) && transferSource == null;
        // 레거시: transferSource가 미리 선택된 경우 (현재는 COLLECT_FILES 후 슬라이드만 해당)
        boolean scriptCommand   = "RUN_PS1".equals(commandType) && transferSource != null
                && "file".equals(transferSource.sourceType())
                && transferSource.displayName().toLowerCase(Locale.ROOT).endsWith(".ps1");
        boolean transferCommand = transferSource != null && !isScriptNoFile && !isFileTransfer;

        // ── 요약 카드 ──────────────────────────────────────────────────────
        sendSummaryBox.getChildren().setAll(
            styledLabel(commandLabel, "detail-section-title"),
            styledLabel("전송 예정: " + onlineCount + "대 (온라인만)", "pcmgmt-dialog-text-success"),
            offlineCount > 0
                ? styledLabel("미전송 " + offlineCount + "대 (오프라인)", "pcmgmt-dialog-text-muted")
                : styledLabel("전체 온라인 — 미전송 없음", "pcmgmt-dialog-text-muted")
        );
        if (transferSource != null) {
            sendSummaryBox.getChildren().add(
                styledLabel((transferCommand ? "파일: " : "스크립트: ") + transferSource.displayName(),
                    "meta-key"));
        }

        // ── 옵션 ─────────────────────────────────────────────────────────
        sendOptionsBox.getChildren().clear();

        // 람다 캡처용 배열
        ComboBox<String>[] runModeRef  = new ComboBox[]{null};
        ComboBox<String>[] destModeRef = new ComboBox[]{null};
        TextField[]        destPathRef = new TextField[]{null};
        TextField[]        argsRef     = new TextField[]{null};
        CheckBox[]         runAfterRef = new CheckBox[]{null};
        List<SelectableTransferItem>[] itemsRef = new List[]{new ArrayList<>()};
        File[]             ps1Ref      = new File[]{null};

        if (isFileTransfer) {
            VBox fileListBox = new VBox(4);
            Label emptyHint = styledLabel("파일 추가 버튼으로 전송할 파일을 선택하세요", "pcmgmt-dialog-text-muted");
            fileListBox.getChildren().add(emptyHint);
            fileListBox.setMinHeight(40);

            Button addFileBtn   = new Button("파일 추가");
            Button addFolderBtn = new Button("폴더 추가");
            Button clearBtn     = new Button("초기화");
            for (Button b : new Button[]{addFileBtn, addFolderBtn, clearBtn}) {
                b.getStyleClass().addAll("btn-sm", "btn-neutral");
            }
            HBox addBtns = new HBox(6, addFileBtn, addFolderBtn, clearBtn);

            Runnable refreshFileList = () -> {
                fileListBox.getChildren().clear();
                if (itemsRef[0].isEmpty()) {
                    fileListBox.getChildren().add(emptyHint);
                } else {
                    for (SelectableTransferItem it : itemsRef[0]) {
                        Label lbl = styledLabel("• " + it.displayName()
                                + " (" + formatSize(it.fileSize()) + ")", "pcmgmt-dialog-caption");
                        fileListBox.getChildren().add(lbl);
                    }
                }
            };

            addFileBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("파일 선택");
                List<File> chosen = fc.showOpenMultipleDialog(
                    sendPanel.getScene() != null ? sendPanel.getScene().getWindow() : null);
                if (chosen != null) {
                    for (File f : chosen) {
                        itemsRef[0].add(new SelectableTransferItem(
                            "file", f.getName(), f.getAbsolutePath(), true, "", f.length(), false));
                    }
                    refreshFileList.run();
                }
            });
            addFolderBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("폴더 선택");
                File d = dc.showDialog(sendPanel.getScene() != null ? sendPanel.getScene().getWindow() : null);
                if (d != null) {
                    long sz = calculateFolderSize(d.toPath());
                    itemsRef[0].add(new SelectableTransferItem(
                        "folder", d.getName(), d.getAbsolutePath(), true, "", sz, false));
                    refreshFileList.run();
                }
            });
            clearBtn.setOnAction(e -> { itemsRef[0].clear(); refreshFileList.run(); });

            ComboBox<String> destCombo = new ComboBox<>(FXCollections.observableArrayList(
                "다운로드 폴더", "바탕화면", "경로 직접 입력"));
            destCombo.setValue("다운로드 폴더");
            destCombo.setMaxWidth(Double.MAX_VALUE);
            TextField pathField = new TextField();
            pathField.setPromptText("예: D:/Deploy/files");
            pathField.setVisible(false); pathField.setManaged(false);
            destCombo.valueProperty().addListener((obs, o, n) -> {
                boolean c = "경로 직접 입력".equals(n);
                pathField.setVisible(c); pathField.setManaged(c);
            });
            CheckBox runCb = new CheckBox("전송 후 실행");
            destModeRef[0] = destCombo; destPathRef[0] = pathField; runAfterRef[0] = runCb;

            ComboBox<String> modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "대기 실행 (완료까지 수집)", "즉시 실행 (시작만 확인)"));
            modeCombo.setValue("대기 실행 (완료까지 수집)");
            modeCombo.setMaxWidth(Double.MAX_VALUE);
            runModeRef[0] = modeCombo;

            sendOptionsBox.getChildren().addAll(
                styledLabel("전송 파일", "meta-key"),
                addBtns, fileListBox,
                new Separator(),
                styledLabel("저장 위치", "meta-key"),
                destCombo, pathField, runCb,
                new Separator(),
                styledLabel("결과 처리", "meta-key"),
                modeCombo);

        } else if (isScriptNoFile) {
            Label ps1Label = styledLabel("선택된 파일 없음", "pcmgmt-dialog-text-muted");
            Button chooseBtn = new Button("스크립트 파일 선택 (.ps1)");
            chooseBtn.getStyleClass().add("btn-neutral");
            chooseBtn.setMaxWidth(Double.MAX_VALUE);
            chooseBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("PowerShell 스크립트");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerShell", "*.ps1"));
                File f = fc.showOpenDialog(sendPanel.getScene() != null ? sendPanel.getScene().getWindow() : null);
                if (f != null) {
                    ps1Ref[0] = f;
                    ps1Label.setText("✓ " + f.getName());
                    ps1Label.getStyleClass().setAll("pcmgmt-dialog-text-success");
                }
            });
            TextField argsField = new TextField();
            argsField.setPromptText("추가 인자 (예: -Mode Test)");
            argsRef[0] = argsField;

            ComboBox<String> modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "대기 실행 (완료까지 수집)", "즉시 실행 (시작만 확인)"));
            modeCombo.setValue("대기 실행 (완료까지 수집)");
            modeCombo.setMaxWidth(Double.MAX_VALUE);
            runModeRef[0] = modeCombo;

            sendOptionsBox.getChildren().addAll(
                styledLabel("스크립트 파일", "meta-key"),
                chooseBtn, ps1Label,
                new Separator(),
                styledLabel("추가 인자", "meta-key"),
                argsField,
                new Separator(),
                styledLabel("결과 처리", "meta-key"),
                modeCombo);

        } else if (scriptCommand || transferCommand) {
            if (transferCommand) {
                String preDest = transferSource.preDestMode();
                String preVal  = "DESKTOP".equals(preDest) ? "바탕화면"
                               : "CUSTOM".equals(preDest)  ? "경로 직접 입력"
                               : "다운로드 폴더";
                ComboBox<String> destCombo = new ComboBox<>(FXCollections.observableArrayList(
                    "다운로드 폴더", "바탕화면", "경로 직접 입력"));
                destCombo.setValue(preVal);
                destCombo.setMaxWidth(Double.MAX_VALUE);
                TextField pathField = new TextField(
                    transferSource.preDestPath() != null ? transferSource.preDestPath() : "");
                pathField.setPromptText("예: D:/Deploy/files");
                boolean custom0 = "경로 직접 입력".equals(preVal);
                pathField.setVisible(custom0); pathField.setManaged(custom0);
                destCombo.valueProperty().addListener((obs, o, n) -> {
                    boolean c = "경로 직접 입력".equals(n);
                    pathField.setVisible(c); pathField.setManaged(c);
                });
                CheckBox runCb = new CheckBox("전송 후 실행");
                runCb.setSelected(transferSource.preRunAfter());
                destModeRef[0] = destCombo; destPathRef[0] = pathField; runAfterRef[0] = runCb;
                sendOptionsBox.getChildren().addAll(
                    styledLabel("저장 위치", "meta-key"),
                    destCombo, pathField, runCb);
            }
            if (scriptCommand) {
                TextField argsField = new TextField();
                argsField.setPromptText("추가 인자 (예: -Mode Test)");
                argsRef[0] = argsField;
                sendOptionsBox.getChildren().addAll(
                    styledLabel("추가 인자", "meta-key"), argsField);
            }
            ComboBox<String> modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "대기 실행 (완료까지 수집)", "즉시 실행 (시작만 확인)"));
            modeCombo.setValue("대기 실행 (완료까지 수집)");
            modeCombo.setMaxWidth(Double.MAX_VALUE);
            runModeRef[0] = modeCombo;
            if (!sendOptionsBox.getChildren().isEmpty()) {
                sendOptionsBox.getChildren().add(new Separator());
            }
            sendOptionsBox.getChildren().addAll(
                styledLabel("결과 처리", "meta-key"), modeCombo);
        }

        // ── 전송 버튼 동작 ────────────────────────────────────────────────
        sendConfirmBtn.setOnAction(ev -> {
            if (isFileTransfer) {
                if (itemsRef[0].isEmpty()) {
                    showAlert("선택 오류", "전송할 파일이나 폴더를 1개 이상 추가하세요.", Alert.AlertType.WARNING);
                    return;
                }
                final List<SelectableTransferItem> items = new ArrayList<>(itemsRef[0]);
                final String destMode = mapDestinationMode(
                    destModeRef[0] != null ? destModeRef[0].getValue() : "다운로드 폴더");
                final String destPath  = destPathRef[0]  != null ? destPathRef[0].getText()  : "";
                final boolean runAfter = runAfterRef[0]  != null && runAfterRef[0].isSelected();
                final boolean bgMode   = runModeRef[0]   != null && runModeRef[0].getValue().startsWith("즉시");
                closeSendPanel();
                Thread t = new Thread(() -> {
                    try {
                        Path zipPath = createMultiTransferZip(items);
                        TransferSource ts = new TransferSource(zipPath, "multi-transfer", "MultiTransfer.zip",
                            true, true, destMode, destPath, runAfter, null);
                        ScriptExecutionOptions opts = new ScriptExecutionOptions(
                            ts.path().toString(), ts.scriptUrl(),
                            bgMode ? "BACKGROUND" : "FOREGROUND", "HIDDEN", "",
                            true, ts.sourceType(), ts.displayName(),
                            destMode, destPath, runAfter, ts.zipped(), ts.temporaryArtifact());
                        executeCommand("RUN_PS1", targetPcs, opts, null);
                    } catch (Exception ex) {
                        Platform.runLater(() -> showAlert("전송 준비 실패", ex.getMessage(), Alert.AlertType.ERROR));
                    }
                }, "pcmgmt-transfer-prep");
                t.setDaemon(true);
                t.start();
                return;
            }
            if (isScriptNoFile) {
                if (ps1Ref[0] == null) {
                    showAlert("선택 오류", "실행할 .ps1 파일을 선택하세요.", Alert.AlertType.WARNING);
                    return;
                }
                File ps1 = ps1Ref[0];
                TransferSource ts = TransferSource.simple(ps1.toPath(), "file", ps1.getName(), false, false);
                boolean bgMode = runModeRef[0] != null && runModeRef[0].getValue().startsWith("즉시");
                ScriptExecutionOptions opts = new ScriptExecutionOptions(
                    ts.path().toString(), ts.scriptUrl(),
                    bgMode ? "BACKGROUND" : "FOREGROUND", "HIDDEN",
                    argsRef[0] != null ? argsRef[0].getText() : "",
                    false, ts.sourceType(), ts.displayName(),
                    "DOWNLOADS", "", false, ts.zipped(), ts.temporaryArtifact());
                closeSendPanel();
                executeCommand("RUN_PS1", targetPcs, opts, null);
                return;
            }
            // 단순 명령(재시작/종료/PCON) 및 레거시 transferSource 케이스
            ScriptExecutionOptions scriptOptions = (scriptCommand || transferCommand)
                ? new ScriptExecutionOptions(
                    transferSource == null || transferSource.path() == null ? null : transferSource.path().toString(),
                    transferSource == null ? null : transferSource.scriptUrl(),
                    runModeRef[0] != null && runModeRef[0].getValue().startsWith("즉시") ? "BACKGROUND" : "FOREGROUND",
                    "HIDDEN",
                    argsRef[0] != null ? argsRef[0].getText() : "",
                    transferCommand,
                    transferSource == null ? "file" : transferSource.sourceType(),
                    transferSource == null ? "" : transferSource.displayName(),
                    destModeRef[0] != null ? mapDestinationMode(destModeRef[0].getValue()) : "DOWNLOADS",
                    destPathRef[0] != null ? destPathRef[0].getText() : "",
                    runAfterRef[0] != null && runAfterRef[0].isSelected(),
                    transferSource != null && transferSource.zipped(),
                    transferSource != null && transferSource.temporaryArtifact())
                : ScriptExecutionOptions.none();
            closeSendPanel();
            executeCommand(commandType, targetPcs, scriptOptions, collectPayloadJson);
        });

        // ── 슬라이드 인 애니메이션 ────────────────────────────────────────
        openSidePanel(DEFAULT_SIDE_PANEL_WIDTH);
    }

    private void closeSendPanel() {
        if (sendPanel == null) return;
        disposeSidePanelResources();
        if (sidePanelAnim != null) {
            sidePanelAnim.stop();
        }
        sidePanelAnim = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(160),
                new javafx.animation.KeyValue(sendPanel.prefWidthProperty(), 0,
                    javafx.animation.Interpolator.EASE_BOTH)));
        sidePanelAnim.setOnFinished(e -> {
            sendPanel.setManaged(false);
            sendPanel.setVisible(false);
            sendPanel.getStyleClass().remove("pcmgmt-side-panel-wide");
            sendPanel.setMaxWidth(DEFAULT_SIDE_PANEL_WIDTH);
        });
        sidePanelAnim.play();
    }

    // ─────────────────────────────────────────
    // 명령 실행 (온라인 PC만)
    // ─────────────────────────────────────────

    private void executeCommand(String commandType, List<PcStatusDto> targetPcs, ScriptExecutionOptions scriptOptions,
                                String collectPayloadJson) {
        if ("COLLECT_FILES".equals(commandType) && collectPayloadJson != null && !collectPayloadJson.isBlank()) {
            executeCollectAndDownload(targetPcs, collectPayloadJson);
            return;
        }
        if ("PCON".equals(commandType)) {
            executePconCommand(targetPcs);
            return;
        }
        List<PcStatusDto> onlinePcs = targetPcs.stream().filter(PcStatusDto::isOnline).collect(Collectors.toList());
        if (onlinePcs.isEmpty()) {
            showAlert("알림", "온라인 상태인 PC가 없어 전송을 취소합니다.", Alert.AlertType.INFORMATION);
            return;
        }

        Thread t = new Thread(() -> {
            int success = 0;
            int fail = 0;
            String uploadedScriptUrl = null;

            if ("RUN_PS1".equals(commandType) && scriptOptions.hasScript()) {
                try {
                    if (scriptOptions.hasLocalScriptPath()) {
                        File localScriptFile = new File(scriptOptions.scriptPath());
                        UploadProgress uploadProgress = openUploadProgressStage("파일 업로드 중", localScriptFile);
                        try {
                            updateUploadProgress(uploadProgress, 0L, localScriptFile.length(), "서버 업로드 준비 중...");
                            String uploadResp = apiClient.uploadScriptFile(localScriptFile,
                                    (uploadedBytes, totalBytes) -> updateUploadProgress(
                                            uploadProgress,
                                            uploadedBytes,
                                            totalBytes,
                                            "서버로 업로드 중..."
                                    )).join();
                            updateUploadProgress(uploadProgress, localScriptFile.length(), localScriptFile.length(),
                                    "업로드 완료, 명령 생성 중...");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> uploadBody = objectMapper.readValue(uploadResp, Map.class);
                            uploadedScriptUrl = String.valueOf(uploadBody.getOrDefault("downloadUrl", ""));
                            if (uploadedScriptUrl == null || uploadedScriptUrl.isBlank()) {
                                throw new RuntimeException("업로드 응답에 downloadUrl이 없습니다.");
                            }
                        } finally {
                            closeUploadProgressStage(uploadProgress);
                        }
                    } else if (scriptOptions.hasRemoteScriptUrl()) {
                        uploadedScriptUrl = scriptOptions.scriptUrl();
                    } else {
                        throw new RuntimeException("스크립트/전송 소스가 없습니다.");
                    }
                } catch (Exception ex) {
                    String errorMessage = ApiClient.userFacingErrorMessage(ex, "업로드 실패");
                    log.warn("업로드 실패: {}", errorMessage);
                    int skipped = targetPcs.size() - onlinePcs.size();
                    Platform.runLater(() -> showAlert("전송 실패",
                        "업로드 실패로 명령 전송을 중단했습니다.\n" + errorMessage +
                            "\n오프라인 미전송: " + skipped + "대",
                        Alert.AlertType.ERROR));
                    return;
                }
            }

            for (PcStatusDto pc : onlinePcs) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("scope", "SINGLE");
                    body.put("macAddress", pc.getMacAddress());
                    body.put("type", commandType);
                    if ("RUN_PS1".equals(commandType) && scriptOptions.hasScript()) {
                        body.put("scriptUrl", uploadedScriptUrl);
                        body.put("payloadJson", objectMapper.writeValueAsString(scriptOptions.toPayload()));
                    } else if ("COLLECT_FILES".equals(commandType) && collectPayloadJson != null && !collectPayloadJson.isBlank()) {
                        body.put("payloadJson", collectPayloadJson);
                    }

                    apiClient.postJson("/commands", body).join();
                    success++;
                } catch (Exception ex) {
                    fail++;
                    log.warn("명령 전송 실패: {}", ex.getMessage());
                }
            }
            int finalSuccess = success;
            int finalFail = fail;
            int skipped = targetPcs.size() - onlinePcs.size();
            Platform.runLater(() -> showAlert("전송 완료",
                String.format("전송: %d대 성공%s\n오프라인 미전송: %d대",
                    finalSuccess,
                    finalFail > 0 ? " / " + finalFail + "대 실패" : "",
                    skipped),
                Alert.AlertType.INFORMATION
            ));

            if (scriptOptions.temporaryArtifact() && scriptOptions.hasLocalScriptPath()) {
                try {
                    Files.deleteIfExists(Path.of(scriptOptions.scriptPath()));
                } catch (Exception ex) {
                    log.debug("임시 파일 정리 실패: {}", ex.getMessage());
                }
            }
        }, "pcmgmt-cmd-exec");
        t.setDaemon(true);
        t.start();
    }

    private void executePconCommand(List<PcStatusDto> targetPcs) {
        List<PcStatusDto> wolTargets = targetPcs.stream()
                .filter(pc -> pc.getMacAddress() != null && !pc.getMacAddress().isBlank())
                .collect(Collectors.toList());
        if (wolTargets.isEmpty()) {
            showAlert("대상 없음", "PC ON을 보낼 MAC 주소가 없습니다.", Alert.AlertType.INFORMATION);
            return;
        }

        Thread t = new Thread(() -> {
            int queuedTargets = 0;
            int failedTargets = 0;
            int relayRequests = 0;

            Map<Long, List<PcStatusDto>> byGroup = wolTargets.stream()
                    .filter(pc -> pc.getGroupId() != null)
                    .collect(Collectors.groupingBy(PcStatusDto::getGroupId, LinkedHashMap::new, Collectors.toList()));

            for (Map.Entry<Long, List<PcStatusDto>> entry : byGroup.entrySet()) {
                List<String> targetMacs = entry.getValue().stream()
                        .map(PcStatusDto::getMacAddress)
                        .filter(mac -> mac != null && !mac.isBlank())
                        .distinct()
                        .collect(Collectors.toList());
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("scope", "GROUP");
                    body.put("groupId", entry.getKey());
                    body.put("type", "PCON");
                    body.put("role", "ALL");
                    body.put("targetMacs", targetMacs);
                    int queued = extractQueuedCount(apiClient.postJson("/commands", body).join());
                    if (queued > 0) {
                        queuedTargets += entry.getValue().size();
                        relayRequests += queued;
                    } else {
                        failedTargets += entry.getValue().size();
                    }
                } catch (Exception ex) {
                    failedTargets += entry.getValue().size();
                    log.warn("PC ON 그룹 전송 실패: groupId={}, message={}", entry.getKey(), ex.getMessage());
                }
            }

            for (PcStatusDto pc : wolTargets.stream().filter(pc -> pc.getGroupId() == null).toList()) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("scope", "SINGLE");
                    body.put("macAddress", pc.getMacAddress());
                    body.put("type", "PCON");
                    int queued = extractQueuedCount(apiClient.postJson("/commands", body).join());
                    if (queued > 0) {
                        queuedTargets++;
                        relayRequests += queued;
                    } else {
                        failedTargets++;
                    }
                } catch (Exception ex) {
                    failedTargets++;
                    log.warn("PC ON 개별 전송 실패: mac={}, message={}", pc.getMacAddress(), ex.getMessage());
                }
            }

            int finalQueuedTargets = queuedTargets;
            int finalFailedTargets = failedTargets;
            int finalRelayRequests = relayRequests;
            Platform.runLater(() -> showAlert("PC ON 요청 완료",
                    String.format("대상: %d대\n릴레이 요청: %d건\n큐잉 성공 대상: %d대%s",
                            wolTargets.size(),
                            finalRelayRequests,
                            finalQueuedTargets,
                            finalFailedTargets > 0 ? "\n큐잉 실패/릴레이 없음: " + finalFailedTargets + "대" : ""),
                    finalFailedTargets > 0 ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION));
        }, "pcmgmt-pcon-exec");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private int extractQueuedCount(String response) {
        if (response == null || response.isBlank()) return 0;
        try {
            Map<String, Object> body = objectMapper.readValue(response, Map.class);
            Object queued = body.get("queued");
            if (queued instanceof Number number) {
                return number.intValue();
            }
            return queued == null ? 0 : Integer.parseInt(String.valueOf(queued));
        } catch (Exception ex) {
            return 0;
        }
    }

    private record UploadProgress(
            Stage stage,
            ProgressBar progressBar,
            Label statusLabel,
            Label detailLabel
    ) {}

    private UploadProgress openUploadProgressStage(String title, File file) {
        if (file == null) return null;
        CountDownLatch uiReady = new CountDownLatch(1);
        AtomicReference<UploadProgress> holder = new AtomicReference<>();
        Platform.runLater(() -> {
            holder.set(buildUploadProgressStage(title, file));
            uiReady.countDown();
        });
        try {
            uiReady.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return holder.get();
    }

    private UploadProgress buildUploadProgressStage(String title, File file) {
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(420);
        Label statusLabel = new Label("업로드 준비 중...");
        Label detailLabel = new Label(file.getName() + " (" + formatSize(file.length()) + ")");

        VBox box = new VBox(10,
                new Label("백엔드 업로드 진행 상태"),
                progressBar,
                statusLabel,
                detailLabel
        );
        box.setPadding(new Insets(16));
        box.setPrefWidth(460);

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle(title);
        stage.setScene(new Scene(box));
        stage.setResizable(false);
        stage.show();
        return new UploadProgress(stage, progressBar, statusLabel, detailLabel);
    }

    private void updateUploadProgress(UploadProgress progress,
                                      long uploadedBytes,
                                      long totalBytes,
                                      String statusText) {
        if (progress == null) return;
        Platform.runLater(() -> {
            progress.progressBar().setProgress(totalBytes > 0 ? Math.min(1.0, (double) uploadedBytes / totalBytes) : -1);
            progress.statusLabel().setText(statusText);
            progress.detailLabel().setText(formatSize(uploadedBytes) + " / " + formatSize(totalBytes)
                    + " (" + formatProgressPercent(uploadedBytes, totalBytes) + ")");
        });
    }

    private void closeUploadProgressStage(UploadProgress progress) {
        if (progress == null || progress.stage() == null) return;
        Platform.runLater(progress.stage()::close);
    }

    // ─────────────────────────────────────────
    // 파일 수집 (에이전트 → 대시보드 PC 자동 저장)
    // ─────────────────────────────────────────

    private static final int COLLECT_POLL_INTERVAL_MS = 1500;
    private static final int COLLECT_TIMEOUT_SECONDS = 600; // 10분

    // ── 진행 상태 레코드 ─────────────────────────
    private record CollectProgress(
            Stage stage,
            ProgressBar overallBar,
            Label overallLabel,
            Label currentAgentLabel,
            ProgressBar fileBar,
            Label fileLabel,
            Label etaLabel,
            AtomicBoolean cancelled
    ) {}

    private CollectProgress buildCollectProgressStage(int totalPcs, Path baseDir) {
        ProgressBar overallBar = new ProgressBar(0);
        overallBar.setPrefWidth(440);
        Label overallLabel = new Label("0 / " + totalPcs + " 완료");
        Label currentAgentLabel = new Label("대기 중...");
        currentAgentLabel.setStyle("-fx-font-weight: bold;");
        ProgressBar fileBar = new ProgressBar(-1); // indeterminate
        fileBar.setPrefWidth(440);
        Label fileLabel = new Label("명령 전송 준비 중...");
        Label etaLabel = new Label("남은 시간: 계산 중...");
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Button cancelBtn = new Button("취소");

        VBox box = new VBox(8,
                new Label("수집 대상: " + totalPcs + "대  |  저장 위치: " + baseDir),
                new Separator(),
                new Label("전체 진행"),
                overallBar,
                overallLabel,
                etaLabel,
                new Separator(),
                new Label("현재 에이전트"),
                currentAgentLabel,
                fileBar,
                fileLabel,
                cancelBtn
        );
        box.setPadding(new Insets(16));
        box.setPrefWidth(480);

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("파일 수집 진행 중");
        stage.setScene(new javafx.scene.Scene(box));
        stage.setResizable(false);
        stage.setOnCloseRequest(ev -> cancelled.set(true));
        cancelBtn.setOnAction(e -> { cancelled.set(true); stage.close(); });

        Platform.runLater(stage::show);
        return new CollectProgress(stage, overallBar, overallLabel, currentAgentLabel,
                fileBar, fileLabel, etaLabel, cancelled);
    }

    private void executeCollectAndDownload(List<PcStatusDto> targetPcs, String collectPayloadJson) {
        List<PcStatusDto> onlinePcs = targetPcs.stream()
                .filter(PcStatusDto::isOnline)
                .collect(Collectors.toList());
        if (onlinePcs.isEmpty()) {
            showAlert("알림", "온라인 PC가 없어 수집을 취소합니다.", Alert.AlertType.INFORMATION);
            return;
        }

        String configuredDir = FrontendEnvConfig.getProperty("COLLECT_DOWNLOAD_DIR", "");
        Path baseDir = configuredDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "qsync-downloads")
                : Path.of(configuredDir);

        String outputName;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pl = objectMapper.readValue(collectPayloadJson, Map.class);
            outputName = String.valueOf(pl.getOrDefault("outputName", "collected.zip"));
        } catch (Exception ex) {
            outputName = "collected.zip";
        }
        final String finalOutputName = outputName.endsWith(".zip") ? outputName : outputName + ".zip";
        final int total = onlinePcs.size();

        // 진행 다이얼로그 생성 (FX 스레드에서 열기)
        CollectProgress[] progressHolder = {null};
        CountDownLatch uiReady = new CountDownLatch(1);
        Platform.runLater(() -> {
            progressHolder[0] = buildCollectProgressStage(total, baseDir);
            uiReady.countDown();
        });

        Thread t = new Thread(() -> {
            try { uiReady.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            CollectProgress progress = progressHolder[0];

            int parallelism = Math.min(total, FrontendEnvConfig.getIntProperty("COLLECT_PARALLELISM", 8));
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            AtomicInteger done = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            List<String> savedPaths = Collections.synchronizedList(new ArrayList<>());
            long startMs = System.currentTimeMillis();

            CountDownLatch allDone = new CountDownLatch(total);

            for (PcStatusDto pc : onlinePcs) {
                pool.submit(() -> {
                    if (progress != null && progress.cancelled().get()) {
                        allDone.countDown();
                        failed.incrementAndGet();
                        return;
                    }
                    String agentLabel = pc.getPcName() != null && !pc.getPcName().isBlank()
                            ? pc.getPcName() : pc.getMacAddress();
                    try {
                        updateProgress(progress, agentLabel, "명령 전송 중...", -1, -1, done.get(), total, startMs);

                        // 1. 명령 전송
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("scope", "SINGLE");
                        body.put("macAddress", pc.getMacAddress());
                        body.put("type", "COLLECT_FILES");
                        body.put("payloadJson", collectPayloadJson);
                        String enqueueResp = apiClient.postJson("/commands", body).join();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> enqueueBody = objectMapper.readValue(enqueueResp,
                                new TypeReference<Map<String, Object>>() {});
                        @SuppressWarnings("unchecked")
                        List<Object> taskIds = (List<Object>) enqueueBody.getOrDefault("taskIds", List.of());
                        if (taskIds.isEmpty()) throw new IllegalStateException("명령 큐 등록 실패");
                        long taskId = Long.parseLong(String.valueOf(taskIds.get(0)));

                        // 2. 완료 대기 폴링
                        updateProgress(progress, agentLabel, "에이전트 수집 중...", -1, -1, done.get(), total, startMs);
                        int maxPoll = (COLLECT_TIMEOUT_SECONDS * 1000) / COLLECT_POLL_INTERVAL_MS;
                        String stdout = null;
                        for (int i = 0; i < maxPoll; i++) {
                            if (progress != null && progress.cancelled().get()) break;
                            Thread.sleep(COLLECT_POLL_INTERVAL_MS);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = apiClient.getJson("/commands?taskId=" + taskId,
                                    new TypeReference<Map<String, Object>>() {}).join();
                            String status = String.valueOf(row.getOrDefault("status", ""));
                            if ("COMPLETED".equalsIgnoreCase(status)) {
                                stdout = row.get("stdout") instanceof String s ? s : "";
                                break;
                            }
                            if ("FAILED".equalsIgnoreCase(status)) {
                                String err = row.get("stderr") instanceof String s ? s : "수집 실패";
                                throw new IllegalStateException(err);
                            }
                        }
                        if (stdout == null) throw new IllegalStateException("수집 타임아웃 (10분)");

                        // 3. downloadUrl 추출
                        String downloadUrl = extractDownloadUrl(stdout);
                        if (downloadUrl.isBlank()) throw new IllegalStateException("downloadUrl 없음");

                        // 4. 스트리밍 다운로드 (대용량 파일 지원)
                        String safeName = agentLabel.replaceAll("[\\\\/:*?\"<>|]", "_");
                        Path agentDir = baseDir.resolve(safeName);
                        Files.createDirectories(agentDir);
                        Path savePath = agentDir.resolve(finalOutputName);

                        AtomicLong lastUpdate = new AtomicLong(0);
                        updateProgress(progress, agentLabel, "다운로드 시작...", 0, -1, done.get(), total, startMs);
                        apiClient.downloadToFile(ApiUrlUtil.normalizeDownloadPath(downloadUrl), savePath, (received, totalBytes) -> {
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate.get() > 200) { // 200ms 마다 UI 업데이트
                                lastUpdate.set(now);
                                updateProgress(progress, agentLabel,
                                        formatSize(received) + (totalBytes > 0 ? " / " + formatSize(totalBytes) : ""),
                                        received, totalBytes, done.get(), total, startMs);
                            }
                        });

                        savedPaths.add(savePath.toString());
                        int d = done.incrementAndGet();
                        updateProgress(progress, agentLabel, "✓ 저장 완료: " + savePath.getFileName(), 1, 1, d, total, startMs);
                        log.info("수집 완료: {} → {}", agentLabel, savePath);

                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("수집 실패 [{}]: {}", agentLabel, ex.getMessage());
                        updateProgress(progress, agentLabel, "✗ 실패: " + ex.getMessage(), 0, 0,
                                done.get(), total, startMs);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            try { allDone.await(COLLECT_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            pool.shutdownNow();

            int finalDone = done.get();
            int finalFailed = failed.get();
            String detail = savedPaths.stream().map(p -> "  • " + p).collect(Collectors.joining("\n"));
            Platform.runLater(() -> {
                if (progress != null) {
                    // 진행 창을 Stage로 찾아 닫기
                    overallBarStageClose(progress);
                }
                showCollectResultDialog(
                        finalFailed == 0 ? "수집 완료" : "수집 부분 완료",
                        finalDone, finalFailed,
                        detail.isBlank() ? "(없음)" : detail,
                        finalFailed == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING
                );
            });
        }, "pcmgmt-collect-main");
        t.setDaemon(true);
        t.start();
    }

    private void updateProgress(CollectProgress p, String agentLabel, String fileStatus,
                                long bytesReceived, long totalBytes,
                                int done, int total, long startMs) {
        if (p == null) return;
        double overallFraction = total > 0 ? (double) done / total : 0;
        String etaText = computeEta(done, total, startMs);
        String fileFraction = totalBytes > 0
                ? String.format("%.0f%%", 100.0 * bytesReceived / totalBytes)
                : "";
        double fileProg = totalBytes > 0 ? (double) bytesReceived / totalBytes : -1;
        Platform.runLater(() -> {
            p.overallBar().setProgress(overallFraction);
            p.overallLabel().setText(done + " / " + total + " 완료" + (fileFraction.isBlank() ? "" : "  |  현재 " + fileFraction));
            p.currentAgentLabel().setText(agentLabel);
            p.fileBar().setProgress(fileProg);
            p.fileLabel().setText(fileStatus);
            p.etaLabel().setText(etaText);
        });
    }

    private void overallBarStageClose(CollectProgress p) {
        if (p != null && p.stage() != null) p.stage().close();
    }

    private String computeEta(int done, int total, long startMs) {
        if (done <= 0) return "남은 시간: 계산 중...";
        long elapsed = System.currentTimeMillis() - startMs;
        long perUnit = elapsed / done;
        long remaining = perUnit * (total - done);
        if (remaining < 60_000) return String.format("남은 시간: 약 %d초", remaining / 1000);
        return String.format("남은 시간: 약 %d분 %d초", remaining / 60_000, (remaining % 60_000) / 1000);
    }

    private String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    private String formatProgressPercent(long transferredBytes, long totalBytes) {
        if (totalBytes <= 0) return "계산 중";
        return String.format("%.0f%%", Math.min(100.0, (100.0 * transferredBytes) / totalBytes));
    }

    private String extractDownloadUrl(String stdout) {
        if (stdout == null || stdout.isBlank()) return "";
        // 1순위: URL 경로 직접 검색 (언어에 무관하게 안정적)
        int idx = stdout.indexOf("/api/v1/agent/uploads/");
        if (idx >= 0) {
            String after = stdout.substring(idx);
            int end = after.indexOf('\n');
            return (end >= 0 ? after.substring(0, end) : after).trim();
        }
        // 2순위: "다운로드:" 레이블 파싱 (폴백)
        idx = stdout.indexOf("다운로드:");
        if (idx >= 0) {
            String after = stdout.substring(idx + 5).trim();
            int end = after.indexOf('\n');
            return end >= 0 ? after.substring(0, end).trim() : after.trim();
        }
        return "";
    }

    // ─────────────────────────────────────────
    // 유틸
    // ─────────────────────────────────────────

    private Label styledLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        return l;
    }

    private String toCommandCode(String label) {
        if (label == null) return "";
        return switch (label) {
            case "PC 켜기(PCON)" -> "PCON";
            case "재시작" -> "REBOOT";
            case "종료" -> "SHUTDOWN";
            case "스크립트 실행" -> "RUN_PS1";
            case "파일/폴더 전송" -> "RUN_FILE_TRANSFER";
            case "파일/폴더 수집(에이전트→클라이언트)" -> "COLLECT_FILES";
            default -> label;
        };
    }

    private void showCollectPanel(String commandLabel, List<PcStatusDto> targetPcs) {
        PcStatusDto browsePc = targetPcs.stream().filter(PcStatusDto::isOnline).findFirst().orElse(null);
        if (browsePc == null) {
            showAlert("탐색 불가", "온라인 PC가 없어 원격 탐색기를 열 수 없습니다.", Alert.AlertType.WARNING);
            return;
        }

        long onlineCount = targetPcs.stream().filter(PcStatusDto::isOnline).count();
        long offlineCount = targetPcs.size() - onlineCount;
        String browsePcLabel = browsePc.getPcName() == null || browsePc.getPcName().isBlank()
                ? (browsePc.getMacAddress() == null || browsePc.getMacAddress().isBlank() ? "-" : browsePc.getMacAddress())
                : browsePc.getPcName();

        prepareSidePanel("파일/폴더 수집", "수집 실행", "닫기", COLLECT_SIDE_PANEL_WIDTH);
        sendSummaryBox.getChildren().setAll(
                styledLabel(commandLabel, "detail-section-title"),
                styledLabel("수집 예정: " + onlineCount + "대 (온라인만)", "pcmgmt-dialog-text-success"),
                offlineCount > 0
                        ? styledLabel("미수집 " + offlineCount + "대 (오프라인)", "pcmgmt-dialog-text-muted")
                        : styledLabel("전체 온라인 — 미수집 없음", "pcmgmt-dialog-text-muted"),
                styledLabel("탐색 기준 PC: " + browsePcLabel, "pcmgmt-dialog-text-muted"),
                styledLabel("선택한 경로를 대상 온라인 PC 전체에서 동일하게 수집합니다.", "pcmgmt-dialog-text-muted")
        );

        TextField currentPathField = new TextField();
        currentPathField.setEditable(false);
        currentPathField.setPromptText("(드라이브 목록)");
        currentPathField.getStyleClass().add("rfe-path-field");
        currentPathField.setMaxWidth(Double.MAX_VALUE);

        Button upButton      = new Button("↑ 상위");
        Button rootButton    = new Button("⌂ 루트");
        Button refreshButton = new Button("↻ 새로고침");
        for (Button b : new Button[]{upButton, rootButton, refreshButton}) {
            b.getStyleClass().add("rfe-nav-btn");
        }

        CheckBox usbOnlyCheck = new CheckBox("USB만");
        usbOnlyCheck.setStyle("-fx-font-size: 12px;");

        TextField pathInputField = new TextField();
        pathInputField.setPromptText("직접 경로 입력 (예: D:\\Folder)");
        pathInputField.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(pathInputField, Priority.ALWAYS);

        Button goButton = new Button("이동");
        goButton.getStyleClass().add("rfe-go-btn");

        FlowPane actionRow = new FlowPane(6, 6, upButton, rootButton, refreshButton, usbOnlyCheck);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        HBox navBar = new HBox(6, pathInputField, goButton);
        navBar.setAlignment(Pos.CENTER_LEFT);
        VBox toolbar = new VBox(8, currentPathField, actionRow, navBar);
        toolbar.getStyleClass().add("rfe-toolbar");
        toolbar.setPadding(new Insets(10, 12, 10, 12));

        Label loadingLabel = new Label("⏳ 탐색 중...");
        loadingLabel.getStyleClass().add("rfe-loading");
        loadingLabel.setMaxWidth(Double.MAX_VALUE);
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        ListView<RemoteBrowseEntry> remoteList = new ListView<>();
        remoteList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteList.getStyleClass().add("rfe-file-list");
        remoteList.setPrefHeight(280);
        remoteList.setMinHeight(240);
        remoteList.setPlaceholder(styledLabel("표시할 항목이 없습니다.", "pcmgmt-dialog-text-muted"));

        Button loadMoreButton = new Button("↓ 더 불러오기");
        loadMoreButton.getStyleClass().add("rfe-nav-btn");
        loadMoreButton.setVisible(false);
        loadMoreButton.setManaged(false);

        Label pagingStatusLabel = new Label();
        pagingStatusLabel.getStyleClass().add("rfe-status-text");

        HBox pagingRow = new HBox(8, loadMoreButton, pagingStatusLabel);
        pagingRow.setAlignment(Pos.CENTER_LEFT);
        pagingRow.setPadding(new Insets(5, 10, 5, 10));
        pagingRow.getStyleClass().add("rfe-paging-row");

        javafx.collections.ObservableList<String> selectedPathItems = FXCollections.observableArrayList();
        ListView<String> selectedPathList = new ListView<>();
        selectedPathList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectedPathList.getStyleClass().add("rfe-file-list");
        selectedPathList.setItems(selectedPathItems);
        selectedPathList.setPrefHeight(160);
        selectedPathList.setMinHeight(130);
        selectedPathList.setPlaceholder(styledLabel("아직 추가된 수집 경로가 없습니다.", "pcmgmt-dialog-text-muted"));

        Button addSelectedButton    = new Button("+ 선택 경로 추가");
        Button removeSelectedButton = new Button("− 선택 항목 제거");
        addSelectedButton.getStyleClass().add("rfe-add-btn");
        removeSelectedButton.getStyleClass().add("rfe-remove-btn");

        HBox collBtnRow = new HBox(6, addSelectedButton, removeSelectedButton);
        collBtnRow.setAlignment(Pos.CENTER_LEFT);

        TextField outputField = new TextField("collected-bundle.zip");
        outputField.setPromptText("파일명.zip");

        Label driveStatusLabel = new Label("로딩 중...");
        driveStatusLabel.getStyleClass().add("rfe-status-text");
        driveStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(driveStatusLabel, Priority.ALWAYS);

        Label estimateLabel = new Label("선택 예상 전송량: -");
        estimateLabel.getStyleClass().add("rfe-status-text");
        estimateLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(estimateLabel, Priority.ALWAYS);

        HBox driveRow    = new HBox(driveStatusLabel);
        HBox estimateRow = new HBox(estimateLabel);
        driveRow.setAlignment(Pos.CENTER_LEFT);
        estimateRow.setAlignment(Pos.CENTER_LEFT);

        VBox statusBar = new VBox(2, driveRow, estimateRow);
        statusBar.getStyleClass().add("rfe-status-bar");
        statusBar.setPadding(new Insets(5, 12, 5, 12));

        VBox root = new VBox(10,
                styledLabel("원격 파일 목록", "meta-key"),
                toolbar,
                loadingLabel,
                remoteList,
                pagingRow,
                new Separator(),
                styledLabel("수집 경로 목록", "meta-key"),
                selectedPathList,
                collBtnRow,
                styledLabel("묶음 파일명 (zip)", "meta-key"),
                outputField,
                statusBar
        );
        sendOptionsBox.getChildren().setAll(root);

        final String[] currentPath = {""};
        final String[] driveSnapshotRef = {""};
        final Set<String> usbDriveRoots = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicBoolean remoteBrowseLoading = new AtomicBoolean(false);
        final int[] nextOffsetRef = {0};
        final boolean[] hasMoreRef = {false};
        // epoch: 사용자 명시적 이동 시 증가 → 이전 자동새로고침 응답을 stale로 폐기
        final int[] browseEpoch = {0};

        Runnable loadRoot = () -> loadRemoteEntriesAsync(
                browsePc, "", 0, REMOTE_LIST_PAGE_SIZE, false,
                currentPathField, remoteList, currentPath, usbOnlyCheck,
                driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                loadMoreButton, pagingStatusLabel, loadingLabel,
                nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, browseEpoch[0]
        );
        Runnable refresh = () -> loadRemoteEntriesAsync(
                browsePc, currentPath[0], 0, REMOTE_LIST_PAGE_SIZE, false,
                currentPathField, remoteList, currentPath, usbOnlyCheck,
                driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                loadMoreButton, pagingStatusLabel, loadingLabel,
                nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, browseEpoch[0]
        );
        Runnable loadMore = () -> {
            if (!hasMoreRef[0]) return;
            loadRemoteEntriesAsync(
                    browsePc, currentPath[0], nextOffsetRef[0], REMOTE_LIST_PAGE_SIZE, true,
                    currentPathField, remoteList, currentPath, usbOnlyCheck,
                    driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                    loadMoreButton, pagingStatusLabel, loadingLabel,
                    nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, browseEpoch[0]
            );
        };
        loadRoot.run();

        goButton.setOnAction(e -> {
            String p = pathInputField.getText() == null ? "" : pathInputField.getText().trim();
            currentPath[0] = p;
            int epoch = ++browseEpoch[0];
            remoteBrowseLoading.set(false);
            loadRemoteEntriesAsync(
                    browsePc, p, 0, REMOTE_LIST_PAGE_SIZE, false,
                    currentPathField, remoteList, currentPath, usbOnlyCheck,
                    driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                    loadMoreButton, pagingStatusLabel, loadingLabel,
                    nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, epoch
            );
        });
        rootButton.setOnAction(e -> {
            currentPath[0] = "";
            int epoch = ++browseEpoch[0];
            remoteBrowseLoading.set(false);
            loadRemoteEntriesAsync(
                    browsePc, "", 0, REMOTE_LIST_PAGE_SIZE, false,
                    currentPathField, remoteList, currentPath, usbOnlyCheck,
                    driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                    loadMoreButton, pagingStatusLabel, loadingLabel,
                    nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, epoch
            );
        });
        upButton.setOnAction(e -> {
            String parent = parentPath(currentPath[0]);
            currentPath[0] = parent;
            int epoch = ++browseEpoch[0];
            remoteBrowseLoading.set(false);
            loadRemoteEntriesAsync(
                    browsePc, parent, 0, REMOTE_LIST_PAGE_SIZE, false,
                    currentPathField, remoteList, currentPath, usbOnlyCheck,
                    driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                    loadMoreButton, pagingStatusLabel, loadingLabel,
                    nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, epoch
            );
        });
        loadMoreButton.setOnAction(e -> loadMore.run());
        refreshButton.setOnAction(e -> refresh.run());
        usbOnlyCheck.selectedProperty().addListener((obs, oldV, newV) -> refresh.run());

        ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pcmgmt-remote-browse-refresh");
            t.setDaemon(true);
            return t;
        });
        refreshScheduler.scheduleAtFixedRate(() -> {
            if (sendPanel == null || !sendPanel.isVisible() || sendPanel.getScene() == null) {
                return;
            }
            Platform.runLater(() -> {
                if (currentPath[0] != null && !currentPath[0].isBlank()) {
                    return;
                }
                int epoch = browseEpoch[0];
                loadRemoteEntriesAsync(
                        browsePc, "", 0, REMOTE_LIST_PAGE_SIZE, false,
                        currentPathField, remoteList, currentPath, usbOnlyCheck,
                        driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                        loadMoreButton, pagingStatusLabel, loadingLabel,
                        nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, epoch
                );
            });
        }, 4, 4, TimeUnit.SECONDS);
        sidePanelCleanup = refreshScheduler::shutdownNow;

        addSelectedButton.setOnAction(e -> {
            List<RemoteBrowseEntry> selected = new ArrayList<>(remoteList.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                return;
            }
            SelectionEstimate estimate = estimateSelection(selected, currentPath[0], usbDriveRoots);
            if (!confirmLargeUsbSelection(estimate)) {
                return;
            }
            Set<String> existing = new LinkedHashSet<>(selectedPathList.getItems());
            for (RemoteBrowseEntry entry : selected) {
                existing.add(resolveEntryAbsolutePath(currentPath[0], entry));
            }
            selectedPathItems.setAll(existing);
        });
        removeSelectedButton.setOnAction(e -> {
            List<String> selected = new ArrayList<>(selectedPathList.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                return;
            }
            List<String> next = new ArrayList<>(selectedPathItems);
            next.removeAll(selected);
            selectedPathItems.setAll(next);
        });

        remoteList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() < 2) return;
            javafx.scene.Node n = (javafx.scene.Node) ev.getTarget();
            while (n != null && !(n instanceof javafx.scene.control.ListCell)) n = n.getParent();
            if (!(n instanceof javafx.scene.control.ListCell)) return;
            @SuppressWarnings("unchecked")
            RemoteBrowseEntry entry = (RemoteBrowseEntry) ((javafx.scene.control.ListCell<?>) n).getItem();
            if (entry == null) return;
            if (!"dir".equalsIgnoreCase(entry.type) && !"drive".equalsIgnoreCase(entry.type)) return;
            String nextPath = resolveEntryAbsolutePath(currentPath[0], entry);
            currentPath[0] = nextPath;
            int epoch = ++browseEpoch[0];
            remoteBrowseLoading.set(false);
            loadRemoteEntriesAsync(
                    browsePc, nextPath, 0, REMOTE_LIST_PAGE_SIZE, false,
                    currentPathField, remoteList, currentPath, usbOnlyCheck,
                    driveStatusLabel, driveSnapshotRef, usbDriveRoots,
                    loadMoreButton, pagingStatusLabel, loadingLabel,
                    nextOffsetRef, hasMoreRef, remoteBrowseLoading, browseEpoch, epoch
            );
        });

        remoteList.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<RemoteBrowseEntry>) change -> {
            List<RemoteBrowseEntry> selected = new ArrayList<>(remoteList.getSelectionModel().getSelectedItems());
            SelectionEstimate estimate = estimateSelection(selected, currentPath[0], usbDriveRoots);
            estimateLabel.setText(formatEstimateLabel(estimate));
        });

        sendConfirmBtn.disableProperty().bind(Bindings.isEmpty(selectedPathItems));
        sendConfirmBtn.setOnAction(ev -> {
            List<String> paths = selectedPathItems.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            if (paths.isEmpty()) {
                showAlert("입력 오류", "수집할 파일/폴더를 1개 이상 선택하세요.", Alert.AlertType.WARNING);
                return;
            }

            String outputName = outputField.getText() == null ? "" : outputField.getText().trim();
            if (outputName.isBlank()) {
                outputName = "collected-bundle.zip";
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("paths", paths);
                payload.put("outputName", outputName);
                String collectPayloadJson = objectMapper.writeValueAsString(payload);
                closeSendPanel();
                executeCommand("COLLECT_FILES", targetPcs, ScriptExecutionOptions.none(), collectPayloadJson);
            } catch (Exception e) {
                showAlert("직렬화 오류", "수집 요청 생성에 실패했습니다.\n" + e.getMessage(), Alert.AlertType.ERROR);
            }
        });

        pathInputField.setOnAction(e -> goButton.fire());
        openSidePanel(COLLECT_SIDE_PANEL_WIDTH);
    }

    private void loadRemoteEntriesAsync(PcStatusDto browsePc,
                                        String path,
                                        int offset,
                                        int limit,
                                        boolean append,
                                        TextField currentPathField,
                                        ListView<RemoteBrowseEntry> remoteList,
                                        String[] currentPathRef,
                                        CheckBox usbOnlyCheck,
                                        Label driveStatusLabel,
                                        String[] driveSnapshotRef,
                                        Set<String> usbDriveRoots,
                                        Button loadMoreButton,
                                        Label pagingStatusLabel,
                                        Label loadingLabel,
                                        int[] nextOffsetRef,
                                        boolean[] hasMoreRef,
                                        AtomicBoolean remoteBrowseLoading,
                                        int[] epochRef,
                                        int expectedEpoch) {
        if (remoteBrowseLoading != null && !remoteBrowseLoading.compareAndSet(false, true)) {
            return;
        }
        if (loadingLabel != null) {
            Platform.runLater(() -> { loadingLabel.setVisible(true); loadingLabel.setManaged(true); });
        }
        Thread t = new Thread(() -> {
            try {
                Map<String, Object> resp = requestRemoteFileList(browsePc, path, offset, limit);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entriesRaw = (List<Map<String, Object>>) resp.getOrDefault("entries", List.of());
                String currentPath = String.valueOf(resp.getOrDefault("currentPath", ""));
                int nextOffset = toInt(resp.get("nextOffset"), offset + entriesRaw.size());
                boolean hasMore = toBoolean(resp.get("hasMore"), false);
                long returnedEntries = toLong(resp.get("returnedEntries"));
                if (returnedEntries <= 0 && !entriesRaw.isEmpty()) {
                    returnedEntries = entriesRaw.size();
                }
                long totalEntries = toLong(resp.get("totalEntries"));
                if (totalEntries <= 0) {
                    totalEntries = hasMore ? Math.max(nextOffset, returnedEntries) : Math.max(returnedEntries, nextOffset);
                }
                boolean partial = toBoolean(resp.get("partial"), false)
                        || toBoolean(resp.get("scanLimitReached"), false)
                        || toBoolean(resp.get("timeLimitReached"), false);
                long warningsCount = toLong(resp.get("warningsCount"));
                String warningPreview = extractWarningPreview(resp.get("warningsPreview"));
                List<RemoteBrowseEntry> entries = entriesRaw.stream()
                        .map(row -> new RemoteBrowseEntry(
                                String.valueOf(row.getOrDefault("name", "")),
                                String.valueOf(row.getOrDefault("type", "file")),
                                toLong(row.get("size")),
                                String.valueOf(row.getOrDefault("displayName", row.getOrDefault("name", ""))),
                                String.valueOf(row.getOrDefault("driveType", "")),
                                String.valueOf(row.getOrDefault("label", "")),
                                toLong(row.get("totalBytes")),
                                toLong(row.get("freeBytes")),
                                toBoolean(row.get("mounted"), true),
                                toBoolean(row.get("usb"), false)
                        ))
                        .toList();

                boolean rootLevel = currentPath == null || currentPath.isBlank();
                List<RemoteBrowseEntry> displayEntries = entries;
                if (rootLevel && usbOnlyCheck != null && usbOnlyCheck.isSelected()) {
                    displayEntries = entries.stream()
                            .filter(RemoteBrowseEntry::isUsbDrive)
                            .toList();
                }
                int displayNextOffset = hasMore ? Math.max(nextOffset, offset + displayEntries.size()) : nextOffset;
                final List<RemoteBrowseEntry> displayEntriesFinal = displayEntries;
                String nextSnapshot = rootLevel ? snapshotDriveNames(entries) : driveSnapshotRef[0];
                String changeMessage = rootLevel ? describeDriveChanges(driveSnapshotRef[0], nextSnapshot) : "";
                String driveSummary = rootLevel ? buildDriveSummary(entries, usbOnlyCheck != null && usbOnlyCheck.isSelected()) : "";
                long returnedEntriesFinal = returnedEntries;
                long totalEntriesFinal = totalEntries;
                boolean hasMoreFinal = hasMore;
                boolean partialFinal = partial;
                long warningsCountFinal = warningsCount;
                String warningPreviewFinal = warningPreview;
                int displayNextOffsetFinal = displayNextOffset;

                Platform.runLater(() -> {
                    // stale 응답 폐기: 더 최신 이동 요청이 있으면 무시
                    if (epochRef != null && epochRef[0] != expectedEpoch) return;
                    currentPathRef[0] = currentPath;
                    currentPathField.setText(currentPath == null ? "" : currentPath);
                    if (append) {
                        List<RemoteBrowseEntry> merged = new ArrayList<>(remoteList.getItems());
                        merged.addAll(displayEntriesFinal);
                        remoteList.setItems(FXCollections.observableArrayList(merged));
                    } else {
                        remoteList.setItems(FXCollections.observableArrayList(displayEntriesFinal));
                    }
                    if (rootLevel) {
                        if (usbDriveRoots != null) {
                            usbDriveRoots.clear();
                            entries.stream()
                                    .filter(RemoteBrowseEntry::isUsbDrive)
                                    .map(e -> e.name)
                                    .filter(Objects::nonNull)
                                    .forEach(usbDriveRoots::add);
                        }
                        driveSnapshotRef[0] = nextSnapshot;
                        if (driveStatusLabel != null) {
                            if (!changeMessage.isBlank()) {
                                driveStatusLabel.setText("드라이브 변경 감지: " + changeMessage);
                            } else {
                                driveStatusLabel.setText(driveSummary);
                            }
                        }
                    }
                    if (nextOffsetRef != null && nextOffsetRef.length > 0) {
                        nextOffsetRef[0] = Math.max(0, displayNextOffsetFinal);
                    }
                    if (hasMoreRef != null && hasMoreRef.length > 0) {
                        hasMoreRef[0] = hasMoreFinal;
                    }
                    if (loadMoreButton != null) {
                        loadMoreButton.setVisible(hasMoreFinal);
                        loadMoreButton.setManaged(hasMoreFinal);
                        loadMoreButton.setDisable(!hasMoreFinal);
                    }
                    if (pagingStatusLabel != null) {
                        pagingStatusLabel.setText(buildPagingStatusText(
                                returnedEntriesFinal,
                                totalEntriesFinal,
                                displayNextOffsetFinal,
                                hasMoreFinal,
                                partialFinal,
                                warningsCountFinal,
                                warningPreviewFinal
                        ));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert("원격 탐색 실패", ex.getMessage(), Alert.AlertType.ERROR));
            } finally {
                if (remoteBrowseLoading != null) remoteBrowseLoading.set(false);
                if (loadingLabel != null) Platform.runLater(() -> {
                    loadingLabel.setVisible(false);
                    loadingLabel.setManaged(false);
                });
            }
        }, "pcmgmt-remote-browse");
        t.setDaemon(true);
        t.start();
    }

    private Map<String, Object> requestRemoteFileList(PcStatusDto browsePc, String path, int offset, int limit) throws Exception {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("scope", "SINGLE");
        command.put("macAddress", browsePc.getMacAddress());
        command.put("type", "LIST_FILES");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path == null ? "" : path);
        payload.put("offset", Math.max(0, offset));
        payload.put("limit", Math.max(1, limit));
        command.put("payloadJson", objectMapper.writeValueAsString(payload));

        String enqueueResp = apiClient.postJson("/commands", command).join();
        Map<String, Object> enqueueBody = objectMapper.readValue(enqueueResp, new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Object> taskIds = (List<Object>) enqueueBody.getOrDefault("taskIds", List.of());
        if (taskIds.isEmpty()) {
            throw new IllegalStateException("원격 탐색 명령 큐 등록 실패");
        }
        long taskId = Long.parseLong(String.valueOf(taskIds.get(0)));

        int maxPoll = Math.max(1, (REMOTE_LIST_TIMEOUT_SECONDS * 1000) / REMOTE_LIST_POLL_INTERVAL_MS);
        String lastStatus = "";
        String lastErrorMessage = "";
        for (int i = 0; i < maxPoll; i++) {
            Map<String, Object> row;
            try {
                row = apiClient.getJson("/commands?taskId=" + taskId,
                        new TypeReference<Map<String, Object>>() {}).join();
                lastErrorMessage = "";
            } catch (Exception pollEx) {
                Throwable cause = ApiClient.unwrapFailure(pollEx);
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("원격 목록 조회가 중단되었습니다.");
                }
                if (cause instanceof ApiClientException apiEx
                        && apiEx.getStatusCode() >= 400
                        && apiEx.getStatusCode() < 500
                        && apiEx.getStatusCode() != 429) {
                    throw new IllegalStateException(apiEx.getMessage());
                }
                lastErrorMessage = cause == null || cause.getMessage() == null
                        ? "명령 상태 조회 중 통신 오류"
                        : cause.getMessage();
                Thread.sleep(REMOTE_LIST_POLL_INTERVAL_MS);
                continue;
            }

            String status = String.valueOf(row.getOrDefault("status", ""));
            if (status != null && !status.isBlank()) {
                lastStatus = status;
            }
            if ("COMPLETED".equalsIgnoreCase(status)) {
                String stdout = row.get("stdout") instanceof String value ? value : "";
                if (stdout.isBlank()) {
                    throw new IllegalStateException("원격 목록 응답이 비어 있습니다.");
                }
                try {
                    return objectMapper.readValue(stdout, new TypeReference<Map<String, Object>>() {});
                } catch (Exception parseEx) {
                    throw new IllegalStateException(
                            "원격 목록 응답 파싱 실패 (응답이 너무 크거나 손상되었을 수 있습니다): " + parseEx.getMessage());
                }
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                String err = row.get("stderr") instanceof String value ? value : "";
                throw new IllegalStateException(err.isBlank() ? "원격 목록 조회 실패" : err);
            }
            Thread.sleep(REMOTE_LIST_POLL_INTERVAL_MS);
        }
        String detail;
        if (lastErrorMessage != null && !lastErrorMessage.isBlank()) {
            detail = lastErrorMessage;
        } else if (lastStatus != null && !lastStatus.isBlank()) {
            detail = "마지막 상태: " + lastStatus;
        } else {
            detail = "명령 응답 지연";
        }
        throw new IllegalStateException("원격 목록 조회 타임아웃 (" + detail + ")");
    }

    private String resolveEntryAbsolutePath(String currentPath, RemoteBrowseEntry entry) {
        if (entry == null) {
            return currentPath == null ? "" : currentPath;
        }
        if ("drive".equalsIgnoreCase(entry.type)) {
            return entry.name;
        }
        if (currentPath == null || currentPath.isBlank()) {
            return entry.name;
        }
        // "C:" is a relative path in Java — normalize to "C:\" before resolving.
        String base = currentPath.matches("[A-Za-z]:") ? currentPath + "\\" : currentPath;
        try {
            return Path.of(base).resolve(entry.name).normalize().toString();
        } catch (Exception ex) {
            String sep = base.endsWith("\\") || base.endsWith("/") ? "" : "\\";
            return base + sep + entry.name;
        }
    }

    private String parentPath(String currentPath) {
        if (currentPath == null || currentPath.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(currentPath);
            Path parent = p.getParent();
            return parent == null ? "" : parent.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0L;
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private String extractWarningPreview(Object warningPayload) {
        if (warningPayload instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? "" : String.valueOf(first);
        }
        if (warningPayload == null) {
            return "";
        }
        String value = String.valueOf(warningPayload);
        return value == null ? "" : value.trim();
    }

    private String buildPagingStatusText(long returnedEntries,
                                         long totalEntries,
                                         int nextOffset,
                                         boolean hasMore,
                                         boolean partial,
                                         long warningsCount,
                                         String warningPreview) {
        long shown = hasMore ? Math.max(0L, nextOffset) : Math.max(0L, Math.max(totalEntries, nextOffset));
        long total = Math.max(totalEntries, shown);
        String base = "원격 항목: " + shown + " / " + total + "개";
        if (hasMore) {
            base += " (더 불러오기 가능)";
        }
        if (partial) {
            base += " / 일부 항목 생략";
        }
        if (warningsCount > 0) {
            String preview = warningPreview == null ? "" : warningPreview.trim();
            if (!preview.isBlank()) {
                base += " / 경고 " + warningsCount + "건 (" + preview + ")";
            } else {
                base += " / 경고 " + warningsCount + "건";
            }
        }
        return base;
    }

    private String snapshotDriveNames(List<RemoteBrowseEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        return entries.stream()
                .filter(RemoteBrowseEntry::isDrive)
                .map(RemoteBrowseEntry::stableKey)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining("\u001F"));
    }

    private String describeDriveChanges(String previousSnapshot, String currentSnapshot) {
        Set<String> prev = parseSnapshot(previousSnapshot);
        Set<String> now = parseSnapshot(currentSnapshot);
        if (prev.equals(now)) {
            return "";
        }
        Set<String> added = new LinkedHashSet<>(now);
        added.removeAll(prev);
        Set<String> removed = new LinkedHashSet<>(prev);
        removed.removeAll(now);

        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add("+ " + String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            parts.add("- " + String.join(", ", removed));
        }
        return String.join(" / ", parts);
    }

    private Set<String> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return new LinkedHashSet<>();
        }
        String[] parts = snapshot.split("\u001F");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private String buildDriveSummary(List<RemoteBrowseEntry> entries, boolean usbOnly) {
        long driveCount = entries.stream().filter(RemoteBrowseEntry::isDrive).count();
        long usbCount = entries.stream().filter(RemoteBrowseEntry::isUsbDrive).count();
        long mountedCount = entries.stream().filter(RemoteBrowseEntry::isDrive).filter(e -> e.mounted).count();
        if (driveCount == 0) {
            return usbOnly ? "드라이브 상태: USB 드라이브가 없습니다." : "드라이브 상태: 드라이브가 없습니다.";
        }
        if (usbOnly) {
            return String.format(Locale.ROOT, "드라이브 상태: USB %d개 / 연결됨 %d개", usbCount, mountedCount);
        }
        return String.format(Locale.ROOT, "드라이브 상태: 전체 %d개 (USB %d개) / 연결됨 %d개", driveCount, usbCount, mountedCount);
    }

    private SelectionEstimate estimateSelection(List<RemoteBrowseEntry> selected,
                                                String currentPath,
                                                Set<String> usbDriveRoots) {
        if (selected == null || selected.isEmpty()) {
            return new SelectionEstimate(0L, false, false);
        }
        long knownBytes = 0L;
        boolean hasUnknownSize = false;
        boolean usbContext = isPathUnderUsbRoot(currentPath, usbDriveRoots);
        boolean hasUsbSelection = usbContext;

        for (RemoteBrowseEntry entry : selected) {
            if (entry == null) {
                continue;
            }
            if (entry.isUsbDrive()) {
                hasUsbSelection = true;
            }
            if ("file".equalsIgnoreCase(entry.type)) {
                knownBytes += Math.max(0L, entry.size);
            } else {
                hasUnknownSize = true;
            }
        }
        return new SelectionEstimate(knownBytes, hasUnknownSize, hasUsbSelection);
    }

    private boolean isPathUnderUsbRoot(String currentPath, Set<String> usbDriveRoots) {
        if (currentPath == null || currentPath.isBlank() || usbDriveRoots == null || usbDriveRoots.isEmpty()) {
            return false;
        }
        String normalized = currentPath.trim().toLowerCase(Locale.ROOT);
        for (String root : usbDriveRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String rootNorm = root.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith(rootNorm)) {
                return true;
            }
        }
        return false;
    }

    private String formatEstimateLabel(SelectionEstimate estimate) {
        if (estimate == null || estimate.isEmpty()) {
            return "선택 예상 전송량: -";
        }
        String sizeText = estimate.knownBytes > 0
                ? formatFileSize(estimate.knownBytes)
                : (estimate.hasUnknownSize ? "크기 계산 중" : "0 B");
        String etaText = estimate.knownBytes > 0
                ? estimateDurationLabel(estimate.knownBytes)
                : (estimate.hasUnknownSize ? "대용량 가능(시간 미확정)" : "-");
        String usbText = estimate.usbSelection ? "USB 경로" : "일반 경로";
        return "선택 예상 전송량: " + sizeText + " / 예상 시간: " + etaText + " / " + usbText;
    }

    private String estimateDurationLabel(long bytes) {
        if (bytes <= 0) {
            return "-";
        }
        double seconds = bytes / (ESTIMATED_TRANSFER_MB_PER_SEC * 1024.0 * 1024.0);
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.0f초", seconds);
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%.1f분", seconds / 60.0);
        }
        return String.format(Locale.ROOT, "%.1f시간", seconds / 3600.0);
    }

    private boolean confirmLargeUsbSelection(SelectionEstimate estimate) {
        if (estimate == null || !estimate.usbSelection) {
            return true;
        }
        boolean largeByBytes = estimate.knownBytes >= LARGE_TRANSFER_WARNING_BYTES;
        boolean uncertainLarge = estimate.hasUnknownSize;
        if (!largeByBytes && !uncertainLarge) {
            return true;
        }

        String sizeText = estimate.knownBytes > 0 ? formatFileSize(estimate.knownBytes) : "미확인";
        String etaText = estimate.knownBytes > 0 ? estimateDurationLabel(estimate.knownBytes) : "미확인";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("USB 대용량 전송 주의");
        confirm.setHeaderText("선택한 USB 경로는 전송 시간이 길어질 수 있습니다.");
        confirm.setContentText(
                "예상 크기: " + sizeText + "\n"
                        + "예상 전송 시간(약 " + ESTIMATED_TRANSFER_MB_PER_SEC + "MB/s): " + etaText + "\n"
                        + "네트워크 상태에 따라 더 오래 걸릴 수 있습니다. 계속 진행할까요?"
        );
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private String mapDestinationMode(String value) {
        if ("바탕화면".equals(value)) return "DESKTOP";
        if ("경로 직접 입력".equals(value)) return "CUSTOM";
        return "DOWNLOADS";
    }

    private TransferSource chooseTransferSource() {
        return chooseMultipleTransferSources();
    }

    /**
     * 다중 파일/폴더 선택 UI
     * 좌측: 목록(파일명/용량/유형/실행), 우측: 버튼, 하단: 목적지 옵션
     */
    private TransferSource chooseMultipleTransferSources() {
        List<SelectableTransferItem> items = new ArrayList<>();
        Dialog<ButtonType> selectDialog = new Dialog<>();
        selectDialog.setTitle("파일/폴더 전송");
        selectDialog.setWidth(850);
        selectDialog.setHeight(650);

        VBox mainContent;
        VBox itemsList;
        Button addFileBtn;
        Button addFolderBtn;
        Button removeBtn;
        RadioButton downloadRadio;
        RadioButton desktopRadio;
        RadioButton customRadio;
        TextField customPathField;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pc-transfer-select-dialog.fxml"));
            mainContent = loader.load();
            itemsList = (VBox) loader.getNamespace().get("itemsList");
            addFileBtn = (Button) loader.getNamespace().get("addFileBtn");
            addFolderBtn = (Button) loader.getNamespace().get("addFolderBtn");
            removeBtn = (Button) loader.getNamespace().get("removeBtn");
            downloadRadio = (RadioButton) loader.getNamespace().get("downloadRadio");
            desktopRadio = (RadioButton) loader.getNamespace().get("desktopRadio");
            customRadio = (RadioButton) loader.getNamespace().get("customRadio");
            customPathField = (TextField) loader.getNamespace().get("customPathField");
        } catch (Exception e) {
            throw new IllegalStateException("파일/폴더 전송 다이얼로그 FXML 로딩 실패", e);
        }

        ToggleGroup destGroup = new ToggleGroup();
        downloadRadio.setToggleGroup(destGroup);
        desktopRadio.setToggleGroup(destGroup);
        customRadio.setToggleGroup(destGroup);
        downloadRadio.setSelected(true);
        customPathField.setDisable(true);
        customRadio.selectedProperty().addListener((obs, old, newVal) -> customPathField.setDisable(!newVal));

        addFileBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("파일 선택 (다중 선택 가능)");
            List<File> chosen = fc.showOpenMultipleDialog(null);
            if (chosen != null && !chosen.isEmpty()) {
                for (File f : chosen) {
                    items.add(new SelectableTransferItem("file", f.getName(), f.getAbsolutePath(), true, "", f.length(), false));
                }
                refreshItemsList(itemsList, items);
            }
        });

        addFolderBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("폴더 선택");
            File d = dc.showDialog(null);
            if (d != null) {
                long folderSize = calculateFolderSize(d.toPath());
                SelectableTransferItem item = new SelectableTransferItem("folder", d.getName(), d.getAbsolutePath(), true, "", folderSize, false);
                items.add(item);
                refreshItemsList(itemsList, items);
            }
        });

        removeBtn.setOnAction(e -> {
            if (!items.isEmpty()) {
                items.clear();
                refreshItemsList(itemsList, items);
            }
        });
        
        selectDialog.getDialogPane().setContent(mainContent);
        selectDialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        
        Button okBtn = (Button) selectDialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okBtn != null) {
            okBtn.setText("확인");
        }
        
        Optional<ButtonType> result = selectDialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return null;
        }
        
        if (items.isEmpty()) {
            showAlert("선택 오류", "전송할 파일 또는 폴더를 최소 1개 이상 선택하세요.", Alert.AlertType.WARNING);
            return null;
        }
        
        // zip 생성 및 반환 (사용자가 선택한 목적지 옵션 포함)
        try {
            Path zipPath = createMultiTransferZip(items);
            String destMode = downloadRadio.isSelected() ? "DOWNLOADS" : (desktopRadio.isSelected() ? "DESKTOP" : "CUSTOM");
            String destPath = customRadio.isSelected() ? customPathField.getText() : "";
            boolean runAfter = items.stream().anyMatch(SelectableTransferItem::runAfterTransfer);
            
            TransferSource source = new TransferSource(zipPath, "multi-transfer", "MultiTransfer.zip",
                    true, true, destMode, destPath, runAfter, null);
            return source;
        } catch (IOException ex) {
            showAlert("압축 실패", "다중 파일 압축 중 오류가 발생했습니다.\n" + ex.getMessage(), Alert.AlertType.ERROR);
            return null;
        }
    }
    
    /** 항목 목록 UI 갱신 */
    private void refreshItemsList(VBox itemsList, List<SelectableTransferItem> items) {
        itemsList.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            SelectableTransferItem item = items.get(i);
            HBox itemRow;
            Label nameLabel;
            Label sizeLabel;
            Label typeLabel;
            CheckBox runCheck;
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pc-transfer-item-row.fxml"));
                itemRow = loader.load();
                nameLabel  = (Label)    loader.getNamespace().get("nameLabel");
                sizeLabel  = (Label)    loader.getNamespace().get("sizeLabel");
                typeLabel  = (Label)    loader.getNamespace().get("typeLabel");
                runCheck   = (CheckBox) loader.getNamespace().get("runCheck");
            } catch (Exception ex) {
                itemRow  = new HBox(10);
                nameLabel = new Label(); nameLabel.setMinWidth(120); nameLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(nameLabel, Priority.ALWAYS); nameLabel.getStyleClass().add("pcmgmt-transfer-item-name");
                sizeLabel = new Label(); sizeLabel.setMinWidth(80); sizeLabel.setPrefWidth(90);  sizeLabel.getStyleClass().add("pcmgmt-transfer-item-size");
                typeLabel = new Label(); typeLabel.setMinWidth(40); typeLabel.setPrefWidth(50);  typeLabel.getStyleClass().add("pcmgmt-transfer-item-type");
                runCheck  = new CheckBox("실행");
                itemRow.getChildren().addAll(nameLabel, sizeLabel, typeLabel, runCheck);
            }
            String typeIcon = item.type().equals("file") ? "📄" : "📁";
            nameLabel.setText(typeIcon + " " + item.displayName());
            sizeLabel.setText(formatFileSize(item.fileSize()));
            typeLabel.setText(item.type().equals("file") ? "파일" : "폴더");
            runCheck.setSelected(item.runAfterTransfer());
            final int index = i;
            runCheck.selectedProperty().addListener((obs, old, newVal) -> items.get(index).setRunAfterTransfer(newVal));
            Button deleteBtn = new Button("✕");
            deleteBtn.setStyle("-fx-text-fill: #cc3333; -fx-background-color: transparent; -fx-cursor: hand; -fx-font-size: 13px;");
            deleteBtn.setOnAction(ev -> {
                if (index < items.size()) {
                    items.remove(index);
                    refreshItemsList(itemsList, items);
                }
            });
            itemRow.getChildren().add(deleteBtn);
            itemsList.getChildren().add(itemRow);
        }
    }

    private long calculateFolderSize(Path folder) {
        try {
            return Files.walk(folder)
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", gb);
    }

    /**
     * 선택된 파일/폴더들을 경로 구조대로 zip으로 압축
     */
    private Path createMultiTransferZip(List<SelectableTransferItem> items) throws IOException {
        Path tmpZip = Files.createTempFile("pcmgmt-multi-transfer-", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmpZip))) {
            for (SelectableTransferItem item : items) {
                Path sourcePath = Path.of(item.sourcePath());
                if ("file".equals(item.type())) {
                    String entryName = item.displayName();
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);
                    Files.copy(sourcePath, zos);
                    zos.closeEntry();
                } else if ("folder".equals(item.type())) {
                    Path base = sourcePath.toAbsolutePath().normalize();
                    String folderRoot = item.displayName();
                    Files.walk(base)
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            Path rel = base.relativize(path);
                            String entryName = folderRoot + "/" + rel.toString().replace('\\', '/');
                            ZipEntry entry = new ZipEntry(entryName);
                            try {
                                zos.putNextEntry(entry);
                                Files.copy(path, zos);
                                zos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                }
            }
        } catch (UncheckedIOException ex) {
            Files.deleteIfExists(tmpZip);
            throw ex.getCause();
        }

        return tmpZip;
    }

    /** 수집 완료 결과를 스크롤 가능한 TextArea로 표시 (긴 경로 짤림 방지) */
    private void showCollectResultDialog(String title, int done, int failed, String pathDetail, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(String.format("성공: %d대%s", done,
                failed > 0 ? " / 실패: " + failed + "대" : ""));

        TextArea area = new TextArea("저장된 파일:\n" + pathDetail);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(Math.min(savedPathsLineCount(pathDetail), 12));
        area.setPrefColumnCount(60);
        area.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        area.setMaxWidth(Double.MAX_VALUE);
        area.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(area, Priority.ALWAYS);
        GridPane.setHgrow(area, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(area, 0, 0);

        alert.getDialogPane().setExpandableContent(expContent);
        alert.getDialogPane().setExpanded(true);
        alert.getDialogPane().setPrefWidth(680);
        alert.showAndWait();
    }

    private int savedPathsLineCount(String detail) {
        return (int) detail.chars().filter(c -> c == '\n').count() + 1;
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /** 탭 선택 시 호출 */
    public void reloadData() {
        loadAllData();
    }

    private record TransferSource(Path path,
                                  String sourceType,
                                  String displayName,
                                  boolean zipped,
                                  boolean temporaryArtifact,
                                  String preDestMode,
                                  String preDestPath,
                                  boolean preRunAfter,
                                  String scriptUrl) {
        // Compact constructor for backward compatibility
        public static TransferSource simple(Path path, String sourceType, String displayName, boolean zipped, boolean temporaryArtifact) {
            return new TransferSource(path, sourceType, displayName, zipped, temporaryArtifact,
                    "DOWNLOADS", "", false, null);
        }
    }

    private record ScriptExecutionOptions(String scriptPath,
                                          String scriptUrl,
                                          String runMode,
                                          String windowMode,
                                          String args,
                                          boolean transferCommand,
                                          String sourceType,
                                          String displayName,
                                          String destinationMode,
                                          String destinationPath,
                                          boolean runAfterTransfer,
                                          boolean zipped,
                                          boolean temporaryArtifact) {
        private static ScriptExecutionOptions none() {
            return new ScriptExecutionOptions(null, null, "FOREGROUND", "HIDDEN", "", false,
                    "file", "", "DOWNLOADS", "", false, false, false);
        }

        private boolean hasScript() {
            return hasLocalScriptPath() || hasRemoteScriptUrl();
        }

        private boolean hasLocalScriptPath() {
            return scriptPath != null && !scriptPath.isBlank();
        }

        private boolean hasRemoteScriptUrl() {
            return scriptUrl != null && !scriptUrl.isBlank();
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runMode", runMode == null || runMode.isBlank() ? "FOREGROUND" : runMode);
            payload.put("windowMode", windowMode == null || windowMode.isBlank() ? "HIDDEN" : windowMode);
            if (args != null && !args.isBlank()) {
                payload.put("args", args);
            }
            if (transferCommand) {
                payload.put("transferMode", true);
                payload.put("sourceType", sourceType == null || sourceType.isBlank() ? "file" : sourceType);
                payload.put("displayName", displayName == null ? "" : displayName);
                payload.put("destinationMode", destinationMode == null || destinationMode.isBlank() ? "DOWNLOADS" : destinationMode);
                if (destinationPath != null && !destinationPath.isBlank()) {
                    payload.put("destinationPath", destinationPath);
                }
                payload.put("runAfterTransfer", runAfterTransfer);
                payload.put("zipped", zipped);
            }
            return payload;
        }
    }

    private static class RemoteBrowseEntry {
        private final String name;
        private final String type;
        private final long size;
        private final String displayName;
        private final String driveType;
        private final String label;
        private final long totalBytes;
        private final long freeBytes;
        private final boolean mounted;
        private final boolean usb;

        private RemoteBrowseEntry(String name,
                                  String type,
                                  long size,
                                  String displayName,
                                  String driveType,
                                  String label,
                                  long totalBytes,
                                  long freeBytes,
                                  boolean mounted,
                                  boolean usb) {
            this.name = name;
            this.type = type;
            this.size = size;
            this.displayName = displayName;
            this.driveType = driveType;
            this.label = label;
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
            this.mounted = mounted;
            this.usb = usb;
        }

        private boolean isDrive() {
            return "drive".equalsIgnoreCase(type);
        }

        private boolean isUsbDrive() {
            return isDrive() && (usb || "USB".equalsIgnoreCase(driveType));
        }

        private String stableKey() {
            String base = label == null || label.isBlank() ? name : label;
            return base + "|" + (mounted ? "ON" : "OFF");
        }

        @Override
        public String toString() {
            String icon = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                case "drive" -> switch ((driveType == null ? "" : driveType).toUpperCase(Locale.ROOT)) {
                    case "USB" -> "🔌";
                    case "NETWORK" -> "🌐";
                    default -> "💽";
                };
                case "dir" -> "📁";
                default -> "📄";
            };
            if ("drive".equalsIgnoreCase(type)) {
                String nameText = (displayName != null && !displayName.isBlank())
                        ? displayName
                        : (label != null && !label.isBlank() ? label : name);
                String kind = (driveType == null || driveType.isBlank()) ? "DRIVE" : driveType.toUpperCase(Locale.ROOT);
                String mountedText = mounted ? "연결됨" : "분리됨";
                String capText = totalBytes > 0
                        ? " (" + formatSize(freeBytes) + " / " + formatSize(totalBytes) + ")"
                        : "";
                return icon + " " + nameText + " [" + kind + ", " + mountedText + "]" + capText;
            }
            String sizeText = "file".equalsIgnoreCase(type) ? " (" + formatSize(size) + ")" : "";
            return icon + " " + name + sizeText;
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            double kb = bytes / 1024.0;
            if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
            double mb = kb / 1024.0;
            if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
            return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
        }
    }

    private static class SelectionEstimate {
        private final long knownBytes;
        private final boolean hasUnknownSize;
        private final boolean usbSelection;

        private SelectionEstimate(long knownBytes, boolean hasUnknownSize, boolean usbSelection) {
            this.knownBytes = Math.max(0L, knownBytes);
            this.hasUnknownSize = hasUnknownSize;
            this.usbSelection = usbSelection;
        }

        private boolean isEmpty() {
            return knownBytes <= 0 && !hasUnknownSize;
        }
    }

    /**
     * 다중 파일/폴더 선택을 위한 선택 가능한 항목
     */
    private static class SelectableTransferItem {
        private final String type; // "file" or "folder"
        private final javafx.beans.property.SimpleStringProperty displayName;
        private final javafx.beans.property.SimpleStringProperty sourcePath;
        private final javafx.beans.property.SimpleBooleanProperty included;
        private final javafx.beans.property.SimpleStringProperty destinationPath;
        private final long fileSize;
        private final javafx.beans.property.SimpleBooleanProperty runAfterTransfer;

        public SelectableTransferItem(String type, String displayName, String sourcePath, boolean included, String destinationPath, long fileSize, boolean runAfterTransfer) {
            this.type = type;
            this.displayName = new javafx.beans.property.SimpleStringProperty(displayName);
            this.sourcePath = new javafx.beans.property.SimpleStringProperty(sourcePath);
            this.included = new javafx.beans.property.SimpleBooleanProperty(included);
            this.destinationPath = new javafx.beans.property.SimpleStringProperty(destinationPath);
            this.fileSize = fileSize;
            this.runAfterTransfer = new javafx.beans.property.SimpleBooleanProperty(runAfterTransfer);
        }

        public String type() { return type; }
        public String displayName() { return displayName.get(); }
        public String sourcePath() { return sourcePath.get(); }
        public boolean isIncluded() { return included.get(); }
        public String destinationPath() { return destinationPath.get(); }
        public long fileSize() { return fileSize; }
        public boolean runAfterTransfer() { return runAfterTransfer.get(); }

        public javafx.beans.property.StringProperty displayNameProperty() { return displayName; }
        public javafx.beans.property.StringProperty sourcePathProperty() { return sourcePath; }
        public javafx.beans.property.BooleanProperty includedProperty() { return included; }
        public javafx.beans.property.StringProperty destinationPathProperty() { return destinationPath; }
        public javafx.beans.property.BooleanProperty runAfterTransferProperty() { return runAfterTransfer; }

        public void setIncluded(boolean value) { included.set(value); }
        public void setDestinationPath(String value) { destinationPath.set(value); }
        public void setRunAfterTransfer(boolean value) { runAfterTransfer.set(value); }
    }
}
