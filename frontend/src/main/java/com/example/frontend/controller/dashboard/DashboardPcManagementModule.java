package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
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
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

    // 데이터 (동기화된 컬렉션 - 백그라운드 스레드와 FX 스레드 간 동기화)
    private final List<PcStatusDto> allPcs = Collections.synchronizedList(new ArrayList<>());
    // groupName → groupId (ConcurrentHashMap - 스레드 안전)
    private final Map<String, Long> classroomMap = Collections.synchronizedMap(new LinkedHashMap<>());

    public DashboardPcManagementModule(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pcmgmt-command-section.fxml"));
            vbox                 = loader.load();
            targetCombo          = (ComboBox<String>) loader.getNamespace().get("targetCombo");
            targetClassroomCombo = (ComboBox<String>) loader.getNamespace().get("targetClassroomCombo");
            commandCombo         = (ComboBox<String>) loader.getNamespace().get("commandCombo");
            sendBtn              = (Button)           loader.getNamespace().get("sendBtn");
            selectionHintLabel   = (Label)            loader.getNamespace().get("selectionHintLabel");
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
        return vbox;
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
                    transferSource == null ? null : transferSource.path().toString(),
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
                    String uploadResp = apiClient.uploadFile("/scripts", new File(scriptOptions.scriptPath())).join();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> uploadBody = objectMapper.readValue(uploadResp, Map.class);
                    uploadedScriptUrl = String.valueOf(uploadBody.getOrDefault("downloadUrl", ""));
                    if (uploadedScriptUrl == null || uploadedScriptUrl.isBlank()) {
                        throw new RuntimeException("업로드 응답에 downloadUrl이 없습니다.");
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

            if (scriptOptions.temporaryArtifact() && scriptOptions.hasScript()) {
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
        dialog.getDialogPane().setPrefWidth(860);
        dialog.getDialogPane().setPrefHeight(620);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        Label info = new Label("더블클릭으로 폴더 이동, [선택 추가]로 수집 목록에 담아주세요.");
        info.getStyleClass().add("pcmgmt-dialog-caption");

        TextField currentPathField = new TextField();
        currentPathField.setEditable(false);
        currentPathField.setPromptText("(드라이브 목록)");

        TextField pathInputField = new TextField();
        pathInputField.setPromptText("직접 경로 입력 (예: D:\\USB)");
        Button goButton = new Button("이동");
        Button rootButton = new Button("루트");
        Button upButton = new Button("상위");
        Button refreshButton = new Button("새로고침");
        Button addSelectedButton = new Button("선택 추가");
        Button removeSelectedButton = new Button("선택 제거");

        ListView<RemoteBrowseEntry> remoteList = new ListView<>();
        remoteList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(remoteList, Priority.ALWAYS);

        ListView<String> selectedPathList = new ListView<>();
        selectedPathList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectedPathList.setPrefHeight(130);

        TextField outputField = new TextField("collected-bundle.zip");
        outputField.setPromptText("묶음 파일명(zip)");

        HBox pathRow = new HBox(6, currentPathField, upButton, refreshButton, rootButton);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        HBox navRow = new HBox(6, pathInputField, goButton, addSelectedButton, removeSelectedButton);
        HBox.setHgrow(pathInputField, Priority.ALWAYS);

        root.getChildren().addAll(
                info,
                pathRow,
                navRow,
                new Label("원격 항목"),
                remoteList,
                new Label("수집 대상 경로"),
                selectedPathList,
                new Label("묶음 파일명(zip)"),
                outputField
        );
        dialog.getDialogPane().setContent(root);

        final String[] currentPath = {""};

        Runnable loadRoot = () -> loadRemoteEntriesAsync(browsePc, "", currentPathField, remoteList, currentPath);
        Runnable refresh = () -> loadRemoteEntriesAsync(browsePc, currentPath[0], currentPathField, remoteList, currentPath);
        loadRoot.run();

        goButton.setOnAction(e -> {
            String p = pathInputField.getText() == null ? "" : pathInputField.getText().trim();
            loadRemoteEntriesAsync(browsePc, p, currentPathField, remoteList, currentPath);
        });
        rootButton.setOnAction(e -> loadRoot.run());
        upButton.setOnAction(e -> {
            String parent = parentPath(currentPath[0]);
            loadRemoteEntriesAsync(browsePc, parent, currentPathField, remoteList, currentPath);
        });
        refreshButton.setOnAction(e -> refresh.run());

        addSelectedButton.setOnAction(e -> {
            List<RemoteBrowseEntry> selected = new ArrayList<>(remoteList.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
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
            if (ev.getClickCount() != 2) {
                return;
            }
            RemoteBrowseEntry entry = remoteList.getSelectionModel().getSelectedItem();
            if (entry == null || !"dir".equalsIgnoreCase(entry.type) && !"drive".equalsIgnoreCase(entry.type)) {
                return;
            }
            String nextPath = resolveEntryAbsolutePath(currentPath[0], entry);
            loadRemoteEntriesAsync(browsePc, nextPath, currentPathField, remoteList, currentPath);
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
                                        TextField currentPathField,
                                        ListView<RemoteBrowseEntry> remoteList,
                                        String[] currentPathRef) {
        Thread t = new Thread(() -> {
            try {
                Map<String, Object> resp = requestRemoteFileList(browsePc, path);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entriesRaw = (List<Map<String, Object>>) resp.getOrDefault("entries", List.of());
                String currentPath = String.valueOf(resp.getOrDefault("currentPath", ""));
                List<RemoteBrowseEntry> entries = entriesRaw.stream()
                        .map(row -> new RemoteBrowseEntry(
                                String.valueOf(row.getOrDefault("name", "")),
                                String.valueOf(row.getOrDefault("type", "file")),
                                toLong(row.get("size"))
                        ))
                        .toList();
                Platform.runLater(() -> {
                    currentPathRef[0] = currentPath;
                    currentPathField.setText(currentPath == null ? "" : currentPath);
                    remoteList.setItems(FXCollections.observableArrayList(entries));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert("원격 탐색 실패", ex.getMessage(), Alert.AlertType.ERROR));
            }
        }, "pcmgmt-remote-browse");
        t.setDaemon(true);
        t.start();
    }

    private Map<String, Object> requestRemoteFileList(PcStatusDto browsePc, String path) throws Exception {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("scope", "SINGLE");
        command.put("macAddress", browsePc.getMacAddress());
        command.put("type", "LIST_FILES");
        command.put("payloadJson", objectMapper.writeValueAsString(Map.of("path", path == null ? "" : path)));

        String enqueueResp = apiClient.postJson("/commands", command).join();
        Map<String, Object> enqueueBody = objectMapper.readValue(enqueueResp, new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Object> taskIds = (List<Object>) enqueueBody.getOrDefault("taskIds", List.of());
        if (taskIds.isEmpty()) {
            throw new IllegalStateException("원격 탐색 명령 큐 등록 실패");
        }
        long taskId = Long.parseLong(String.valueOf(taskIds.get(0)));

        int maxPoll = 20;
        for (int i = 0; i < maxPoll; i++) {
            Map<String, Object> row = apiClient.getJson("/commands?taskId=" + taskId,
                    new TypeReference<Map<String, Object>>() {}).join();
            String status = String.valueOf(row.getOrDefault("status", ""));
            if ("COMPLETED".equalsIgnoreCase(status)) {
                String stdout = String.valueOf(row.getOrDefault("stdout", ""));
                if (stdout == null || stdout.isBlank()) {
                    throw new IllegalStateException("원격 목록 응답이 비어 있습니다.");
                }
                return objectMapper.readValue(stdout, new TypeReference<Map<String, Object>>() {});
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                String err = String.valueOf(row.getOrDefault("stderr", ""));
                throw new IllegalStateException(err == null || err.isBlank() ? "원격 목록 조회 실패" : err);
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("원격 목록 조회 타임아웃");
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
        Button runAllBtn;
        Button runNoneBtn;
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
            runAllBtn = (Button) loader.getNamespace().get("runAllBtn");
            runNoneBtn = (Button) loader.getNamespace().get("runNoneBtn");
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
            // 마지막 선택된 항목 제거
            if (!items.isEmpty()) {
                items.remove(items.size() - 1);
                refreshItemsList(itemsList, items);
            }
        });

        runAllBtn.setOnAction(e -> {
            items.forEach(item -> item.setRunAfterTransfer(true));
            refreshItemsList(itemsList, items);
        });

        runNoneBtn.setOnAction(e -> {
            items.forEach(item -> item.setRunAfterTransfer(false));
            refreshItemsList(itemsList, items);
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
            
            TransferSource source = new TransferSource(zipPath, "multi-transfer", "MultiTransfer.zip", true, true, destMode, destPath, runAfter);
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
                                  boolean preRunAfter) {
        // Compact constructor for backward compatibility
        public static TransferSource simple(Path path, String sourceType, String displayName, boolean zipped, boolean temporaryArtifact) {
            return new TransferSource(path, sourceType, displayName, zipped, temporaryArtifact, "DOWNLOADS", "", false);
        }
    }

    private record ScriptExecutionOptions(String scriptPath,
                                          String runMode,
                                          String windowMode,
                                          String args,
                                          boolean transferCommand,
                                          String sourceType,
                                          String displayName,
                                          String destinationMode,
                                          String destinationPath,
                                          boolean runAfterTransfer,
                                          boolean temporaryArtifact) {
        private static ScriptExecutionOptions none() {
            return new ScriptExecutionOptions(null, "FOREGROUND", "HIDDEN", "", false,
                    "file", "", "DOWNLOADS", "", false, false);
        }

        private boolean hasScript() {
            return scriptPath != null && !scriptPath.isBlank();
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
            }
            return payload;
        }
    }

    private static class RemoteBrowseEntry {
        private final String name;
        private final String type;
        private final long size;

        private RemoteBrowseEntry(String name, String type, long size) {
            this.name = name;
            this.type = type;
            this.size = size;
        }

        @Override
        public String toString() {
            String icon = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                case "drive" -> "💽";
                case "dir" -> "📁";
                default -> "📄";
            };
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
