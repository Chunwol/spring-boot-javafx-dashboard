package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
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
import javafx.stage.FileChooser;

import java.io.File;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

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

    // 데이터
    private List<PcStatusDto> allPcs = new ArrayList<>();
    // groupName → groupId
    private LinkedHashMap<String, Long> classroomMap = new LinkedHashMap<>();

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
        root.setStyle("-fx-background-color: #ffffff;");

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
        row.setStyle("-fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0; -fx-padding: 0 0 12 0;");

        Label viewLabel = new Label("보기:");
        viewLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

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
        refreshBtn.setStyle("-fx-font-size: 11; -fx-padding: 5 12; -fx-background-color: #e2e8f0; -fx-cursor: hand;");
        refreshBtn.setOnAction(e -> loadAllData());

        summaryLabel = new Label();
        summaryLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #64748b;");
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        row.getChildren().addAll(viewLabel, viewTypeCombo, viewClassroomCombo, refreshBtn, summaryLabel);
        return row;
    }

    // ─────────────────────────────────────────
    // PC 목록 테이블 (멀티셀렉션)
    // ─────────────────────────────────────────

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
                lbl.setStyle("TEACHER".equals(role)
                    ? "-fx-text-fill: #7c3aed; -fx-font-weight: bold;"
                    : "-fx-text-fill: #374151;");
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
                lbl.setStyle(pc.isOnline()
                    ? "-fx-text-fill: #10b981; -fx-font-weight: bold;"
                    : "-fx-text-fill: #9ca3af;");
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
        vbox.setStyle("-fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0; -fx-padding: 12 0 0 0;");

        Label titleLabel = new Label("명령 전송");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        HBox formRow = new HBox(10);
        formRow.setAlignment(Pos.CENTER_LEFT);

        // ── 대상 선택
        Label targetLbl = new Label("대상:");
        targetLbl.setStyle("-fx-font-size: 12;");

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
        cmdLbl.setStyle("-fx-font-size: 12;");

        commandCombo = new ComboBox<>(FXCollections.observableArrayList(
            "PC 켜기(PCON)", "재시작", "종료", "스크립트 실행"
        ));
        commandCombo.setValue("재시작");
        commandCombo.setPrefWidth(140);

        // ── 전송 버튼
        Button sendBtn = new Button("▶ 전송");
        sendBtn.setStyle("-fx-padding: 8 18; -fx-font-size: 12; -fx-font-weight: bold;"
            + "-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-cursor: hand; -fx-background-radius: 6;");
        sendBtn.setOnAction(e -> handleSendCommand());

        formRow.getChildren().addAll(targetLbl, targetCombo, targetClassroomCombo, cmdLbl, commandCombo, sendBtn);

        // 선택 수 힌트
        selectionHintLabel = new Label();
        selectionHintLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #6366f1; -fx-font-style: italic;");

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
                    allPcs = objectMapper.readValue(resp.body(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, PcStatusDto.class));

                    // PC 데이터로부터 강의실 목록 구성 (groupName → groupId)
                    LinkedHashMap<String, Long> map = new LinkedHashMap<>();
                    allPcs.stream()
                        .filter(p -> p.getGroupId() != null && p.getGroupName() != null && !"-".equals(p.getGroupName()))
                        .sorted(Comparator.comparing(p -> Objects.requireNonNull(p.getGroupName())))
                        .forEach(p -> map.putIfAbsent(p.getGroupName(), p.getGroupId()));
                    classroomMap = map;

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

        String scriptPath = null;
        if ("RUN_PS1".equals(command)) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("PowerShell 스크립트 선택");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerShell 파일", "*.ps1"));
            File selected = fileChooser.showOpenDialog(null);
            if (selected == null) {
                return;
            }
            scriptPath = selected.getAbsolutePath();
        }

        showConfirmationDialog(command, commandLabel, targetPcs, scriptPath);
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

    private void showConfirmationDialog(String commandType, String commandLabel, List<PcStatusDto> targetPcs, String scriptPath) {
        long onlineCount = targetPcs.stream().filter(PcStatusDto::isOnline).count();
        long offlineCount = targetPcs.size() - onlineCount;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("명령 전송 확인");
        dialog.setHeaderText(null);

        VBox content = new VBox(12);
        content.setPadding(new Insets(15));
        content.setPrefWidth(600);
        content.setPrefHeight(500);

        VBox summaryBox = new VBox(4);
        summaryBox.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6;");
        summaryBox.getChildren().addAll(
            styledLabel("명령: " + commandLabel, "-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: #1e3a8a;"),
            styledLabel("총 대상: " + targetPcs.size() + "대", "-fx-font-size: 12; -fx-text-fill: #374151;"),
            styledLabel("미전송: " + offlineCount + "대 (오프라인)", "-fx-font-size: 12; -fx-text-fill: #9ca3af;"),
            styledLabel("전송 예정: " + onlineCount + "대 (온라인)", "-fx-font-size: 12; -fx-text-fill: #10b981; -fx-font-weight: bold;")
        );
        if ("RUN_PS1".equals(commandType) && scriptPath != null) {
            summaryBox.getChildren().add(styledLabel("스크립트: " + scriptPath, "-fx-font-size: 11; -fx-text-fill: #475569;"));
        }
        content.getChildren().add(summaryBox);

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
                lbl.setStyle(pc.isOnline()
                    ? "-fx-text-fill: #10b981; -fx-font-weight: bold;"
                    : "-fx-text-fill: #dc2626; -fx-font-weight: bold;");
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
            okBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold;");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            executeCommand(commandType, targetPcs, scriptPath);
        }
    }

    // ─────────────────────────────────────────
    // 명령 실행 (온라인 PC만)
    // ─────────────────────────────────────────

    private void executeCommand(String commandType, List<PcStatusDto> targetPcs, String scriptPath) {
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
            for (PcStatusDto pc : onlinePcs) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("scope", "SINGLE");
                    body.put("macAddress", pc.getMacAddress());
                    body.put("type", commandType);
                    if ("RUN_PS1".equals(commandType) && scriptPath != null && !scriptPath.isBlank()) {
                        body.put("scriptUrl", scriptPath);
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
            default -> label;
        };
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
}


