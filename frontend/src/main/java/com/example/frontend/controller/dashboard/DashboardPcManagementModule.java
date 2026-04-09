package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
import com.example.util.MultipartUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    // 뷰 컨트롤
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

    public DashboardPcManagementModule(HttpClient httpClient, ObjectMapper objectMapper, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
    }

    // ─────────────────────────────────────────
    // 뷰 생성
    // ─────────────────────────────────────────

    public Node createView() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(15));
        root.setFillWidth(true);
        root.setMaxWidth(Double.MAX_VALUE);
        root.getStyleClass().add("pcmgmt-root");

        root.getChildren().add(buildViewControls());
        root.getChildren().add(buildPcTable());
        root.getChildren().add(buildCommandSection());

        loadAllData();

        ScrollPane scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setPannable(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scrollPane;
    }

    // ─────────────────────────────────────────
    // 보기 선택 영역
    // ─────────────────────────────────────────

    private Node buildViewControls() {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("pcmgmt-section-divider");

        Label viewLabel = new Label("보기:");
        viewLabel.getStyleClass().add("pcmgmt-label-title");

        viewTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
            "전체 PC", "강의실별", "교사 PC만"
        ));
        viewTypeCombo.setValue("전체 PC");
        viewTypeCombo.setPrefWidth(110);

        // 강의실별 선택 시 나타날 강의실 ComboBox
        viewClassroomCombo = new ComboBox<>();
        viewClassroomCombo.setPrefWidth(150);
        viewClassroomCombo.setVisible(false);
        viewClassroomCombo.setManaged(false);
        viewClassroomCombo.setOnAction(e -> applyViewFilter());

        viewTypeCombo.setOnAction(e -> {
            boolean isClassroom = "강의실별".equals(viewTypeCombo.getValue());
            viewClassroomCombo.setVisible(isClassroom);
            viewClassroomCombo.setManaged(isClassroom);
            applyViewFilter();
        });

        Button refreshBtn = new Button("새로고침");
        refreshBtn.getStyleClass().add("pcmgmt-refresh-btn");
        refreshBtn.setOnAction(e -> loadAllData());

        summaryLabel = new Label();
        summaryLabel.getStyleClass().add("pcmgmt-summary-label");
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        row.getChildren().addAll(viewLabel, viewTypeCombo, viewClassroomCombo, refreshBtn, summaryLabel);
        return row;
    }

    // ─────────────────────────────────────────
    // PC 목록 테이블 (멀티셀렉션)
    // ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Node buildPcTable() {
        pcTable = new TableView<>();
        pcTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        pcTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(pcTable, Priority.ALWAYS);
        pcTable.setMinHeight(300);

        // 강의실 열
        TableColumn<PcStatusDto, String> classroomCol = new TableColumn<>("강의실");
        classroomCol.setPrefWidth(100);
        classroomCol.setCellValueFactory(c ->
            Bindings.createStringBinding(() ->
                c.getValue().getGroupName() != null ? c.getValue().getGroupName() : "-"
            )
        );

        // IP 열
        TableColumn<PcStatusDto, String> ipCol = new TableColumn<>("IP");
        ipCol.setPrefWidth(130);
        ipCol.setCellValueFactory(c ->
            Bindings.createStringBinding(() ->
                c.getValue().getIpAddress() != null ? c.getValue().getIpAddress() : "-"
            )
        );

        // 호스트명 열
        TableColumn<PcStatusDto, String> hostnameCol = new TableColumn<>("호스트명");
        hostnameCol.setPrefWidth(120);
        hostnameCol.setCellValueFactory(c ->
            Bindings.createStringBinding(() ->
                c.getValue().getHostname() != null ? c.getValue().getHostname() : "-"
            )
        );

        // 역할 열
        TableColumn<PcStatusDto, Void> roleCol = new TableColumn<>("역할");
        roleCol.setPrefWidth(70);
        roleCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                String role = getTableRow().getItem().getRole();
                Label lbl = new Label("TEACHER".equals(role) ? "교사" : "학생");
                lbl.getStyleClass().add("TEACHER".equals(role) ? "pcmgmt-role-teacher" : "pcmgmt-role-student");
                setGraphic(lbl);
            }
        });

        // 상태 열
        TableColumn<PcStatusDto, Void> statusCol = new TableColumn<>("상태");
        statusCol.setPrefWidth(80);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                PcStatusDto pc = getTableRow().getItem();
                Label lbl = new Label(pc.isOnline() ? "● 온라인" : "○ 오프라인");
                lbl.getStyleClass().add(pc.isOnline() ? "pcmgmt-status-online" : "pcmgmt-status-offline");
                setGraphic(lbl);
            }
        });

        // 마지막 하트비트 열
        TableColumn<PcStatusDto, String> beatCol = new TableColumn<>("마지막 하트비트");
        beatCol.setPrefWidth(160);
        beatCol.setCellValueFactory(c ->
            Bindings.createStringBinding(() ->
                c.getValue().getLastSeen() != null ? c.getValue().getLastSeen().toString() : "-"
            )
        );

        pcTable.getColumns().addAll(classroomCol, ipCol, hostnameCol, roleCol, statusCol, beatCol);

        // 선택 시 힌트 업데이트
        pcTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<PcStatusDto>) change -> updateSelectionHint()
        );

        VBox box = new VBox(pcTable);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    // ─────────────────────────────────────────
    // 명령 전송 섹션
    // ─────────────────────────────────────────

    private Node buildCommandSection() {
        VBox vbox = new VBox(10);
        vbox.getStyleClass().add("pcmgmt-command-divider");

        Label titleLabel = new Label("명령 전송");
        titleLabel.getStyleClass().add("pcmgmt-command-title");

        HBox formRow = new HBox(10);
        formRow.setAlignment(Pos.CENTER_LEFT);

        // ── 대상 선택
        Label targetLbl = new Label("대상:");
        targetLbl.getStyleClass().add("pcmgmt-command-label");

        targetCombo = new ComboBox<>(FXCollections.observableArrayList(
            "전체 PC", "선택 강의실", "교사 PC만", "선택 PC"
        ));
        targetCombo.setValue("전체 PC");
        targetCombo.setPrefWidth(120);

        // 선택 강의실용 ComboBox
        targetClassroomCombo = new ComboBox<>();
        targetClassroomCombo.setPrefWidth(150);
        targetClassroomCombo.setVisible(false);
        targetClassroomCombo.setManaged(false);

        targetCombo.setOnAction(e -> {
            boolean isClassroom = "선택 강의실".equals(targetCombo.getValue());
            targetClassroomCombo.setVisible(isClassroom);
            targetClassroomCombo.setManaged(isClassroom);
            updateSelectionHint();
        });

        // ── 명령 선택
        Label cmdLbl = new Label("명령:");
        cmdLbl.getStyleClass().add("pcmgmt-command-label");

        commandCombo = new ComboBox<>(FXCollections.observableArrayList(
            "PC 켜기(PCON)", "재시작", "종료", "스크립트 실행", "파일/폴더 전송"
        ));
        commandCombo.setValue("재시작");
        commandCombo.setPrefWidth(140);

        // ── 전송 버튼
        Button sendBtn = new Button("▶ 전송");
        sendBtn.getStyleClass().add("pcmgmt-send-btn");
        sendBtn.setOnAction(e -> handleSendCommand());

        formRow.getChildren().addAll(targetLbl, targetCombo, targetClassroomCombo, cmdLbl, commandCombo, sendBtn);

        // 선택 수 힌트
        selectionHintLabel = new Label();
        selectionHintLabel.getStyleClass().add("pcmgmt-selection-hint");

        vbox.getChildren().addAll(titleLabel, formRow, selectionHintLabel);
        return vbox;
    }

    // ─────────────────────────────────────────
    // 데이터 로드
    // ─────────────────────────────────────────

    private void loadAllData() {
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = authorizedRequest(apiPath("/status/all"))
                    .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    List<PcStatusDto> parsed = objectMapper.readValue(resp.body(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, PcStatusDto.class));

                    // PC 데이터로부터 강의실 목록 구성 (groupName → groupId)
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
                } else {
                    log.warn("/status/all returned HTTP {}", resp.statusCode());
                }
            } catch (Exception ex) {
                log.error("loadAllData failed", ex);
            }
        }, "pcmgmt-data-load");
        t.setDaemon(true);
        t.start();
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

        if ("교사 PC만".equals(viewType)) {
            display = allPcs.stream()
                .filter(p -> "TEACHER".equals(p.getRole()))
                .collect(Collectors.toList());
        } else if ("강의실별".equals(viewType) && viewClassroomCombo.getValue() != null) {
            Long gid = classroomMap.get(viewClassroomCombo.getValue());
            display = allPcs.stream()
                .filter(p -> gid != null && gid.equals(p.getGroupId()))
                .collect(Collectors.toList());
        } else {
            display = new ArrayList<>(allPcs);
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
        }

        showConfirmationDialog(commandTypeForQueue, commandLabel, targetPcs, transferSource);
    }

    private List<PcStatusDto> resolveTargetPcs(String target) {
        return switch (target) {
            case "전체 PC" -> new ArrayList<>(allPcs);
            case "교사 PC만" -> allPcs.stream()
                .filter(p -> "TEACHER".equals(p.getRole()))
                .collect(Collectors.toList());
            case "선택 강의실" -> {
                Long gid = classroomMap.get(targetClassroomCombo.getValue());
                yield gid == null ? List.of() : allPcs.stream()
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
    private void showConfirmationDialog(String commandType, String commandLabel, List<PcStatusDto> targetPcs, TransferSource transferSource) {
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

        VBox content = new VBox(12);
        content.setPadding(new Insets(15));
        content.setPrefWidth(600);
        content.setPrefHeight(500);

        VBox summaryBox = new VBox(4);
        summaryBox.getStyleClass().add("pcmgmt-dialog-card");
        summaryBox.getChildren().addAll(
            styledLabel("명령: " + commandLabel, "-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: #1e3a8a;"),
            styledLabel("총 대상: " + targetPcs.size() + "대", "-fx-font-size: 12; -fx-text-fill: #374151;"),
            styledLabel("미전송: " + offlineCount + "대 (오프라인)", "-fx-font-size: 12; -fx-text-fill: #9ca3af;"),
            styledLabel("전송 예정: " + onlineCount + "대 (온라인)", "-fx-font-size: 12; -fx-text-fill: #10b981; -fx-font-weight: bold;")
        );
        if (transferSource != null) {
            String sourceLabel = transferCommand ? "전송원본" : "스크립트";
            summaryBox.getChildren().add(styledLabel(sourceLabel + ": " + transferSource.displayName(), "-fx-font-size: 11; -fx-text-fill: #475569;"));
        }
        content.getChildren().add(summaryBox);

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
                "-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: #334155;"
            );
            HBox modeRow = new HBox(8);
            modeRow.setAlignment(Pos.CENTER_LEFT);
            Label modeLabel = styledLabel("결과 처리", "-fx-font-size: 12; -fx-text-fill: #475569;");
            ComboBox<String> modeComboLocal = new ComboBox<>(FXCollections.observableArrayList(
                "대기 실행 (완료까지 수집)", "즉시 실행 (시작만 확인)"
            ));
            modeComboLocal.setValue(transferCommand ? "대기 실행 (완료까지 수집)" : "대기 실행 (완료까지 수집)");
            modeComboLocal.setPrefWidth(220);
            modeRow.getChildren().addAll(modeLabel, modeComboLocal);

            HBox windowRow = new HBox(8);
            windowRow.setAlignment(Pos.CENTER_LEFT);
            Label windowLabel = styledLabel("콘솔 창", "-fx-font-size: 12; -fx-text-fill: #475569;");
            ComboBox<String> windowComboLocal = new ComboBox<>(FXCollections.observableArrayList(
                "창 숨김", "창 표시"
            ));
            windowComboLocal.setValue("창 숨김");
            windowComboLocal.setPrefWidth(220);
            windowRow.getChildren().addAll(windowLabel, windowComboLocal);

            VBox argsBox = new VBox(4);
            Label argsLabel = styledLabel("추가 인자", "-fx-font-size: 12; -fx-text-fill: #475569;");
            TextField argsFieldLocal = new TextField();
            argsFieldLocal.setPromptText("예: -Mode Test");

            VBox transferDestBox = new VBox(6);
            Label destinationLabel = styledLabel("대상 PC 저장 위치", "-fx-font-size: 12; -fx-text-fill: #475569;");
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
                "-fx-font-size: 11; -fx-text-fill: #64748b;"
            );

            argsBox.getChildren().addAll(argsLabel, argsFieldLocal);
            transferDestBox.getChildren().addAll(destinationLabel, destinationComboLocal, destinationPathLocal, runAfterTransferLocal);

            if (transferCommand) {
                optionsBox.getChildren().addAll(optionTitle, transferDestBox, modeRow, windowRow, argsBox, hintLabel);
            } else {
                optionsBox.getChildren().addAll(optionTitle, modeRow, windowRow, argsBox, hintLabel);
            }
            content.getChildren().add(optionsBox);
            runModeCombo = modeComboLocal;
            windowModeCombo = windowComboLocal;
            argsField = argsFieldLocal;
            destinationCombo = destinationComboLocal;
            destinationPathField = destinationPathLocal;
            runAfterTransferCheck = runAfterTransferLocal;
        }

        Label listTitle = styledLabel("대상 PC 목록", "-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: #374151;");
        content.getChildren().add(listTitle);

        TableView<PcStatusDto> confirmTable = new TableView<>();
        confirmTable.setPrefHeight(300);
        confirmTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<PcStatusDto, String> cRoom = new TableColumn<>("강의실");
        cRoom.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getGroupName() != null ? c.getValue().getGroupName() : "-"
        ));

        TableColumn<PcStatusDto, String> cIp = new TableColumn<>("IP");
        cIp.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getIpAddress() != null ? c.getValue().getIpAddress() : "-"
        ));

        TableColumn<PcStatusDto, String> cHost = new TableColumn<>("호스트명");
        cHost.setCellValueFactory(c -> Bindings.createStringBinding(
            () -> c.getValue().getHostname() != null ? c.getValue().getHostname() : "-"
        ));

        TableColumn<PcStatusDto, Void> cStatus = new TableColumn<>("전송 여부");
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

        confirmTable.getColumns().addAll(cRoom, cIp, cHost, cStatus);

        List<PcStatusDto> sorted = targetPcs.stream()
            .sorted((a, b) -> Boolean.compare(b.isOnline(), a.isOnline()))
            .collect(Collectors.toList());
        confirmTable.setItems(FXCollections.observableArrayList(sorted));
        content.getChildren().add(confirmTable);

        Label note = styledLabel(
            "※ 오프라인 PC는 명령을 전송하지 않으며, 미전송 상태로 기록됩니다.",
            "-fx-font-size: 11; -fx-text-fill: #64748b; -fx-font-style: italic;"
        );
        content.getChildren().add(note);

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
            executeCommand(commandType, targetPcs, scriptOptions);
        }
    }

    // ─────────────────────────────────────────
    // 명령 실행 (온라인 PC만)
    // ─────────────────────────────────────────

    private void executeCommand(String commandType, List<PcStatusDto> targetPcs, ScriptExecutionOptions scriptOptions) {
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
                    Map<String, String> uploadHeaders = new HashMap<>();
                    String bearerToken = apiClient.getBearerToken();
                    if (bearerToken != null && !bearerToken.isBlank()) {
                        uploadHeaders.put("Authorization", "Bearer " + bearerToken);
                    }
                    HttpRequest uploadRequest = MultipartUtil
                            .newMultipartRequest(apiPath("/scripts"), Map.of("file", scriptOptions.scriptPath()), uploadHeaders)
                            .build();
                    HttpResponse<String> uploadResp = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
                    if (uploadResp.statusCode() < 200 || uploadResp.statusCode() >= 300) {
                        throw new RuntimeException("파일 업로드 실패 HTTP=" + uploadResp.statusCode());
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> uploadBody = objectMapper.readValue(uploadResp.body(), Map.class);
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
                    }

                    HttpRequest req = authorizedRequest(apiPath("/commands"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();

                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        success++;
                    } else {
                        fail++;
                    }
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

    private Label styledLabel(String text, String style) {
        Label l = new Label(text);
        l.setStyle(style);
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
            default -> label;
        };
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
        
        VBox mainContent = new VBox(10);
        mainContent.setPadding(new Insets(15));
        
        // ===== 상단: 설명 =====
        Label instrLabel = new Label("전송할 파일 또는 폴더를 선택하세요. 항목별 또는 전체 실행 여부를 제어할 수 있습니다.");
        instrLabel.getStyleClass().add("pcmgmt-transfer-info");
        
        // ===== 중앙: 좌측(목록) + 우측(버튼) =====
        HBox centerBox = new HBox(15);
        centerBox.setPrefHeight(300);
        HBox.setHgrow(centerBox, Priority.ALWAYS);
        
        // 좌측: 선택된 항목 목록
        VBox itemsPanel = new VBox(8);
        itemsPanel.getStyleClass().add("pcmgmt-transfer-panel");
        itemsPanel.setPrefWidth(640);
        
        Label itemsLabel = new Label("선택된 항목:");
        itemsLabel.getStyleClass().add("pcmgmt-transfer-section-title");
        
        VBox itemsList = new VBox(5);
        itemsList.setPadding(new Insets(5));
        
        ScrollPane itemsScroll = new ScrollPane(itemsList);
        itemsScroll.setFitToWidth(true);
        itemsScroll.setPrefHeight(250);
        itemsScroll.getStyleClass().add("pcmgmt-transfer-list-scroll");
        
        itemsPanel.getChildren().addAll(itemsLabel, itemsScroll);
        HBox.setHgrow(itemsPanel, Priority.ALWAYS);
        
        // 우측: 버튼들
        VBox buttonsPanel = new VBox(10);
        buttonsPanel.setAlignment(Pos.TOP_CENTER);
        buttonsPanel.setPrefWidth(120);
        
        Button addFileBtn = new Button("+ 파일");
        addFileBtn.setPrefWidth(100);
        addFileBtn.getStyleClass().add("pcmgmt-transfer-btn-file");
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
        
        Button addFolderBtn = new Button("+ 폴더");
        addFolderBtn.setPrefWidth(100);
        addFolderBtn.getStyleClass().add("pcmgmt-transfer-btn-folder");
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
        
        Button removeBtn = new Button("- 제거");
        removeBtn.setPrefWidth(100);
        removeBtn.getStyleClass().add("pcmgmt-transfer-btn-remove");
        removeBtn.setOnAction(e -> {
            // 마지막 선택된 항목 제거
            if (!items.isEmpty()) {
                items.remove(items.size() - 1);
                refreshItemsList(itemsList, items);
            }
        });
        
        Button runAllBtn = new Button("전체\n실행");
        runAllBtn.setPrefWidth(100);
        runAllBtn.setWrapText(true);
        runAllBtn.getStyleClass().add("pcmgmt-transfer-btn-all-run");
        runAllBtn.setOnAction(e -> {
            items.forEach(item -> item.setRunAfterTransfer(true));
            refreshItemsList(itemsList, items);
        });

        Button runNoneBtn = new Button("전체\n미실행");
        runNoneBtn.setPrefWidth(100);
        runNoneBtn.setWrapText(true);
        runNoneBtn.getStyleClass().add("pcmgmt-transfer-btn-all-stop");
        runNoneBtn.setOnAction(e -> {
            items.forEach(item -> item.setRunAfterTransfer(false));
            refreshItemsList(itemsList, items);
        });

        buttonsPanel.getChildren().addAll(addFileBtn, addFolderBtn, removeBtn, new Separator(), runAllBtn, runNoneBtn);
        
        centerBox.getChildren().addAll(itemsPanel, buttonsPanel);
        
        // ===== 하단: 옵션 =====
        VBox optionsBox = new VBox(8);
        optionsBox.getStyleClass().add("pcmgmt-transfer-options");

        // 목적지 선택
        Label destLabel = new Label("목적지 저장 경로:");
        destLabel.getStyleClass().add("pcmgmt-transfer-section-title");

        VBox destBox = new VBox(6);
        destBox.setAlignment(Pos.TOP_LEFT);
        
        ToggleGroup destGroup = new ToggleGroup();
        
        RadioButton downloadRadio = new RadioButton("다운로드 폴더");
        downloadRadio.setToggleGroup(destGroup);
        downloadRadio.setSelected(true);
        downloadRadio.setWrapText(true);
        
        RadioButton desktopRadio = new RadioButton("바탕화면");
        desktopRadio.setToggleGroup(destGroup);
        desktopRadio.setWrapText(true);
        
        RadioButton customRadio = new RadioButton("경로 직접\n입력");
        customRadio.setToggleGroup(destGroup);
        customRadio.setWrapText(true);
        
        TextField customPathField = new TextField();
        customPathField.setPromptText("경로 입력...");
        customPathField.setPrefWidth(420);
        customPathField.setDisable(true);
        
        customRadio.selectedProperty().addListener((obs, old, newVal) -> {
            customPathField.setDisable(!newVal);
        });
        
        destBox.getChildren().addAll(downloadRadio, desktopRadio, customRadio, customPathField);

        Label runHint = new Label("실행 체크된 항목이 하나 이상이면 전송 후 자동 실행이 활성화됩니다.");
        runHint.getStyleClass().add("pcmgmt-transfer-run-hint");

        optionsBox.getChildren().addAll(destLabel, destBox, runHint);
        
        mainContent.getChildren().addAll(instrLabel, centerBox, optionsBox);
        
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
            HBox itemRow = new HBox(10);
            itemRow.getStyleClass().add("pcmgmt-transfer-item-row");
            itemRow.setAlignment(Pos.CENTER_LEFT);
            
            String typeIcon = item.type().equals("file") ? "📄" : "📁";
            Label nameLabel = new Label(typeIcon + " " + item.displayName());
            nameLabel.setPrefWidth(260);
            nameLabel.getStyleClass().add("pcmgmt-transfer-item-name");

            Label sizeLabel = new Label(formatFileSize(item.fileSize()));
            sizeLabel.setPrefWidth(90);
            sizeLabel.getStyleClass().add("pcmgmt-transfer-item-size");

            Label typeLabel = new Label(item.type().equals("file") ? "파일" : "폴더");
            typeLabel.setPrefWidth(50);
            typeLabel.getStyleClass().add("pcmgmt-transfer-item-type");

            CheckBox runCheck = new CheckBox("실행");
            runCheck.setSelected(item.runAfterTransfer());
            final int index = i;
            runCheck.selectedProperty().addListener((obs, old, newVal) -> items.get(index).setRunAfterTransfer(newVal));

            itemRow.getChildren().addAll(nameLabel, sizeLabel, typeLabel, runCheck);
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

    private Path zipDirectoryToTemp(Path sourceDir) throws IOException {
        String safeName = sourceDir.getFileName() == null ? "folder" : sourceDir.getFileName().toString();
        Path tmpZip = Files.createTempFile("pcmgmt-transfer-" + safeName + "-", ".zip");
        Path base = sourceDir.toAbsolutePath().normalize();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmpZip))) {
            Files.walk(base)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    Path rel = base.relativize(path);
                    String entryName = rel.toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(entryName);
                    try {
                        zos.putNextEntry(entry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (UncheckedIOException ex) {
            Files.deleteIfExists(tmpZip);
            throw ex.getCause();
        }

        return tmpZip;
    }

    private String apiPath(String path) {
        if (path == null || path.isBlank()) {
            return apiBaseUrl;
        }
        return path.startsWith("/") ? apiBaseUrl + path : apiBaseUrl + "/" + path;
    }

    private HttpRequest.Builder authorizedRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(java.net.URI.create(url));
        String bearerToken = apiClient.getBearerToken();
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return builder;
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


