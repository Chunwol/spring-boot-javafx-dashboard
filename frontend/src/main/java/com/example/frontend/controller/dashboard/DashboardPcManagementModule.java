package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.config.FrontendEnvConfig;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.ApiClientException;
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
    private static final int REMOTE_LIST_POLL_INTERVAL_MS = 500;
    private static final int REMOTE_LIST_TIMEOUT_SECONDS = resolveRemoteListTimeoutSeconds();
    private static final String SOURCE_MODE_SERVER_LABEL = "서버 단독 배포";
    private static final String SOURCE_MODE_HYBRID_LABEL = "하이브리드 배포(서버+에이전트)";

    private final ApiClient apiClient = ApiClient.getInstance();
    private final ObjectMapper objectMapper;

    // 뷰 컨트롤
    @FXML
    private VBox rootContainer;
    @FXML
    private StackPane viewControlsSlot;
    @FXML
    private StackPane pcTableSlot;
    @FXML
    private StackPane commandSectionSlot;

    private ComboBox<String> viewTypeCombo;
    private ComboBox<String> viewClassroomCombo;   // 강의실별 보기 시 표시
    private Label summaryLabel;

    // 테이블
    private TableView<PcStatusDto> pcTable;

    // 명령 전송
    private ComboBox<String> targetCombo;
    private ComboBox<String> targetClassroomCombo; // 선택 강의실 전송 시 표시
    private Label selectionHintLabel;
    private ComboBox<String> commandCombo;
    private Button rolloutBtn;

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
            ScrollPane scrollPane = loader.load();

            if (viewControlsSlot != null) {
                viewControlsSlot.getChildren().setAll(buildViewControls());
            }
            if (pcTableSlot != null) {
                pcTableSlot.getChildren().setAll(buildPcTable());
            }
            if (commandSectionSlot != null) {
                commandSectionSlot.getChildren().setAll(buildCommandSection());
            }

            loadAllData();
            return scrollPane;
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
            refreshBtn         = (Button)           loader.getNamespace().get("refreshBtn");
            summaryLabel       = (Label)            loader.getNamespace().get("summaryLabel");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load pcmgmt-view-controls.fxml", e);
        }

        viewTypeCombo.setItems(FXCollections.observableArrayList("전체 PC", "강의실별", "교사 PC만"));
        viewTypeCombo.setValue("전체 PC");

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

            TableColumn<PcStatusDto, String> classroomCol = (TableColumn<PcStatusDto, String>) ns.get("classroomCol");
            TableColumn<PcStatusDto, String> ipCol = (TableColumn<PcStatusDto, String>) ns.get("ipCol");
            TableColumn<PcStatusDto, String> hostnameCol = (TableColumn<PcStatusDto, String>) ns.get("hostnameCol");
            TableColumn<PcStatusDto, Void> roleCol = (TableColumn<PcStatusDto, Void>) ns.get("roleCol");
            TableColumn<PcStatusDto, Void> statusCol = (TableColumn<PcStatusDto, Void>) ns.get("statusCol");
            TableColumn<PcStatusDto, String> beatCol = (TableColumn<PcStatusDto, String>) ns.get("beatCol");

            pcTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

            classroomCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getGroupName() != null ? c.getValue().getGroupName() : "-"));
            ipCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getIpAddress() != null ? c.getValue().getIpAddress() : "-"));
            hostnameCol.setCellValueFactory(c -> Bindings.createStringBinding(() ->
                c.getValue().getPcName() != null ? c.getValue().getPcName() : "-"));
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

            pcTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<PcStatusDto>) change -> updateSelectionHint()
            );

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
            targetCombo          = (ComboBox<String>) loader.getNamespace().get("targetCombo");
            targetClassroomCombo = (ComboBox<String>) loader.getNamespace().get("targetClassroomCombo");
            commandCombo         = (ComboBox<String>) loader.getNamespace().get("commandCombo");
            sendBtn              = (Button)           loader.getNamespace().get("sendBtn");
            rolloutBtn           = (Button)           loader.getNamespace().get("rolloutBtn");
            selectionHintLabel   = (Label)            loader.getNamespace().get("selectionHintLabel");
            commandControlsFlow  = (FlowPane)         loader.getNamespace().get("commandControlsFlow");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load pcmgmt-command-section.fxml", e);
        }

        targetCombo.setItems(FXCollections.observableArrayList("전체 PC", "선택 강의실", "교사 PC만", "선택 PC"));
        targetCombo.setValue("전체 PC");

        targetCombo.setOnAction(e -> {
            boolean isClassroom = "선택 강의실".equals(targetCombo.getValue());
            targetClassroomCombo.setVisible(isClassroom);
            targetClassroomCombo.setManaged(isClassroom);
            updateSelectionHint();
        });

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
        return vbox;
    }

    private void openAgentRolloutDialog() {
        if (apiClient.isViewerRole()) {
            showAlert("권한 없음", ApiClient.viewerWriteDeniedMessage("에이전트 업데이트 배포"), Alert.AlertType.INFORMATION);
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("에이전트 업데이트 배포");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(860);
        dialog.getDialogPane().setPrefHeight(700);
        dialog.getDialogPane().setMinWidth(700);
        dialog.getDialogPane().setMinHeight(560);

        VBox root = new VBox(10);
        root.setPadding(new Insets(8));
        root.setFillWidth(true);
        root.setMaxWidth(Double.MAX_VALUE);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setMaxWidth(Double.MAX_VALUE);

        ColumnConstraints labelCol1 = new ColumnConstraints();
        labelCol1.setMinWidth(96);
        ColumnConstraints inputCol1 = new ColumnConstraints();
        inputCol1.setHgrow(Priority.ALWAYS);
        inputCol1.setFillWidth(true);
        inputCol1.setMinWidth(220);
        ColumnConstraints labelCol2 = new ColumnConstraints();
        labelCol2.setMinWidth(96);
        ColumnConstraints inputCol2 = new ColumnConstraints();
        inputCol2.setHgrow(Priority.ALWAYS);
        inputCol2.setFillWidth(true);
        inputCol2.setMinWidth(220);
        form.getColumnConstraints().setAll(labelCol1, inputCol1, labelCol2, inputCol2);

        TextField artifactIdField = new TextField();
        artifactIdField.setPromptText("예: 42");
        artifactIdField.setMaxWidth(Double.MAX_VALUE);
        Button uploadArtifactButton = new Button("파일 업로드");
        uploadArtifactButton.getStyleClass().add("pcmgmt-refresh-btn");
        uploadArtifactButton.setMinWidth(104);
        HBox artifactInputBox = new HBox(6, artifactIdField, uploadArtifactButton);
        HBox.setHgrow(artifactIdField, Priority.ALWAYS);
        artifactInputBox.setMaxWidth(Double.MAX_VALUE);

        TextField targetVersionField = new TextField();
        targetVersionField.setPromptText("예: 1.2.0");
        targetVersionField.setMaxWidth(Double.MAX_VALUE);

        Spinner<Integer> waveSizeSpinner = new Spinner<>(1, 400, 20, 1);
        waveSizeSpinner.setEditable(true);
        waveSizeSpinner.setMaxWidth(Double.MAX_VALUE);

        TextField rampUpField = new TextField("1.8");
        rampUpField.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> sourceModeCombo = new ComboBox<>(
                FXCollections.observableArrayList(SOURCE_MODE_SERVER_LABEL, SOURCE_MODE_HYBRID_LABEL));
        sourceModeCombo.setValue(SOURCE_MODE_HYBRID_LABEL);
        sourceModeCombo.setPrefWidth(220);
        sourceModeCombo.setMaxWidth(Double.MAX_VALUE);

        CheckBox canaryOnlyCheck = new CheckBox("검증 배포(1개 웨이브)");
        canaryOnlyCheck.setSelected(false);

        List<String> classChoices = new ArrayList<>();
        classChoices.add("전체");
        synchronized (classroomMap) {
            classChoices.addAll(classroomMap.keySet());
        }
        ComboBox<String> targetClassroomCombo = new ComboBox<>(FXCollections.observableArrayList(classChoices));
        targetClassroomCombo.setValue("전체");
        targetClassroomCombo.setMaxWidth(Double.MAX_VALUE);

        TextField uploadIdField = new TextField();
        uploadIdField.setPromptText("비워두면 자동 생성");
        uploadIdField.setMaxWidth(Double.MAX_VALUE);

        TextField fileNameField = new TextField("agent-update-package");
        fileNameField.setMaxWidth(Double.MAX_VALUE);

        TextField rollbackStableJobIdField = new TextField();
        rollbackStableJobIdField.setPromptText("안정 버전 Job ID");
        rollbackStableJobIdField.setMaxWidth(Double.MAX_VALUE);

        int row = 0;
        form.add(new Label("아티팩트 ID"), 0, row);
        form.add(artifactInputBox, 1, row);
        form.add(new Label("타깃 버전"), 2, row);
        form.add(targetVersionField, 3, row);
        row++;
        form.add(new Label("초기 웨이브 크기"), 0, row);
        form.add(waveSizeSpinner, 1, row);
        form.add(new Label("증가 계수"), 2, row);
        form.add(rampUpField, 3, row);
        row++;
        form.add(new Label("소스 모드"), 0, row);
        form.add(sourceModeCombo, 1, row);
        form.add(new Label("대상 강의실"), 2, row);
        form.add(targetClassroomCombo, 3, row);
        row++;
        form.add(new Label("업로드 식별자"), 0, row);
        form.add(uploadIdField, 1, row);
        form.add(new Label("원본 파일명"), 2, row);
        form.add(fileNameField, 3, row);
        row++;
        form.add(canaryOnlyCheck, 1, row);
        form.add(new Label("롤백 기준 Job ID"), 2, row);
        form.add(rollbackStableJobIdField, 3, row);

        GridPane.setHgrow(artifactInputBox, Priority.ALWAYS);
        GridPane.setHgrow(targetVersionField, Priority.ALWAYS);
        GridPane.setHgrow(waveSizeSpinner, Priority.ALWAYS);
        GridPane.setHgrow(rampUpField, Priority.ALWAYS);
        GridPane.setHgrow(sourceModeCombo, Priority.ALWAYS);
        GridPane.setHgrow(targetClassroomCombo, Priority.ALWAYS);
        GridPane.setHgrow(uploadIdField, Priority.ALWAYS);
        GridPane.setHgrow(fileNameField, Priority.ALWAYS);
        GridPane.setHgrow(rollbackStableJobIdField, Priority.ALWAYS);

        Button createJobButton = new Button("배포 시작");
        createJobButton.getStyleClass().add("pcmgmt-send-btn");
        Button verifyCanaryButton = new Button("검증 배포 시작");
        verifyCanaryButton.getStyleClass().add("pcmgmt-refresh-btn");
        Button refreshJobsButton = new Button("작업 목록 새로고침");
        refreshJobsButton.getStyleClass().add("pcmgmt-refresh-btn");
        Button loadDetailButton = new Button("선택 작업 상세");
        loadDetailButton.getStyleClass().add("pcmgmt-refresh-btn");
        Button rollbackButton = new Button("선택 작업 롤백");
        rollbackButton.getStyleClass().add("pcmgmt-refresh-btn");
        createJobButton.setMinWidth(108);
        verifyCanaryButton.setMinWidth(120);
        refreshJobsButton.setMinWidth(134);
        loadDetailButton.setMinWidth(122);
        rollbackButton.setMinWidth(118);

        FlowPane actions = new FlowPane(8, 8, createJobButton, verifyCanaryButton, refreshJobsButton, loadDetailButton, rollbackButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPrefWrapLength(780);
        actions.setMaxWidth(Double.MAX_VALUE);

        ListView<Map<String, Object>> jobsListView = new ListView<>();
        jobsListView.setPrefHeight(220);
        jobsListView.setFixedCellSize(-1);
        jobsListView.setCellFactory(list -> new ListCell<>() {
            private final Label summaryLabel = new Label();
            private final Tooltip summaryTooltip = new Tooltip();

            {
                summaryLabel.setWrapText(true);
                summaryLabel.setTextOverrun(OverrunStyle.CLIP);
                summaryLabel.setMaxWidth(Double.MAX_VALUE);
                summaryLabel.maxWidthProperty().bind(widthProperty().subtract(24));
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(summaryLabel);
            }

            @Override
            protected void updateItem(Map<String, Object> item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    summaryLabel.setText(null);
                    setTooltip(null);
                    return;
                }
                long jobId = toLong(item.get("jobId"));
                String status = String.valueOf(item.getOrDefault("status", "-"));
                String version = String.valueOf(item.getOrDefault("targetVersion", "-"));
                String createdAt = formatCreatedAtDisplay(item.get("createdAt"));
                String sourceModeLabel = sourceModeLabel(item.get("sourceMode"));
                String summary = "#" + jobId + " | " + status + " | v" + version + " | " + sourceModeLabel + " | " + createdAt;
                summaryLabel.setText(summary);
                summaryTooltip.setText(summary);
                setTooltip(summaryTooltip);
            }
        });

        TextArea detailArea = new TextArea();
        detailArea.setEditable(false);
        detailArea.setWrapText(true);
        detailArea.setPrefHeight(280);
        detailArea.setPromptText("작업 상세/이벤트가 여기에 표시됩니다.");

        uploadArtifactButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("에이전트 업데이트 파일 선택");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Agent Binary", "*.jar", "*.exe", "*.zip"),
                    new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            File selected = chooser.showOpenDialog(dialog.getDialogPane().getScene() == null
                    ? null
                    : dialog.getDialogPane().getScene().getWindow());
            if (selected == null) {
                return;
            }

            uploadArtifactButton.setDisable(true);
            detailArea.setText("아티팩트 업로드 중...\n파일: " + selected.getName());

            apiClient.uploadFile("/scripts", selected)
                    .thenAccept(resp -> {
                        try {
                            Map<String, Object> uploadBody = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
                            long artifactId = toLong(uploadBody.get("id"));
                            String guessedVersion = guessVersionFromFilename(selected.getName());
                            Platform.runLater(() -> {
                                if (artifactId > 0) {
                                    artifactIdField.setText(String.valueOf(artifactId));
                                }
                                fileNameField.setText(selected.getName());
                                if ((targetVersionField.getText() == null || targetVersionField.getText().isBlank())
                                        && guessedVersion != null && !guessedVersion.isBlank()) {
                                    targetVersionField.setText(guessedVersion);
                                }
                                detailArea.setText("아티팩트 업로드 완료\nartifactId: " + artifactId
                                        + "\n파일: " + selected.getName());
                                uploadArtifactButton.setDisable(false);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                detailArea.setText("업로드 응답 파싱 실패: " + ex.getMessage());
                                uploadArtifactButton.setDisable(false);
                            });
                        }
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String msg = ApiClient.userFacingErrorMessage(ex, "아티팩트 업로드 실패");
                            detailArea.setText(msg);
                            showAlert("실패", msg, Alert.AlertType.ERROR);
                            uploadArtifactButton.setDisable(false);
                        });
                        return null;
                    });
        });

        createJobButton.setOnAction(e -> {
            Long artifactId = parsePositiveLong(artifactIdField.getText());
            if (artifactId == null) {
                showAlert("입력 오류", "아티팩트 ID를 숫자로 입력하세요.", Alert.AlertType.WARNING);
                return;
            }
            int waveSize = waveSizeSpinner.getValue() == null ? 20 : waveSizeSpinner.getValue();
            double rampUp = parseDoubleOrDefault(rampUpField.getText(), 1.8d);
            String sourceMode = sourceModeCode(sourceModeCombo.getValue());
            String selectedClassroom = targetClassroomCombo.getValue();
            Long groupId = null;
            if (selectedClassroom != null && !"전체".equals(selectedClassroom)) {
                groupId = classroomMap.get(selectedClassroom);
            }

            String version = targetVersionField.getText() == null ? "" : targetVersionField.getText().trim();
            String uploadId = uploadIdField.getText() == null ? "" : uploadIdField.getText().trim();
            if (uploadId.isBlank()) {
                uploadId = "agent-update-" + artifactId + "-" + System.currentTimeMillis();
            }
            String originalFilename = fileNameField.getText() == null ? "" : fileNameField.getText().trim();
            if (originalFilename.isBlank()) {
                originalFilename = "agent-update-package";
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uploadId", uploadId);
            body.put("originalFilename", originalFilename);
            body.put("totalChunks", 1);
            body.put("artifactId", artifactId);
            body.put("targetVersion", version);
            body.put("waveSize", waveSize);
            body.put("canaryOnly", canaryOnlyCheck.isSelected());
            body.put("sourceMode", sourceMode);
            body.put("rampUpFactor", rampUp);
            if (groupId != null) {
                body.put("groupId", groupId);
            }

            createJobButton.setDisable(true);
            apiClient.postJson("/file-distribution/jobs", body)
                    .thenAccept(resp -> {
                        try {
                            Map<String, Object> created = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
                            Platform.runLater(() -> {
                                long createdJobId = toLong(created.get("jobId"));
                                detailArea.setText("배포 작업 생성 완료\nJob ID: " + createdJobId + "\n상태: "
                                        + created.getOrDefault("status", "-"));
                                loadRolloutJobsIntoList(jobsListView, detailArea);
                                if (createdJobId > 0) {
                                    loadRolloutDetail(createdJobId, detailArea);
                                }
                                createJobButton.setDisable(false);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                detailArea.setText("배포 작업 생성 응답 파싱 실패: " + ex.getMessage());
                                createJobButton.setDisable(false);
                            });
                        }
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String msg = ApiClient.userFacingErrorMessage(ex, "배포 작업 생성 실패");
                            detailArea.setText(msg);
                            showAlert("실패", msg, Alert.AlertType.ERROR);
                            createJobButton.setDisable(false);
                        });
                        return null;
                    });
        });

        verifyCanaryButton.setOnAction(e -> {
            Integer previousWaveSize = waveSizeSpinner.getValue();
            boolean previousCanary = canaryOnlyCheck.isSelected();
            waveSizeSpinner.getValueFactory().setValue(1);
            canaryOnlyCheck.setSelected(true);
            createJobButton.fire();
            Platform.runLater(() -> {
                if (previousWaveSize != null && previousWaveSize > 0) {
                    waveSizeSpinner.getValueFactory().setValue(previousWaveSize);
                }
                canaryOnlyCheck.setSelected(previousCanary);
            });
        });

        refreshJobsButton.setOnAction(e -> loadRolloutJobsIntoList(jobsListView, detailArea));

        loadDetailButton.setOnAction(e -> {
            Map<String, Object> selected = jobsListView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showAlert("선택 필요", "상세를 볼 작업을 선택하세요.", Alert.AlertType.INFORMATION);
                return;
            }
            long jobId = toLong(selected.get("jobId"));
            if (jobId <= 0) {
                showAlert("선택 오류", "유효한 Job ID가 아닙니다.", Alert.AlertType.WARNING);
                return;
            }
            loadRolloutDetail(jobId, detailArea);
        });

        rollbackButton.setOnAction(e -> {
            Map<String, Object> selected = jobsListView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showAlert("선택 필요", "롤백할 작업을 선택하세요.", Alert.AlertType.INFORMATION);
                return;
            }
            long sourceJobId = toLong(selected.get("jobId"));
            Long stableJobId = parsePositiveLong(rollbackStableJobIdField.getText());
            if (sourceJobId <= 0 || stableJobId == null) {
                showAlert("입력 오류", "선택 작업 + 롤백 기준 Job ID를 확인하세요.", Alert.AlertType.WARNING);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rollbackFromJobId", stableJobId);
            body.put("waveSize", waveSizeSpinner.getValue() == null ? 20 : waveSizeSpinner.getValue());
            body.put("canaryOnly", canaryOnlyCheck.isSelected());
            String selectedClassroom = targetClassroomCombo.getValue();
            if (selectedClassroom != null && !"전체".equals(selectedClassroom)) {
                Long groupId = classroomMap.get(selectedClassroom);
                if (groupId != null) {
                    body.put("groupId", groupId);
                }
            }

            rollbackButton.setDisable(true);
            apiClient.postJson("/file-distribution/jobs/" + sourceJobId + "/rollback", body)
                    .thenAccept(resp -> {
                        Platform.runLater(() -> {
                            detailArea.setText("롤백 작업 생성 완료\n" + resp);
                            loadRolloutJobsIntoList(jobsListView, detailArea);
                            rollbackButton.setDisable(false);
                        });
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            String msg = ApiClient.userFacingErrorMessage(ex, "롤백 작업 생성 실패");
                            detailArea.setText(msg);
                            showAlert("실패", msg, Alert.AlertType.ERROR);
                            rollbackButton.setDisable(false);
                        });
                        return null;
                    });
        });

        jobsListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            long jobId = toLong(newValue.get("jobId"));
            if (jobId > 0) {
                loadRolloutDetail(jobId, detailArea);
            }
        });

        root.getChildren().addAll(
                new Label("에이전트 업데이트 배포"),
                form,
                actions,
                new Label("최근 작업"),
                jobsListView,
                new Label("상세 / 이벤트"),
                detailArea
        );
        for (Node node : root.getChildren()) {
            if (node != null) {
                node.setManaged(true);
            }
        }
        VBox.setVgrow(jobsListView, Priority.ALWAYS);
        VBox.setVgrow(detailArea, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportHeight(640);
        dialog.getDialogPane().widthProperty().addListener((obs, oldW, newW) -> {
            double wrap = Math.max(360.0, newW.doubleValue() - 56.0);
            actions.setPrefWrapLength(wrap);
        });
        Platform.runLater(() -> {
            double paneWidth = dialog.getDialogPane().getWidth();
            actions.setPrefWrapLength(Math.max(360.0, paneWidth - 56.0));
        });

        dialog.getDialogPane().setContent(scrollPane);
        loadRolloutJobsIntoList(jobsListView, detailArea);
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

        targetClassroomCombo.setItems(FXCollections.observableArrayList(names));
        if (!names.isEmpty() && targetClassroomCombo.getValue() == null) {
            targetClassroomCombo.setValue(names.get(0));
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

        // 온라인 먼저 정렬
        display.sort((a, b) -> {
            if (a.isOnline() != b.isOnline()) return a.isOnline() ? -1 : 1;
            String na = a.getGroupName() != null ? a.getGroupName() : "";
            String nb = b.getGroupName() != null ? b.getGroupName() : "";
            return na.compareTo(nb);
        });

        pcTable.setItems(FXCollections.observableArrayList(display));

        long online = display.stream().filter(PcStatusDto::isOnline).count();
        summaryLabel.setText(String.format("총 %d대 — 온라인 %d / 오프라인 %d", display.size(), online, display.size() - online));
    }

    // ─────────────────────────────────────────
    // 선택 힌트 업데이트
    // ─────────────────────────────────────────

    private void updateSelectionHint() {
        if (selectionHintLabel == null) return;
        String target = targetCombo != null ? targetCombo.getValue() : "";
        if ("선택 PC".equals(target)) {
            int cnt = pcTable.getSelectionModel().getSelectedItems().size();
            selectionHintLabel.setText(cnt > 0
                ? String.format("✓ %d대 선택됨 (Ctrl+클릭 또는 Shift+클릭으로 여러 PC 선택)", cnt)
                : "← 위 테이블에서 PC를 선택하세요 (Ctrl+클릭 또는 Shift+클릭)");
        } else {
            selectionHintLabel.setText("");
        }
    }

    // ─────────────────────────────────────────
    // 명령 전송
    // ─────────────────────────────────────────

    private void handleSendCommand() {
        String target = targetCombo.getValue();
        String commandLabel = commandCombo.getValue();

        if (target == null || commandLabel == null) {
            showAlert("선택 오류", "대상과 명령을 선택해주세요.", Alert.AlertType.WARNING);
            return;
        }

        String command = toCommandCode(commandLabel);

        List<PcStatusDto> targetPcs = resolveTargetPcs(target);
        if (targetPcs.isEmpty()) {
            if ("선택 PC".equals(target)) {
                showAlert("선택 오류", "테이블에서 전송할 PC를 선택해주세요.", Alert.AlertType.WARNING);
            } else {
                showAlert("대상 없음", "조건에 해당하는 PC가 없습니다.", Alert.AlertType.INFORMATION);
            }
            return;
        }

        TransferSource transferSource = null;
        String collectPayloadJson = null;
        String commandTypeForQueue = command;

        if ("RUN_PS1".equals(command)) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("PowerShell 스크립트 선택");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerShell 파일", "*.ps1"));
            File selected = fileChooser.showOpenDialog(null);
            if (selected == null) {
                return;
            }
            transferSource = TransferSource.simple(selected.toPath(), "file", selected.getName(), false, false);
        } else if ("RUN_FILE_TRANSFER".equals(command)) {
            transferSource = chooseTransferSource();
            if (transferSource == null) {
                return;
            }
            commandTypeForQueue = "RUN_PS1";
        } else if ("COLLECT_FILES".equals(command)) {
            collectPayloadJson = chooseCollectPayloadJson(targetPcs);
            if (collectPayloadJson == null || collectPayloadJson.isBlank()) {
                return;
            }
        }

        showConfirmationDialog(commandTypeForQueue, commandLabel, targetPcs, transferSource, collectPayloadJson);
    }

    private List<PcStatusDto> resolveTargetPcs(String target) {
        List<PcStatusDto> snapshot;
        synchronized (allPcs) {
            snapshot = new ArrayList<>(allPcs);
        }
        return switch (target) {
            case "전체 PC" -> new ArrayList<>(snapshot);
            case "교사 PC만" -> snapshot.stream()
                .filter(p -> "TEACHER".equals(p.getRole()))
                .collect(Collectors.toList());
            case "선택 강의실" -> {
                Long gid = classroomMap.get(targetClassroomCombo.getValue());
                yield gid == null ? List.of() : snapshot.stream()
                    .filter(p -> gid.equals(p.getGroupId()))
                    .collect(Collectors.toList());
            }
            case "선택 PC" -> new ArrayList<>(pcTable.getSelectionModel().getSelectedItems());
            default -> List.of();
        };
    }

    // ─────────────────────────────────────────
    // 전송 전 확인 다이얼로그
    // ─────────────────────────────────────────
    // 전송 전 확인 다이얼로그
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void showConfirmationDialog(String commandType, String commandLabel, List<PcStatusDto> targetPcs,
                                        TransferSource transferSource, String collectPayloadJson) {
        long onlineCount = targetPcs.stream().filter(PcStatusDto::isOnline).count();
        long offlineCount = targetPcs.size() - onlineCount;
        boolean scriptCommand = "RUN_PS1".equals(commandType)
                && transferSource != null
                && "file".equals(transferSource.sourceType())
                && transferSource.displayName().toLowerCase(Locale.ROOT).endsWith(".ps1")
                && !"파일/폴더 전송".equals(commandLabel);
        boolean transferCommand = "파일/폴더 전송".equals(commandLabel) && transferSource != null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("명령 전송 확인");
        dialog.setHeaderText(null);

        VBox content;
        VBox summaryBox;
        VBox shellOptionsBox;
        Label listTitle;
        Label note;
        TableView<PcStatusDto> confirmTable;
        TableColumn<PcStatusDto, String> cRoom;
        TableColumn<PcStatusDto, String> cIp;
        TableColumn<PcStatusDto, String> cHost;
        TableColumn<PcStatusDto, Void> cStatus;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pc-command-confirm-dialog.fxml"));
            content = loader.load();
            summaryBox = (VBox) loader.getNamespace().get("summaryBox");
            shellOptionsBox = (VBox) loader.getNamespace().get("optionsBox");
            listTitle = (Label) loader.getNamespace().get("listTitleLabel");
            note = (Label) loader.getNamespace().get("noteLabel");
            @SuppressWarnings("unchecked")
            TableView<PcStatusDto> ct = (TableView<PcStatusDto>) loader.getNamespace().get("confirmTable");
            confirmTable = ct;
            @SuppressWarnings("unchecked")
            TableColumn<PcStatusDto, String> cr = (TableColumn<PcStatusDto, String>) loader.getNamespace().get("confirmRoomCol");
            cRoom = cr;
            @SuppressWarnings("unchecked")
            TableColumn<PcStatusDto, String> ci = (TableColumn<PcStatusDto, String>) loader.getNamespace().get("confirmIpCol");
            cIp = ci;
            @SuppressWarnings("unchecked")
            TableColumn<PcStatusDto, String> ch = (TableColumn<PcStatusDto, String>) loader.getNamespace().get("confirmHostCol");
            cHost = ch;
            @SuppressWarnings("unchecked")
            TableColumn<PcStatusDto, Void> cs = (TableColumn<PcStatusDto, Void>) loader.getNamespace().get("confirmStatusCol");
            cStatus = cs;
        } catch (Exception e) {
            throw new IllegalStateException("명령 전송 확인 다이얼로그 FXML 로딩 실패", e);
        }

        summaryBox.getStyleClass().add("pcmgmt-dialog-card");
        summaryBox.getChildren().addAll(
            styledLabel("명령: " + commandLabel, "pcmgmt-dialog-title-strong"),
            styledLabel("총 대상: " + targetPcs.size() + "대", "pcmgmt-dialog-text"),
            styledLabel("미전송: " + offlineCount + "대 (오프라인)", "pcmgmt-dialog-text-muted"),
            styledLabel("전송 예정: " + onlineCount + "대 (온라인)", "pcmgmt-dialog-text-success")
        );
        if (transferSource != null) {
            String sourceLabel = transferCommand ? "전송원본" : "스크립트";
            summaryBox.getChildren().add(styledLabel(sourceLabel + ": " + transferSource.displayName(), "pcmgmt-dialog-caption"));
        }

        ComboBox<String> runModeCombo = null;
        ComboBox<String> windowModeCombo = null;
        TextField argsField = null;
        ComboBox<String> destinationCombo = null;
        TextField destinationPathField = null;
        CheckBox runAfterTransferCheck = null;
        if (scriptCommand || transferCommand) {
            VBox optionsBox = new VBox(8);
            optionsBox.getStyleClass().add("pcmgmt-dialog-card");

            Label optionTitle = styledLabel(
                transferCommand ? "파일 전송 옵션" : "스크립트 실행 옵션",
                "pcmgmt-dialog-section-title"
            );
            HBox modeRow = new HBox(8);
            modeRow.setAlignment(Pos.CENTER_LEFT);
            Label modeLabel = styledLabel("결과 처리", "pcmgmt-dialog-field-label");
            ComboBox<String> modeComboLocal = new ComboBox<>(FXCollections.observableArrayList(
                "대기 실행 (완료까지 수집)", "즉시 실행 (시작만 확인)"
            ));
            modeComboLocal.setValue(transferCommand ? "대기 실행 (완료까지 수집)" : "대기 실행 (완료까지 수집)");
            modeComboLocal.setPrefWidth(220);
            modeRow.getChildren().addAll(modeLabel, modeComboLocal);

            HBox windowRow = new HBox(8);
            windowRow.setAlignment(Pos.CENTER_LEFT);
            Label windowLabel = styledLabel("콘솔 창", "pcmgmt-dialog-field-label");
            ComboBox<String> windowComboLocal = new ComboBox<>(FXCollections.observableArrayList(
                "창 숨김", "창 표시"
            ));
            windowComboLocal.setValue("창 숨김");
            windowComboLocal.setPrefWidth(220);
            windowRow.getChildren().addAll(windowLabel, windowComboLocal);

            VBox argsBox = new VBox(4);
            Label argsLabel = styledLabel("추가 인자", "pcmgmt-dialog-field-label");
            TextField argsFieldLocal = new TextField();
            argsFieldLocal.setPromptText("예: -Mode Test");

            VBox transferDestBox = new VBox(6);
            Label destinationLabel = styledLabel("대상 PC 저장 위치", "pcmgmt-dialog-field-label");
            ComboBox<String> destinationComboLocal = new ComboBox<>(FXCollections.observableArrayList(
                "다운로드 폴더", "바탕화면", "경로 직접 입력"
            ));
            // TransferSource에서 미리 선택한 값이 있으면 기본값으로 사용
            String preselectedDestMode = transferSource != null ? transferSource.preDestMode() : "DOWNLOADS";
            String preselectedValue = "DOWNLOADS".equals(preselectedDestMode) ? "다운로드 폴더" 
                : "DESKTOP".equals(preselectedDestMode) ? "바탕화면" : "경로 직접 입력";
            destinationComboLocal.setValue(preselectedValue);
            destinationComboLocal.setPrefWidth(220);
            TextField destinationPathLocal = new TextField();
            destinationPathLocal.setPromptText("예: D:/Deploy/packages");
            String preselectedPath = transferSource != null ? (transferSource.preDestPath() != null ? transferSource.preDestPath() : "") : "";
            destinationPathLocal.setText(preselectedPath);
            boolean showPathField = "경로 직접 입력".equals(preselectedValue);
            destinationPathLocal.setVisible(showPathField);
            destinationPathLocal.setManaged(showPathField);
            destinationComboLocal.valueProperty().addListener((obs, oldV, newV) -> {
                boolean custom = "경로 직접 입력".equals(newV);
                destinationPathLocal.setVisible(custom);
                destinationPathLocal.setManaged(custom);
            });
            CheckBox runAfterTransferLocal = new CheckBox("전송 후 실행 (파일 전송 시)");
            boolean preselectedRunAfter = transferSource != null && transferSource.preRunAfter();
            runAfterTransferLocal.setSelected(preselectedRunAfter);
            runAfterTransferLocal.setDisable(!transferCommand || transferSource == null || !"file".equals(transferSource.sourceType()));

            Label hintLabel = styledLabel(
                transferCommand
                    ? "전송은 RUN_PS1 명령 체계로 처리되며 상태는 명령 탭에서 확인합니다. 폴더 전송은 zip으로 압축 후 대상 경로에 해제됩니다."
                    : "즉시 실행은 프로세스 시작 성공만 확인합니다. 대기 실행은 종료까지 기다리며 stdout/stderr를 수집합니다. 창 표시는 에이전트 실행 계정/세션 정책에 따라 보이지 않을 수 있습니다.",
                "pcmgmt-dialog-hint"
            );

            argsBox.getChildren().addAll(argsLabel, argsFieldLocal);
            transferDestBox.getChildren().addAll(destinationLabel, destinationComboLocal, destinationPathLocal, runAfterTransferLocal);

            if (transferCommand) {
                optionsBox.getChildren().addAll(optionTitle, transferDestBox, modeRow, windowRow, argsBox, hintLabel);
            } else {
                optionsBox.getChildren().addAll(optionTitle, modeRow, windowRow, argsBox, hintLabel);
            }
            shellOptionsBox.getChildren().setAll(optionsBox);
            runModeCombo = modeComboLocal;
            windowModeCombo = windowComboLocal;
            argsField = argsFieldLocal;
            destinationCombo = destinationComboLocal;
            destinationPathField = destinationPathLocal;
            runAfterTransferCheck = runAfterTransferLocal;
        } else {
            shellOptionsBox.getChildren().clear();
            shellOptionsBox.setManaged(false);
            shellOptionsBox.setVisible(false);
        }

        listTitle.getStyleClass().add("pcmgmt-dialog-list-title");

        cRoom.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getGroupName() != null ? c.getValue().getGroupName() : "-"
        ));
        cIp.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getIpAddress() != null ? c.getValue().getIpAddress() : "-"
        ));
        cHost.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getPcName() != null ? c.getValue().getPcName() : "-"
        ));
        cStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                PcStatusDto pc = getTableRow().getItem();
                Label lbl = new Label(pc.isOnline() ? "✓ 전송" : "✗ 미전송");
                lbl.getStyleClass().add(pc.isOnline() ? "pcmgmt-status-online" : "pcmgmt-status-failed");
                setGraphic(lbl);
            }
        });

        List<PcStatusDto> sorted = targetPcs.stream()
            .sorted((a, b) -> Boolean.compare(b.isOnline(), a.isOnline()))
            .collect(Collectors.toList());
        confirmTable.setItems(FXCollections.observableArrayList(sorted));

        note.getStyleClass().add("pcmgmt-dialog-note");
        note.setText("※ 오프라인 PC는 명령을 전송하지 않으며, 미전송 상태로 기록됩니다.");

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okBtn != null) {
            okBtn.setText("전송");
            okBtn.getStyleClass().add("pcmgmt-dialog-primary-btn");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            ScriptExecutionOptions scriptOptions = (scriptCommand || transferCommand)
                ? new ScriptExecutionOptions(
                    transferSource == null || transferSource.path() == null ? null : transferSource.path().toString(),
                    transferSource == null ? null : transferSource.scriptUrl(),
                    runModeCombo != null && runModeCombo.getValue() != null && runModeCombo.getValue().startsWith("즉시 실행")
                        ? "BACKGROUND" : "FOREGROUND",
                    windowModeCombo != null && windowModeCombo.getValue() != null && windowModeCombo.getValue().startsWith("창 표시")
                        ? "VISIBLE" : "HIDDEN",
                    argsField != null ? argsField.getText() : "",
                    transferCommand,
                    transferSource == null ? "file" : transferSource.sourceType(),
                    transferSource == null ? "" : transferSource.displayName(),
                    destinationCombo != null && destinationCombo.getValue() != null ? mapDestinationMode(destinationCombo.getValue()) : "DOWNLOADS",
                    destinationPathField != null ? destinationPathField.getText() : "",
                    runAfterTransferCheck != null && runAfterTransferCheck.isSelected(),
                    transferSource != null && transferSource.zipped(),
                    transferSource != null && transferSource.temporaryArtifact()
                )
                : ScriptExecutionOptions.none();
            executeCommand(commandType, targetPcs, scriptOptions, collectPayloadJson);
        }
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
        // PC 켜기(PCON)는 오프라인 대상까지 포함해서 서버의 WOL 릴레이 로직으로 전달
        List<PcStatusDto> onlinePcs = "PCON".equals(commandType)
                ? new ArrayList<>(targetPcs)
                : targetPcs.stream().filter(PcStatusDto::isOnline).collect(Collectors.toList());
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
                        String uploadResp = apiClient.uploadFile("/scripts", new File(scriptOptions.scriptPath())).join();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> uploadBody = objectMapper.readValue(uploadResp, Map.class);
                        uploadedScriptUrl = String.valueOf(uploadBody.getOrDefault("downloadUrl", ""));
                        if (uploadedScriptUrl == null || uploadedScriptUrl.isBlank()) {
                            throw new RuntimeException("업로드 응답에 downloadUrl이 없습니다.");
                        }
                    } else if (scriptOptions.hasRemoteScriptUrl()) {
                        uploadedScriptUrl = scriptOptions.scriptUrl();
                    } else {
                        throw new RuntimeException("스크립트/전송 소스가 없습니다.");
                    }
                } catch (Exception ex) {
                    log.warn("업로드 실패: {}", ex.getMessage());
                    int skipped = targetPcs.size() - onlinePcs.size();
                    Platform.runLater(() -> showAlert("전송 실패",
                        "업로드 실패로 명령 전송을 중단했습니다.\n" + ex.getMessage() +
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
                        apiClient.downloadToFile(downloadUrl, savePath, (received, totalBytes) -> {
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
                showAlert(
                        finalFailed == 0 ? "수집 완료" : "수집 부분 완료",
                        String.format("성공: %d대%s\n\n저장된 파일:\n%s",
                                finalDone,
                                finalFailed > 0 ? " / 실패: " + finalFailed + "대" : "",
                                detail.isBlank() ? "(없음)" : detail),
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

    private String chooseCollectPayloadJson(List<PcStatusDto> targetPcs) {
        PcStatusDto browsePc = targetPcs.stream().filter(PcStatusDto::isOnline).findFirst().orElse(null);
        if (browsePc == null) {
            showAlert("탐색 불가", "온라인 PC가 없어 원격 탐색기를 열 수 없습니다.", Alert.AlertType.WARNING);
            return null;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("원격 파일 탐색기");
        dialog.setHeaderText("탐색 대상: " + (browsePc.getPcName() == null ? browsePc.getMacAddress() : browsePc.getPcName()));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(1000);
        dialog.getDialogPane().setPrefHeight(780);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        Label info = new Label("폴더/드라이브 클릭 시 이동, [선택 추가]로 수집 목록에 담아주세요.");
        info.getStyleClass().add("pcmgmt-dialog-caption");

        CheckBox usbOnlyCheck = new CheckBox("USB만 보기 (루트 드라이브 목록)");
        Label driveStatusLabel = new Label("드라이브 상태: 로딩 중...");
        driveStatusLabel.getStyleClass().add("pcmgmt-dialog-caption");
        Label estimateLabel = new Label("선택 예상 전송량: -");
        estimateLabel.getStyleClass().add("pcmgmt-dialog-caption");

        TextField currentPathField = new TextField();
        currentPathField.setEditable(false);
        currentPathField.setPromptText("(드라이브 목록)");

        TextField pathInputField = new TextField();
        pathInputField.setPromptText("직접 경로 입력 (예: D:\\USB)");
        Button goButton = new Button("이동");
        Button rootButton = new Button("루트");
        Button upButton = new Button("상위");
        Button refreshButton = new Button("새로고침");
        Button loadMoreButton = new Button("더 불러오기");
        Button addSelectedButton = new Button("선택 추가");
        Button removeSelectedButton = new Button("선택 제거");
        loadMoreButton.setVisible(false);
        loadMoreButton.setManaged(false);
        loadMoreButton.getStyleClass().add("btn-sm");

        ListView<RemoteBrowseEntry> remoteList = new ListView<>();
        remoteList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(remoteList, Priority.ALWAYS);
        Label pagingStatusLabel = new Label("목록 로딩 중...");
        pagingStatusLabel.getStyleClass().add("pcmgmt-dialog-caption");
        Label loadingLabel = new Label("⏳ 탐색 중...");
        loadingLabel.getStyleClass().add("pcmgmt-dialog-caption");
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        ListView<String> selectedPathList = new ListView<>();
        selectedPathList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectedPathList.setPrefHeight(160);

        TextField outputField = new TextField("collected-bundle.zip");
        outputField.setPromptText("묶음 파일명(zip)");

        HBox pathRow = new HBox(6, currentPathField, upButton, refreshButton, rootButton);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        HBox navRow = new HBox(6, pathInputField, goButton, addSelectedButton, removeSelectedButton);
        HBox.setHgrow(pathInputField, Priority.ALWAYS);
        HBox pagingRow = new HBox(8, loadMoreButton, pagingStatusLabel);
        pagingRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(
                info,
                usbOnlyCheck,
                driveStatusLabel,
                estimateLabel,
                pathRow,
                navRow,
                loadingLabel,
                new Label("원격 항목"),
                remoteList,
                pagingRow,
                new Label("수집 대상 경로"),
                selectedPathList,
                new Label("묶음 파일명(zip)"),
                outputField
        );
        dialog.getDialogPane().setContent(root);

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
            if (dialog.getDialogPane() == null || dialog.getDialogPane().getScene() == null) {
                return;
            }
            if (currentPath[0] != null && !currentPath[0].isBlank()) {
                return;
            }
            Platform.runLater(refresh);
        }, 4, 4, TimeUnit.SECONDS);
        dialog.setOnHidden(evt -> refreshScheduler.shutdownNow());

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
            selectedPathList.setItems(FXCollections.observableArrayList(existing));
        });
        removeSelectedButton.setOnAction(e -> {
            List<String> selected = new ArrayList<>(selectedPathList.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                return;
            }
            List<String> next = new ArrayList<>(selectedPathList.getItems());
            next.removeAll(selected);
            selectedPathList.setItems(FXCollections.observableArrayList(next));
        });

        remoteList.setOnMouseClicked(ev -> {
            // 셀에서 직접 item 취득 — getSelectedItem()은 MOUSE_PRESSED~CLICKED 사이에
            // setItems()가 끼어들면 null이 될 수 있어 셀 기반으로 우회
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

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return null;
        }

        List<String> paths = selectedPathList.getItems().stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            showAlert("입력 오류", "수집할 파일/폴더를 1개 이상 선택하세요.", Alert.AlertType.WARNING);
            return null;
        }

        String outputName = outputField.getText() == null ? "" : outputField.getText().trim();
        if (outputName.isBlank()) {
            outputName = "collected-bundle.zip";
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paths", paths);
            payload.put("outputName", outputName);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            showAlert("직렬화 오류", "수집 요청 생성에 실패했습니다.\n" + e.getMessage(), Alert.AlertType.ERROR);
            return null;
        }
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
        try {
            return Path.of(currentPath).resolve(entry.name).normalize().toString();
        } catch (Exception ex) {
            String sep = currentPath.endsWith("\\") || currentPath.endsWith("/") ? "" : "\\";
            return currentPath + sep + entry.name;
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
            fc.setTitle("파일 선택");
            File f = fc.showOpenDialog(null);
            if (f != null) {
                SelectableTransferItem item = new SelectableTransferItem("file", f.getName(), f.getAbsolutePath(), true, "", f.length(), false);
                items.add(item);
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
                nameLabel = new Label(); nameLabel.setPrefWidth(260); nameLabel.getStyleClass().add("pcmgmt-transfer-item-name");
                sizeLabel = new Label(); sizeLabel.setPrefWidth(90);  sizeLabel.getStyleClass().add("pcmgmt-transfer-item-size");
                typeLabel = new Label(); typeLabel.setPrefWidth(50);  typeLabel.getStyleClass().add("pcmgmt-transfer-item-type");
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
