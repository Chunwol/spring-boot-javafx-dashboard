package com.example.frontend.controller.tab;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.dto.dashboard.PcStatusDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 강의실 현황 탭 컨트롤러.
 *
 * <h2>주요 기능</h2>
 * <ul>
 *   <li>강의실 목록 테이블 — 강의실명, PC상태 바, 온라인 비율, 상태 배지, 마지막 갱신</li>
 *   <li>행 클릭 → 우측 상세 패널 슬라이드 인</li>
 *   <li>상세 패널: 온라인/경고/오프라인 카운터 + PC 좌석별 목록 + 전체 켜기/종료</li>
 *   <li>검색 + 강의실/상태 필터 + 즐겨찾기</li>
 * </ul>
 */
public class ClassroomStatusController {

    private static final Logger log = LoggerFactory.getLogger(ClassroomStatusController.class);
    private static final DateTimeFormatter UPDATED_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final double DETAIL_PANEL_WIDTH = 360.0;
    private static final double PC_CARD_MIN_SIZE = 34.0;
    private static final double PC_CARD_MAX_SIZE = 48.0;
    private static final double TEACHER_PC_CARD_HEIGHT = 44.0;
    private static final double OVERVIEW_TEACHER_PC_CARD_HEIGHT = 28.0;
    private static final double DETAIL_GRID_SAFE_INSET = 18.0;
    private static final double DETAIL_GRID_FALLBACK_INSET = 56.0;
    private static final double OVERVIEW_PC_CARD_MIN_SIZE = 24.0;
    private static final double OVERVIEW_PC_CARD_MAX_SIZE = 36.0;
    private static final int DETAIL_STUDENT_TARGET_ROWS = 6;
    private static final int MAIN_STUDENT_TARGET_ROWS = 4;
    private static final double OVERVIEW_ROOM_GAP = 12.0;
    private static final double OVERVIEW_CARD_MIN_WIDTH = 168.0;

    // ── Left: classroom table ──────────────────────────────────────────────
    @FXML private Button downloadBtn, printBtn;
    @FXML private ComboBox<String> classroomFilter;
    @FXML private ComboBox<String> statusFilter;
    @FXML private Button addClassroomBtn;
    @FXML private Button refreshBtn;
    @FXML private Button fullscreenBtn;
    @FXML private Button mainViewListBtn, mainViewGridBtn;
    @FXML private ScrollPane mainGridScroll;
    @FXML private VBox mainGridContent;
    @FXML private TableView<ClassroomSummaryDto> classroomTable;
    @FXML private TableColumn<ClassroomSummaryDto, String> colClassroom;
    @FXML private TableColumn<ClassroomSummaryDto, ClassroomSummaryDto> colPcStatus;
    @FXML private TableColumn<ClassroomSummaryDto, String> colOnline;
    @FXML private TableColumn<ClassroomSummaryDto, String> colStatus;
    @FXML private TableColumn<ClassroomSummaryDto, String> colUpdated;

    // ── Right: detail panel ────────────────────────────────────────────────
    @FXML private VBox detailPanel;
    @FXML private Label detailTitleLabel;
    @FXML private Button detailCloseBtn;
    @FXML private Label detailOnlineCount, detailWarnCount, detailOfflineCount;
    @FXML private Button detailViewListBtn, detailViewGridBtn;
    @FXML private TableView<PcStatusDto> detailPcTable;
    @FXML private TableColumn<PcStatusDto, String> colPcSeat;
    @FXML private TableColumn<PcStatusDto, String> colPcPowerTime;
    @FXML private ScrollPane detailPcGridScroll;
    @FXML private Label detailTeacherLabel;
    @FXML private FlowPane detailTeacherGrid;
    @FXML private Label detailStudentLabel;
    @FXML private FlowPane detailStudentGrid;
    @FXML private Label detailUpdatedLabel;
    @FXML private Button detailPowerOnAllBtn, detailPowerOffAllBtn;

    /** 상세 패널 PC 목록 보기 모드. */
    private boolean detailGridMode = false;
    /** 메인 강의실 목록 보기 모드 (false=표, true=그리드). */
    private boolean mainGridMode = false;

    private final ObservableList<ClassroomSummaryDto> allRows = FXCollections.observableArrayList();
    private final FilteredList<ClassroomSummaryDto> filtered = new FilteredList<>(allRows);
    private final ObservableList<PcStatusDto> detailPcRows = FXCollections.observableArrayList();
    private final DashboardService dashboardService = new DashboardService();
    private final ApiClient apiClient = ApiClient.getInstance();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private Timeline panelAnim;
    private Timeline autoRefreshTimer;
    private ClassroomSummaryDto selectedClassroom;
    private FlowPane overviewRoomFlow;
    private List<ClassroomSummaryDto> overviewRooms = List.of();
    private Map<Long, List<PcStatusDto>> overviewPcByGroup = Map.of();
    /** groupId → pcSection VBox. renderMainGrid가 채우고 refreshMainGrid가 재활용한다. */
    private final Map<Long, VBox> overviewPcSectionByGroupId = new LinkedHashMap<>();

    @FXML
    public void initialize() {
        classroomTable.setPlaceholder(new Label("불러오는 중…"));
        setupTable();
        setupDetailTable();
        wireActions();
        wireFilters();
        refresh();
        startAutoRefresh();
    }

    /** 30초 주기 자동 갱신. 상세 패널 열려 있으면 PC 목록도 갱신. */
    private void startAutoRefresh() {
        autoRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            refresh();
            if (selectedClassroom != null) loadPcList(selectedClassroom.getGroupId());
        }));
        autoRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimer.play();

        // 탭이 Scene에서 제거되면 타이머 정지 (메모리 누수 방지)
        classroomTable.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null && autoRefreshTimer != null) {
                autoRefreshTimer.stop();
                autoRefreshTimer = null;
            }
        });
    }

    /** 메인 헤더의 글로벌 검색에서 호출 — 강의실 탭은 검색창을 제거했으므로 no-op. */
    public void setSearchText(String text) {
        // 강의실 현황 탭 내부 검색 제거됨 (필터 콤보만 사용)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Table setup
    // ─────────────────────────────────────────────────────────────────────

    private void setupTable() {
        // 컬럼이 테이블 폭을 꽉 채우도록 → 우측에 빈 여백 컬럼 안 생김
        classroomTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        colOnline.setText("전체/온라인");
        colClassroom.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getGroupName()));
        colOnline.setCellValueFactory(d -> {
            ClassroomSummaryDto r = d.getValue();
            return new SimpleStringProperty(r.getTotalPcs() + "/" + r.getOnlinePcs());
        });
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(deriveStatusLabel(d.getValue())));
        colUpdated.setCellValueFactory(d -> new SimpleStringProperty(LocalDateTime.now().format(UPDATED_FMT)));

        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) { setText(null); setGraphic(null); return; }
                if (val.contains(",")) {
                    HBox box = new HBox(4);
                    box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    for (String part : val.split(",")) {
                        String t = part.trim();
                        Label badge = new Label(t);
                        badge.getStyleClass().addAll("status-badge", "status-badge-" + statusToClass(t));
                        box.getChildren().add(badge);
                    }
                    setGraphic(box);
                } else {
                    Label badge = new Label(val);
                    badge.getStyleClass().addAll("status-badge", "status-badge-" + statusToClass(val));
                    setGraphic(badge);
                }
                setText(null);
            }
        });

        colPcStatus.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(d.getValue()));
        colPcStatus.setCellFactory(col -> new TableCell<>() {
            private final Region track     = new Region();
            private final Region fill      = new Region();
            private final StackPane container = new StackPane(track, fill);

            {
                track.getStyleClass().add("pc-bar-track");
                fill.getStyleClass().add("pc-bar-fill");
                StackPane.setAlignment(fill, javafx.geometry.Pos.CENTER_LEFT);

                // 높이 고정
                for (Region r : new Region[]{track, fill, container}) {
                    r.setPrefHeight(5);
                    r.setMaxHeight(5);
                }
                // ★ 핵심 fix: StackPane이 fill을 자신 폭으로 늘리지 못하게 제한
                //   fill.prefWidth가 ratio*barW로 설정돼도 StackPane이 강제 확장하면 항상 100% 표시됨
                fill.setMaxWidth(Region.USE_PREF_SIZE);

                // 셀 실제 폭 변화 시 fill 폭 갱신
                widthProperty().addListener((obs, ov, nv) -> {
                    ClassroomSummaryDto cur = getItem();
                    if (cur == null) return;
                    updateFillWidth(cur);
                });
            }

            private void updateFillWidth(ClassroomSummaryDto dto) {
                double barW = Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset());
                if (barW <= 0 && getTableColumn() != null) {
                    barW = Math.max(0, getTableColumn().getWidth());
                }
                double ratio = dto.getTotalPcs() > 0
                        ? Math.max(0.0, Math.min(1.0, (double) dto.getOnlinePcs() / dto.getTotalPcs()))
                        : 0.0;
                fill.setPrefWidth(barW * ratio);
            }

            @Override
            protected void updateItem(ClassroomSummaryDto r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    fill.setPrefWidth(0);
                    setGraphic(null);
                    return;
                }
                // 0/0이어도 트랙(회색 바)은 표시, fill만 0으로
                double ratio = r.getTotalPcs() > 0
                        ? Math.max(0.0, Math.min(1.0, (double) r.getOnlinePcs() / r.getTotalPcs()))
                        : 0.0;
                updateFillWidth(r);
                fill.getStyleClass().removeAll("pc-bar-fill-online", "pc-bar-fill-offline");
                fill.getStyleClass().add(ratio > 0 ? "pc-bar-fill-online" : "pc-bar-fill-offline");
                setGraphic(container);
            }
        });

        SortedList<ClassroomSummaryDto> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(classroomTable.comparatorProperty());
        classroomTable.setItems(sorted);

        classroomTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<ClassroomSummaryDto> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty()) showDetail(row.getItem());
            });
            return row;
        });
    }

    private void setupDetailTable() {
        // 좌석명 | 전원 시간 2개 컬럼이 패널 폭을 꽉 채우도록 (빈 3번째 컬럼 제거)
        detailPcTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        colPcSeat.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getPcName() != null ? d.getValue().getPcName() : "-"));
        colPcPowerTime.setCellValueFactory(d -> new SimpleStringProperty(formatPowerTime(d.getValue())));
        detailPcTable.setItems(detailPcRows);
    }

    /** 전원 시간 표시: 마지막 하트비트(lastSeen) + 상태. lastSeen 없으면 상태만. */
    private static String formatPowerTime(PcStatusDto pc) {
        String statusLabel = onlineLabel(pc);
        LocalDateTime seen = pc.getLastSeen();
        if (seen == null) return statusLabel;
        return seen.format(UPDATED_FMT) + " " + statusLabel;
    }

    private static String onlineLabel(PcStatusDto pc) {
        String s = pc.getStatus();
        if (s == null) return pc.isOnline() ? "켜짐" : "꺼짐";
        return switch (s.toUpperCase()) {
            case "ONLINE" -> "켜짐";
            case "OFFLINE" -> "꺼짐";
            case "BROKEN", "FAILED" -> "고장";
            case "IDLE" -> "유휴";
            default -> s;
        };
    }

    private void wireActions() {
        refreshBtn.setOnAction(e -> refresh());
        detailCloseBtn.setOnAction(e -> hideDetail());
        detailPowerOnAllBtn.setOnAction(e -> sendGroupCommand("WOL"));
        detailPowerOffAllBtn.setOnAction(e -> sendGroupCommand("SHUTDOWN"));
        addClassroomBtn.setOnAction(e -> handleAddClassroom());

        // 툴바 아이콘
        downloadBtn.setOnAction(e -> exportCsv());
        printBtn.setOnAction(e -> printTable());

        // PC 목록 뷰 토글 (우측 상세 패널)
        detailViewListBtn.setOnAction(e -> setDetailViewMode(false));
        detailViewGridBtn.setOnAction(e -> setDetailViewMode(true));
        updateDetailViewToggleStyle();

        // 메인 강의실 표/그리드 토글
        mainViewListBtn.setOnAction(e -> setMainViewMode(false));
        mainViewGridBtn.setOnAction(e -> setMainViewMode(true));
        updateMainViewToggleStyle();

        // 전체화면 토글 (강의실 현황 탭 전용)
        fullscreenBtn.setOnAction(e -> toggleFullscreen());
        setSectionVisible(detailTeacherLabel, false);
        setSectionVisible(detailTeacherGrid, false);
        setSectionVisible(detailStudentLabel, false);
        setSectionVisible(detailStudentGrid, false);
        mainGridScroll.viewportBoundsProperty().addListener((obs, ov, nv) -> {
            if (mainGridMode) {
                Platform.runLater(this::layoutOverviewGrid);
            }
        });
        mainGridContent.widthProperty().addListener((obs, ov, nv) -> {
            if (mainGridMode) {
                Platform.runLater(this::layoutOverviewGrid);
            }
        });

        // 상세 패널 그리드 wrap: 실제 ScrollPane viewport 폭 기준으로 카드 크기 자동 조정
        detailPanel.widthProperty().addListener((obs, ov, nv) ->
                Platform.runLater(this::resizeDetailPcGrids));
        detailPcGridScroll.viewportBoundsProperty().addListener((obs, ov, nv) ->
                Platform.runLater(this::resizeDetailPcGrids));
    }

    private boolean fullscreen = false;

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        // 헤더 + 사이드바 숨김
        com.example.frontend.controller.MainController mc =
                com.example.frontend.controller.MainController.getInstance();
        if (mc != null) mc.setChromeHidden(fullscreen);
        // OS 레벨 전체화면
        javafx.stage.Stage stage =
                (javafx.stage.Stage) classroomTable.getScene().getWindow();
        stage.setFullScreen(fullscreen);
        // 아이콘 변경
        var icon = (org.kordamp.ikonli.javafx.FontIcon) fullscreenBtn.getGraphic();
        icon.setIconLiteral(fullscreen ? "mdi2f-fullscreen-exit" : "mdi2f-fullscreen");
    }

    /** 상세 패널 PC 목록 보기 모드 전환 (테이블/그리드). */
    private void setDetailViewMode(boolean grid) {
        if (detailGridMode == grid) return;
        detailGridMode = grid;
        detailPcTable.setVisible(!grid);
        detailPcTable.setManaged(!grid);
        detailPcGridScroll.setVisible(grid);
        detailPcGridScroll.setManaged(grid);
        updateDetailViewToggleStyle();
        if (grid) renderDetailGrid(detailPcRows);
    }

    private void updateDetailViewToggleStyle() {
        toggleActiveClass(detailViewListBtn, !detailGridMode);
        toggleActiveClass(detailViewGridBtn, detailGridMode);
    }

    private static void toggleActiveClass(Node n, boolean active) {
        n.getStyleClass().remove("detail-view-toggle-active");
        if (active) n.getStyleClass().add("detail-view-toggle-active");
    }

    /**
     * PC 카드 그리드 렌더링.
     * 카드 = 정사각형, 좌석명 또는 PC 번호 큰 글씨, 상태별 배경색.
     */
    private void renderDetailGrid(List<PcStatusDto> pcs) {
        List<PcStatusDto> teachers = splitTeachers(pcs);
        List<PcStatusDto> students = splitStudents(pcs);
        if (!tryUpdateFlowPaneStatuses(detailTeacherGrid, teachers)) {
            detailTeacherGrid.getChildren().setAll(buildWideTeacherCards(teachers));
        }
        if (!tryUpdateFlowPaneStatuses(detailStudentGrid, students)) {
            detailStudentGrid.getChildren().setAll(buildStudentCards(students));
        }
        setSectionVisible(detailTeacherLabel, false);
        setSectionVisible(detailTeacherGrid, !teachers.isEmpty());
        setSectionVisible(detailStudentLabel, false);
        setSectionVisible(detailStudentGrid, !students.isEmpty());
        resizeDetailPcGrids();
        Platform.runLater(this::resizeDetailPcGrids);
    }

    /**
     * PC 수가 동일할 때 상태 CSS 클래스만 갱신 — 카드 재생성 없이 떨림 제거.
     * 수가 다르면 false 반환 → 호출자가 setAll로 전체 재빌드.
     */
    private static boolean tryUpdateFlowPaneStatuses(FlowPane flow, List<PcStatusDto> pcs) {
        List<Node> children = flow.getChildren();
        if (children.size() != pcs.size()) return false;
        for (int i = 0; i < pcs.size(); i++) {
            if (!(children.get(i) instanceof Region card)) return false;
            card.getStyleClass().removeAll("pc-card-online", "pc-card-offline", "pc-card-warn");
            card.getStyleClass().add("pc-card-" + pcStatusClass(pcs.get(i)));
        }
        return true;
    }

    /** 정렬 인덱스 기반 번호 카드 (이름에 숫자 없어도 1,2,3... 표시). */
    private static StackPane buildPcCard(PcStatusDto pc, int seatNumber) {
        return buildPcCard(pc, String.valueOf(seatNumber), false);
    }

    private static StackPane buildTeacherPcCard(PcStatusDto pc, int index, int totalTeachers) {
        String label = totalTeachers <= 1 ? "교" : "교" + index;
        return buildPcCard(pc, label, true);
    }

    private static StackPane buildWideTeacherPcCard(PcStatusDto pc, int index, int totalTeachers) {
        return buildWideTeacherPcCard(pc, index, totalTeachers, TEACHER_PC_CARD_HEIGHT);
    }

    private static StackPane buildWideTeacherPcCard(PcStatusDto pc, int index, int totalTeachers, double height) {
        String label = totalTeachers <= 1 ? "교수 PC" : "교수 PC " + index;
        StackPane card = buildPcCard(pc, label, true);
        card.getStyleClass().add("pc-card-teacher-wide");
        applyWideTeacherCardSize(card, 320, height);
        return card;
    }

    private static StackPane buildPcCard(PcStatusDto pc, String labelText, boolean teacher) {
        Label numberLabel = new Label(labelText);
        numberLabel.getStyleClass().add("pc-card-number");
        StackPane card = new StackPane(numberLabel);
        card.getStyleClass().add("pc-card");
        card.getStyleClass().add("pc-card-" + pcStatusClass(pc));
        if (teacher) {
            card.getStyleClass().add("pc-card-teacher");
        }
        applyPcCardSize(card, PC_CARD_MAX_SIZE);
        javafx.scene.control.Tooltip.install(card, new javafx.scene.control.Tooltip(
                pc.getPcName() != null ? pc.getPcName() : "-"));
        return card;
    }

    /** 기존 호환용 (정렬 없이 이름 추출 — buildClassroomSection 외부 호출 시). */
    private static StackPane buildPcCard(PcStatusDto pc) {
        return buildPcCard(pc, extractSeatNumber(pc), isTeacher(pc));
    }

    /** PC 상태 → CSS 클래스 suffix. */
    private static String pcStatusClass(PcStatusDto pc) {
        String s = pc.getStatus() == null ? "" : pc.getStatus().toUpperCase();
        return switch (s) {
            case "ONLINE", "IDLE" -> "online";
            case "BROKEN", "FAILED" -> "warn";
            default -> "offline";
        };
    }

    /** PC 이름에서 좌석 번호 추출. 'PC-12'/'좌석 12' 등 끝의 숫자만 잘라냄. */
    private static String extractSeatNumber(PcStatusDto pc) {
        String name = pc.getPcName();
        if (name == null || name.isBlank()) return "-";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(name);
        return m.find() ? m.group(1) : name;
    }

    /**
     * PC 이름 끝 숫자 기준 오름차순 정렬 (1,2,3…10,11…).
     * 숫자 없으면 이름 사전순 후미.
     */
    private static java.util.Comparator<PcStatusDto> PC_NAME_ORDER =
            java.util.Comparator.comparingInt((PcStatusDto pc) -> {
                String name = pc.getPcName();
                if (name == null) return Integer.MAX_VALUE;
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(name);
                return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
            }).thenComparing(pc -> pc.getPcName() == null ? "" : pc.getPcName());

    private static boolean isTeacher(PcStatusDto pc) {
        return pc != null && "TEACHER".equalsIgnoreCase(pc.getRole());
    }

    private static List<PcStatusDto> splitTeachers(List<PcStatusDto> pcs) {
        List<PcStatusDto> safePcs = pcs == null ? List.of() : pcs;
        return safePcs.stream()
                .filter(ClassroomStatusController::isTeacher)
                .sorted(PC_NAME_ORDER)
                .toList();
    }

    private static List<PcStatusDto> splitStudents(List<PcStatusDto> pcs) {
        List<PcStatusDto> safePcs = pcs == null ? List.of() : pcs;
        return safePcs.stream()
                .filter(pc -> !isTeacher(pc))
                .sorted(PC_NAME_ORDER)
                .toList();
    }

    private static List<Node> buildTeacherCards(List<PcStatusDto> teachers) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < teachers.size(); i++) {
            nodes.add(buildTeacherPcCard(teachers.get(i), i + 1, teachers.size()));
        }
        return nodes;
    }

    private static List<Node> buildWideTeacherCards(List<PcStatusDto> teachers) {
        return buildWideTeacherCards(teachers, TEACHER_PC_CARD_HEIGHT);
    }

    private static List<Node> buildWideTeacherCards(List<PcStatusDto> teachers, double height) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < teachers.size(); i++) {
            nodes.add(buildWideTeacherPcCard(teachers.get(i), i + 1, teachers.size(), height));
        }
        return nodes;
    }

    private static List<Node> buildStudentCards(List<PcStatusDto> students) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            nodes.add(buildPcCard(students.get(i), i + 1));
        }
        return nodes;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Main view: 표/그리드 토글
    // ─────────────────────────────────────────────────────────────────────

    private void setMainViewMode(boolean grid) {
        if (mainGridMode == grid) return;
        mainGridMode = grid;
        classroomTable.setVisible(!grid);
        classroomTable.setManaged(!grid);
        mainGridScroll.setVisible(grid);
        mainGridScroll.setManaged(grid);
        updateMainViewToggleStyle();
        if (grid) renderMainGrid();
    }

    private void updateMainViewToggleStyle() {
        toggleActiveClass(mainViewListBtn, !mainGridMode);
        toggleActiveClass(mainViewGridBtn, mainGridMode);
    }

    /**
     * 메인 그리드 렌더: 강의실 카드를 여러 열로 자동 배치한다.
     * 화면 크기와 강의실 개수에 맞춰 카드 폭과 내부 PC 카드 크기를 같이 조정한다.
     */
    private void renderMainGrid() {
        mainGridContent.getChildren().clear();
        overviewPcSectionByGroupId.clear();
        overviewRooms = new ArrayList<>(filtered);
        overviewPcByGroup = Map.of();

        FlowPane roomFlow = new FlowPane(OVERVIEW_ROOM_GAP, OVERVIEW_ROOM_GAP);
        roomFlow.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        overviewRoomFlow = roomFlow;

        for (ClassroomSummaryDto c : overviewRooms) {
            VBox[] holder = new VBox[1];
            VBox card = buildClassroomCard(c, holder);
            overviewPcSectionByGroupId.put(c.getGroupId(), holder[0]);
            roomFlow.getChildren().add(card);
        }
        mainGridContent.getChildren().add(roomFlow);
        layoutOverviewGrid();
        fetchOverviewPcStatuses(overviewRooms);
    }

    /** PC 전체를 한 번에 조회 → 강의실별로 분배 (N+1 방지). 기존 카드 재사용 시에도 호출됨. */
    private void fetchOverviewPcStatuses(List<ClassroomSummaryDto> rooms) {
        apiClient.getPagedList("/dashboard/pcs", new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(allPcs -> Platform.runLater(() -> {
                    if (allPcs == null) return;
                    overviewPcByGroup = allPcs.stream()
                            .filter(pc -> pc.getGroupId() != null)
                            .collect(java.util.stream.Collectors.groupingBy(PcStatusDto::getGroupId));
                    for (ClassroomSummaryDto c : rooms) {
                        VBox pcSection = overviewPcSectionByGroupId.get(c.getGroupId());
                        if (pcSection == null) continue;
                        List<PcStatusDto> pcs = overviewPcByGroup.getOrDefault(c.getGroupId(), List.of());
                        fillCardPcGrid(pcSection, pcs, c);
                    }
                    layoutOverviewGrid();
                }))
                .exceptionally(ex -> { log.debug("전체 PC 조회 실패: {}", ex.getMessage()); return null; });
    }

    /**
     * 자동 갱신 시 강의실 목록 변경 없으면 카드 재빌드 없이 PC 상태만 갱신 (깜빡임·사라짐 방지).
     * 강의실 추가/삭제/순서 변경 감지 시 전체 재빌드.
     */
    private void refreshMainGrid() {
        List<ClassroomSummaryDto> currentRooms = new ArrayList<>(filtered);
        List<Long> currentIds = currentRooms.stream()
                .map(ClassroomSummaryDto::getGroupId).toList();
        List<Long> existingIds = new ArrayList<>(overviewPcSectionByGroupId.keySet());

        if (!currentIds.equals(existingIds)) {
            renderMainGrid();
            return;
        }

        overviewRooms = currentRooms;
        fetchOverviewPcStatuses(currentRooms);
    }

    /**
     * 강의실 카드 — 상단에 이름+카운터, 하단에 PC 카드 그리드 (교사 먼저, 학생 정렬).
     */
    /** holder[0]에 pcSection VBox 저장. renderMainGrid가 fillCardPcGrid로 채움. */
    private VBox buildClassroomCard(ClassroomSummaryDto c, VBox[] holder) {
        // 이름
        Label title = new Label(c.getGroupName());
        title.getStyleClass().add("classroom-section-title");

        // 카운터 (총/켜짐, 예: 43/22)
        Label ratioLbl = new Label(c.getTotalPcs() + "/" + c.getOnlinePcs());
        ratioLbl.getStyleClass().add("ccard-counter-total");
        HBox counters = new HBox(8, ratioLbl);
        counters.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // PC 카드 그리드 (비동기 로드) — 교사 PC를 상단에 고정하고 학생 PC를 아래에 배치
        FlowPane teacherFlow = new FlowPane(4, 4);
        teacherFlow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        teacherFlow.getProperties().put("pcCardMinSize", OVERVIEW_PC_CARD_MIN_SIZE);
        teacherFlow.getProperties().put("pcCardMaxSize", OVERVIEW_PC_CARD_MAX_SIZE);
        teacherFlow.widthProperty().addListener((obs, ov, nv) ->
                resizeWideTeacherCards(teacherFlow, OVERVIEW_TEACHER_PC_CARD_HEIGHT));
        VBox teacherSection = new VBox(0, teacherFlow);

        FlowPane studentFlow = new FlowPane(4, 4);
        studentFlow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        studentFlow.getProperties().put("pcCardMinSize", OVERVIEW_PC_CARD_MIN_SIZE);
        studentFlow.getProperties().put("pcCardMaxSize", OVERVIEW_PC_CARD_MAX_SIZE);
        studentFlow.widthProperty().addListener((obs, ov, nv) ->
                resizePcCards(studentFlow, MAIN_STUDENT_TARGET_ROWS));
        VBox studentSection = new VBox(0, studentFlow);

        VBox pcSection = new VBox(8, teacherSection, studentSection);
        setSectionVisible(teacherSection, false);
        setSectionVisible(studentSection, false);

        // pcSection 참조를 holder에 담아 renderMainGrid에서 나중에 채울 수 있게
        VBox card = new VBox(6, title, counters, new javafx.scene.control.Separator(), pcSection);
        card.getStyleClass().add("classroom-card");
        card.setMinWidth(OVERVIEW_CARD_MIN_WIDTH);
        card.setPrefWidth(260);
        card.setMaxWidth(Region.USE_PREF_SIZE);
        card.setOnMouseClicked(e -> showDetail(c));

        // pcSection을 holder에 저장; counters는 tag로 달아 fillCardPcGrid에서 갱신
        holder[0] = pcSection;
        card.getProperties().put("groupId", c.getGroupId());
        card.getProperties().put("summary", c);
        card.getProperties().put("teacherSection", teacherSection);
        card.getProperties().put("teacherFlow", teacherFlow);
        card.getProperties().put("studentSection", studentSection);
        card.getProperties().put("studentFlow", studentFlow);
        pcSection.getProperties().put("ratioLbl",  ratioLbl);
        pcSection.getProperties().put("counters",  counters);
        pcSection.getProperties().put("teacherSection", teacherSection);
        pcSection.getProperties().put("teacherFlow", teacherFlow);
        pcSection.getProperties().put("studentSection", studentSection);
        pcSection.getProperties().put("studentFlow", studentFlow);

        return card;
    }

    /** renderMainGrid에서 전체 PC 일괄 로드 후 카드별 PC 그리드 채우기. */
    @SuppressWarnings("unchecked")
    private void fillCardPcGrid(VBox pcSection, List<PcStatusDto> pcs, ClassroomSummaryDto c) {
        VBox teacherSection = (VBox) pcSection.getProperties().get("teacherSection");
        FlowPane teacherFlow = (FlowPane) pcSection.getProperties().get("teacherFlow");
        VBox studentSection = (VBox) pcSection.getProperties().get("studentSection");
        FlowPane studentFlow = (FlowPane) pcSection.getProperties().get("studentFlow");
        Label ratioLbl       = (Label)     pcSection.getProperties().get("ratioLbl");
        HBox  counters       = (HBox)      pcSection.getProperties().get("counters");
        if (teacherFlow == null || studentFlow == null) return;

        List<PcStatusDto> teachers = splitTeachers(pcs);
        List<PcStatusDto> students = splitStudents(pcs);

        if (!tryUpdateFlowPaneStatuses(teacherFlow, teachers)) {
            teacherFlow.getChildren().setAll(buildWideTeacherCards(teachers, OVERVIEW_TEACHER_PC_CARD_HEIGHT));
        }
        if (!tryUpdateFlowPaneStatuses(studentFlow, students)) {
            studentFlow.getChildren().setAll(buildStudentCards(students));
        }
        setSectionVisible(teacherSection, !teachers.isEmpty());
        setSectionVisible(studentSection, !students.isEmpty());
        resizeWideTeacherCards(teacherFlow, OVERVIEW_TEACHER_PC_CARD_HEIGHT);
        resizePcCards(studentFlow, MAIN_STUDENT_TARGET_ROWS);
        Platform.runLater(() -> {
            resizeWideTeacherCards(teacherFlow, OVERVIEW_TEACHER_PC_CARD_HEIGHT);
            resizePcCards(studentFlow, MAIN_STUDENT_TARGET_ROWS);
        });

        if (ratioLbl != null && counters != null) {
            long online = pcs.stream().filter(pc -> "ONLINE".equalsIgnoreCase(pc.getStatus()) || "IDLE".equalsIgnoreCase(pc.getStatus())).count();
            long warn   = pcs.stream().filter(pc -> "BROKEN".equalsIgnoreCase(pc.getStatus()) || "FAILED".equalsIgnoreCase(pc.getStatus())).count();
            Label warnLbl = (Label) pcSection.getProperties().get("warnLbl");
            ratioLbl.setText(pcs.size() + "/" + online);
            if (warn > 0) {
                if (warnLbl == null) {
                    warnLbl = new Label();
                    warnLbl.getStyleClass().add("ccard-counter-warn");
                    counters.getChildren().add(1, warnLbl);
                    pcSection.getProperties().put("warnLbl", warnLbl);
                }
                warnLbl.setText("⚠ " + warn);
            } else if (warnLbl != null) {
                counters.getChildren().remove(warnLbl);
                pcSection.getProperties().remove("warnLbl");
            }
        }
    }

    private void layoutOverviewGrid() {
        if (overviewRoomFlow == null || overviewRoomFlow.getChildren().isEmpty()) {
            return;
        }
        double availableWidth = resolveOverviewViewportWidth();
        double availableHeight = resolveOverviewViewportHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        List<Node> cards = new ArrayList<>(overviewRoomFlow.getChildren());
        int roomCount = cards.size();
        int maxColumns = Math.min(roomCount, 6);
        int bestColumns = 1;
        double bestCardWidth = Math.max(OVERVIEW_CARD_MIN_WIDTH, availableWidth);
        double bestOverflow = Double.POSITIVE_INFINITY;

        for (int columns = 1; columns <= maxColumns; columns++) {
            double candidateWidth = Math.floor((availableWidth - OVERVIEW_ROOM_GAP * (columns - 1)) / columns);
            if (candidateWidth < OVERVIEW_CARD_MIN_WIDTH) {
                continue;
            }
            double totalHeight = estimateOverviewGridHeight(cards, columns, candidateWidth);
            double overflow = Math.max(0, totalHeight - availableHeight);
            if (overflow < bestOverflow - 1
                    || (Math.abs(overflow - bestOverflow) < 1
                    && (columns > bestColumns || (columns == bestColumns && candidateWidth > bestCardWidth)))) {
                bestOverflow = overflow;
                bestColumns = columns;
                bestCardWidth = candidateWidth;
            }
        }

        if (Double.isInfinite(bestOverflow)) {
            bestColumns = Math.min(roomCount, Math.max(1, (int) Math.floor(availableWidth / OVERVIEW_CARD_MIN_WIDTH)));
            bestColumns = Math.max(1, bestColumns);
            bestCardWidth = Math.floor((availableWidth - OVERVIEW_ROOM_GAP * (bestColumns - 1)) / bestColumns);
        }

        for (Node child : cards) {
            if (child instanceof VBox card) {
                applyOverviewCardWidth(card, bestCardWidth);
            }
        }
        overviewRoomFlow.setPrefWrapLength(availableWidth + 1);
    }

    private double resolveOverviewViewportWidth() {
        double width = mainGridScroll == null ? 0 : mainGridScroll.getViewportBounds().getWidth();
        if (width <= 1) {
            width = mainGridContent == null ? 0 : mainGridContent.getWidth();
        }
        return Math.max(0, width - 16);
    }

    private double resolveOverviewViewportHeight() {
        double height = mainGridScroll == null ? 0 : mainGridScroll.getViewportBounds().getHeight();
        if (height <= 1 && mainGridScroll != null) {
            height = mainGridScroll.getHeight();
        }
        return Math.max(0, height - 16);
    }

    private double estimateOverviewGridHeight(List<Node> cards, int columns, double cardWidth) {
        double totalHeight = 0;
        for (int start = 0; start < cards.size(); start += columns) {
            double rowHeight = 0;
            for (int idx = start; idx < Math.min(cards.size(), start + columns); idx++) {
                Node node = cards.get(idx);
                if (!(node instanceof VBox card)) {
                    continue;
                }
                ClassroomSummaryDto summary = (ClassroomSummaryDto) card.getProperties().get("summary");
                Long groupId = summary == null ? null : summary.getGroupId();
                List<PcStatusDto> pcs = groupId == null
                        ? List.of()
                        : overviewPcByGroup.getOrDefault(groupId, List.of());
                rowHeight = Math.max(rowHeight, estimateOverviewCardHeight(cardWidth, summary, pcs));
            }
            if (start > 0) {
                totalHeight += OVERVIEW_ROOM_GAP;
            }
            totalHeight += rowHeight;
        }
        return totalHeight;
    }

    private double estimateOverviewCardHeight(double cardWidth, ClassroomSummaryDto summary, List<PcStatusDto> pcs) {
        int teacherCount = splitTeachers(pcs).size();
        int studentCount = splitStudents(pcs).size();
        if (studentCount == 0 && summary != null) {
            studentCount = Math.max(0, summary.getTotalPcs() - teacherCount);
        }
        double contentWidth = Math.max(120, cardWidth - 24);
        double height = 84;
        if (teacherCount > 0) {
            height += teacherCount * OVERVIEW_TEACHER_PC_CARD_HEIGHT + Math.max(0, teacherCount - 1) * 4;
        }
        if (studentCount > 0) {
            if (teacherCount > 0) {
                height += 8;
            }
            height += estimatePcSectionHeight(
                    contentWidth,
                    studentCount,
                    MAIN_STUDENT_TARGET_ROWS,
                    OVERVIEW_PC_CARD_MIN_SIZE,
                    OVERVIEW_PC_CARD_MAX_SIZE
            );
        }
        return height;
    }

    private double estimatePcSectionHeight(
            double contentWidth,
            int count,
            int targetRows,
            double minCardSize,
            double maxCardSize
    ) {
        PcGridMetrics metrics = calculatePcGridMetrics(contentWidth, count, targetRows, 4, minCardSize, maxCardSize);
        if (metrics.rows == 0) {
            return 0;
        }
        return metrics.rows * metrics.cardSize + Math.max(0, metrics.rows - 1) * 4;
    }

    private void applyOverviewCardWidth(VBox card, double cardWidth) {
        card.setMinWidth(cardWidth);
        card.setPrefWidth(cardWidth);
        card.setMaxWidth(cardWidth);

        FlowPane teacherFlow = (FlowPane) card.getProperties().get("teacherFlow");
        FlowPane studentFlow = (FlowPane) card.getProperties().get("studentFlow");
        if (teacherFlow != null) {
            teacherFlow.setPrefWrapLength(Math.max(120, cardWidth - 24));
            resizeWideTeacherCards(teacherFlow, OVERVIEW_TEACHER_PC_CARD_HEIGHT);
        }
        if (studentFlow != null) {
            studentFlow.setPrefWrapLength(Math.max(120, cardWidth - 24));
            resizePcCards(studentFlow, MAIN_STUDENT_TARGET_ROWS);
        }
    }

    private static void setSectionVisible(Node section, boolean visible) {
        if (section == null) {
            return;
        }
        section.setVisible(visible);
        section.setManaged(visible);
    }

    private void resizeDetailPcGrids() {
        if (detailTeacherGrid == null || detailStudentGrid == null) {
            return;
        }
        double wrapLength = resolveDetailGridWrapLength();
        detailTeacherGrid.setPrefWrapLength(wrapLength);
        detailStudentGrid.setPrefWrapLength(wrapLength);
        resizeWideTeacherCards(detailTeacherGrid);
        resizePcCards(detailStudentGrid, DETAIL_STUDENT_TARGET_ROWS);
    }

    private double resolveDetailGridWrapLength() {
        double width = detailPcGridScroll == null ? 0 : detailPcGridScroll.getViewportBounds().getWidth();
        if (width <= 1 && detailPcGridScroll != null) {
            width = detailPcGridScroll.getWidth();
        }
        if (width <= 1 && detailPanel != null) {
            width = detailPanel.getWidth() - DETAIL_GRID_FALLBACK_INSET;
        }
        if (width <= 1) {
            width = 320;
        }
        return Math.max(120, width - DETAIL_GRID_SAFE_INSET);
    }

    private static void resizePcCards(FlowPane flow, int targetRows) {
        if (flow == null) {
            return;
        }
        int count = flow.getChildren().size();
        if (count == 0) {
            return;
        }
        double width = flow.getWidth();
        double prefWrap = flow.getPrefWrapLength();
        if (prefWrap > 1) {
            width = width > 1 ? Math.min(width, prefWrap) : prefWrap;
        } else if (width <= 1) {
            width = prefWrap;
        }
        if (width <= 1) {
            width = 320;
        }
        double minCardSize = resolveFlowDouble(flow, "pcCardMinSize", PC_CARD_MIN_SIZE);
        double maxCardSize = resolveFlowDouble(flow, "pcCardMaxSize", PC_CARD_MAX_SIZE);
        PcGridMetrics metrics = calculatePcGridMetrics(
                width,
                count,
                targetRows,
                Math.max(0, flow.getHgap()),
                minCardSize,
                maxCardSize
        );
        for (Node child : flow.getChildren()) {
            if (child instanceof Region region) {
                applyPcCardSize(region, metrics.cardSize);
            }
        }
    }

    private static void resizeWideTeacherCards(FlowPane flow) {
        resizeWideTeacherCards(flow, TEACHER_PC_CARD_HEIGHT);
    }

    private static void resizeWideTeacherCards(FlowPane flow, double height) {
        if (flow == null || flow.getChildren().isEmpty()) {
            return;
        }
        double width = flow.getWidth();
        double prefWrap = flow.getPrefWrapLength();
        if (prefWrap > 1) {
            width = width > 1 ? Math.min(width, prefWrap) : prefWrap;
        } else if (width <= 1) {
            width = prefWrap;
        }
        if (width <= 1) {
            width = 320;
        }
        for (Node child : flow.getChildren()) {
            if (child instanceof Region region) {
                applyWideTeacherCardSize(region, width, height);
            }
        }
    }

    private static double resolveFlowDouble(FlowPane flow, String key, double defaultValue) {
        Object value = flow.getProperties().get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private static PcGridMetrics calculatePcGridMetrics(
            double width,
            int count,
            int targetRows,
            double hgap,
            double minCardSize,
            double maxCardSize
    ) {
        if (count <= 0) {
            return new PcGridMetrics(0, 0, maxCardSize);
        }
        int rows = Math.max(1, targetRows);
        int columns = Math.max(1, (int) Math.ceil(count / (double) rows));
        double size = Math.floor((width - hgap * (columns - 1)) / columns);
        if (size < minCardSize) {
            columns = Math.max(1, (int) Math.floor((width + hgap) / (minCardSize + hgap)));
            size = Math.floor((width - hgap * (columns - 1)) / columns);
        }
        size = Math.max(minCardSize, Math.min(maxCardSize, size));
        rows = Math.max(1, (int) Math.ceil(count / (double) columns));
        return new PcGridMetrics(columns, rows, size);
    }

    private static void applyPcCardSize(Region card, double size) {
        card.setPrefSize(size, size);
        card.setMinSize(size, size);
        card.setMaxSize(size, size);
        if (card instanceof StackPane stack
                && !stack.getChildren().isEmpty()
                && stack.getChildren().get(0) instanceof Label label) {
            double fontSize = Math.max(11, Math.min(16, Math.floor(size * 0.45)));
            label.setStyle("-fx-font-size: " + (int) fontSize + "px;");
        }
    }

    private static void applyWideTeacherCardSize(Region card, double width) {
        applyWideTeacherCardSize(card, width, TEACHER_PC_CARD_HEIGHT);
    }

    private static void applyWideTeacherCardSize(Region card, double width, double height) {
        double normalizedWidth = Math.max(120, width);
        double normalizedHeight = Math.max(24, height);
        card.setPrefSize(normalizedWidth, normalizedHeight);
        card.setMinSize(normalizedWidth, normalizedHeight);
        card.setMaxSize(normalizedWidth, normalizedHeight);
        if (card instanceof StackPane stack
                && !stack.getChildren().isEmpty()
                && stack.getChildren().get(0) instanceof Label label) {
            int fontSize = normalizedHeight <= 32 ? 11 : 13;
            label.setStyle("-fx-font-size: " + fontSize + "px;");
        }
    }

    private record PcGridMetrics(int columns, int rows, double cardSize) {}

    /** 현재 테이블을 CSV로 내보내기. */
    private void exportCsv() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("강의실 현황 내보내기");
        fc.setInitialFileName("classroom-status.csv");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = fc.showSaveDialog(classroomTable.getScene().getWindow());
        if (file == null) return;
        StringBuilder sb = new StringBuilder("강의실,온라인,전체,상태,갱신\n");
        for (ClassroomSummaryDto r : filtered) {
            sb.append(escapeCsv(r.getGroupName())).append(',')
              .append(r.getOnlinePcs()).append(',')
              .append(r.getTotalPcs()).append(',')
              .append(deriveStatusLabel(r)).append(',')
              .append(LocalDateTime.now().format(UPDATED_FMT)).append('\n');
        }
        try {
            java.nio.file.Files.writeString(file.toPath(), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            toast("내보내기 완료: " + file.getName(), false);
        } catch (Exception ex) {
            toast("내보내기 실패: " + ex.getMessage(), true);
        }
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private void printTable() {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) { toast("사용 가능한 프린터가 없습니다", true); return; }
        if (job.showPrintDialog(classroomTable.getScene().getWindow())
                && job.printPage(classroomTable)) {
            job.endJob();
            toast("인쇄 요청 완료", false);
        }
    }

    private void wireFilters() {
        statusFilter.getItems().setAll("전체", "온라인", "명령 실행중", "경고", "오프라인");
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.valueProperty().addListener((o, ov, nv) -> applyFilters());
        classroomFilter.valueProperty().addListener((o, ov, nv) -> applyFilters());
    }

    private void applyFilters() {
        String status = statusFilter.getValue();
        String classroom = classroomFilter.getValue();
        filtered.setPredicate(r -> {
            if (r == null) return false;
            if (classroom != null && !"전체".equals(classroom) && !classroom.equals(r.getGroupName())) return false;
            if (status != null && !"전체".equals(status) && !deriveStatusLabel(r).contains(status)) return false;
            return true;
        });
        if (mainGridMode) renderMainGrid();
    }

    // ─────────────────────────────────────────────────────────────────────
    // API
    // ─────────────────────────────────────────────────────────────────────

    public void reloadData() {
        refresh();
    }

    private void refresh() {
        if (!inFlight.compareAndSet(false, true)) return;
        log.info("강의실 조회 시작: baseUrl={}, hasToken={}",
                apiClient.getBaseUrl(), apiClient.getBearerToken() != null);
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
                .thenAccept(list -> Platform.runLater(() -> {
                    log.info("강의실 {}개 수신 → 테이블 반영", list == null ? -1 : list.size());
                    allRows.setAll(list);
                    refreshClassroomFilter();
                    classroomTable.setPlaceholder(new Label(
                            list.isEmpty() ? "표시할 강의실이 없습니다." : ""));
                    if (mainGridMode) refreshMainGrid();
                    else classroomTable.refresh(); // 동일 행 수일 때 셀 재렌더 보장
                    if (selectedClassroom != null) {
                        list.stream()
                                .filter(c -> c.getGroupId().equals(selectedClassroom.getGroupId()))
                                .findFirst().ifPresent(this::showDetail);
                    }
                }))
                .exceptionally(ex -> {
                    log.warn("강의실 목록 로드 실패: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        classroomTable.setPlaceholder(new Label(
                                "강의실 목록을 불러오지 못했습니다.\n서버 연결을 확인하고 [조회]를 눌러 다시 시도하세요."));
                        toast("강의실 목록 로드 실패 — 서버 연결 확인", true);
                    });
                    return null;
                })
                .whenComplete((r, t) -> inFlight.set(false));
    }

    private void refreshClassroomFilter() {
        ObservableList<String> items = FXCollections.observableArrayList();
        items.add("전체");
        allRows.forEach(r -> items.add(r.getGroupName()));
        String prev = classroomFilter.getValue();
        classroomFilter.setItems(items);
        if (prev != null && items.contains(prev)) classroomFilter.setValue(prev);
        else classroomFilter.getSelectionModel().selectFirst();
    }

    private void showDetail(ClassroomSummaryDto c) {
        selectedClassroom = c;
        detailTitleLabel.setText(c.getGroupName());
        detailUpdatedLabel.setText(LocalDateTime.now().format(UPDATED_FMT) + " 갱신됨");
        slideIn();
        loadPcList(c.getGroupId());
    }

    private void loadPcList(Long groupId) {
        apiClient.getPagedList("/dashboard/classrooms/" + groupId + "/pcs",
                        new TypeReference<List<PcStatusDto>>() {})
                .thenAccept(pcs -> Platform.runLater(() -> {
                    // 응답 도착 시점에 여전히 같은 강의실이 선택돼 있을 때만 반영
                    // (빠르게 다른 행 클릭 시 이전 응답이 새 강의실 패널을 덮어쓰는 race 방지)
                    if (selectedClassroom == null || !groupId.equals(selectedClassroom.getGroupId())) {
                        return;
                    }
                    detailPcRows.setAll(pcs);
                    updateDetailCounters(pcs);
                    if (detailGridMode) renderDetailGrid(pcs);
                }))
                .exceptionally(ex -> {
                    log.warn("PC 목록 로드 실패: {}", ex.getMessage());
                    return null;
                });
    }

    /**
     * PC 목록에서 카운트 집계.
     * 분류: ONLINE/IDLE = 온라인, BROKEN/FAILED = 경고, 그 외(OFFLINE/null/미지정) = 오프라인.
     */
    private void updateDetailCounters(List<PcStatusDto> pcs) {
        int online = 0, warn = 0, offline = 0;
        for (PcStatusDto pc : pcs) {
            String s = pc.getStatus() == null ? "" : pc.getStatus().toUpperCase();
            switch (s) {
                case "ONLINE", "IDLE" -> online++;
                case "BROKEN", "FAILED" -> warn++;
                case "OFFLINE", "" -> offline++;
                default -> {
                    offline++;
                    log.debug("미지정 PC 상태값 '{}' → 오프라인 분류", s);
                }
            }
        }
        detailOnlineCount.setText(String.valueOf(online));
        detailWarnCount.setText(String.valueOf(warn));
        detailOfflineCount.setText(String.valueOf(offline));
    }

    private void sendGroupCommand(String type) {
        if (selectedClassroom == null) return;
        String label = switch (type) {
            case "WOL" -> "전체 켜기";
            case "SHUTDOWN" -> "전체 종료";
            default -> type;
        };
        String roomName = selectedClassroom.getGroupName();
        apiClient.postJson("/commands", java.util.Map.of(
                "scope", "GROUP",
                "groupId", selectedClassroom.getGroupId(),
                "type", type,
                "role", "ALL"))
                .thenRun(() -> Platform.runLater(() ->
                        toast(roomName + " " + label + " 명령을 전송했습니다", false)))
                .exceptionally(ex -> {
                    Platform.runLater(() ->
                            toast(roomName + " " + label + " 명령 실패: " + shortMsg(ex), true));
                    log.warn("그룹 명령 실패 ({}): {}", type, ex.getMessage());
                    return null;
                });
    }

    private void handleAddClassroom() {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("강의실 추가");
        dialog.setHeaderText("새 강의실 이름을 입력하세요.");
        dialog.showAndWait().ifPresent(name -> {
            if (name == null || name.isBlank()) return;
            apiClient.postJson("/groups", java.util.Map.of("name", name))
                    .thenRun(() -> Platform.runLater(() -> {
                        toast("강의실 \"" + name + "\" 추가됨", false);
                        refresh();
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() ->
                                toast("강의실 추가 실패: " + shortMsg(ex), true));
                        log.warn("강의실 추가 실패: {}", ex.getMessage());
                        return null;
                    });
        });
    }

    /** 토스트 메시지 전달 — MainController에 위임. */
    private void toast(String message, boolean error) {
        com.example.frontend.controller.MainController mc =
                com.example.frontend.controller.MainController.getInstance();
        if (mc != null) mc.showToast(message, error);
    }

    private static String shortMsg(Throwable ex) {
        String m = ex.getMessage();
        return m == null ? ex.getClass().getSimpleName() : m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Detail panel slide
    // ─────────────────────────────────────────────────────────────────────

    private void slideIn() {
        if (panelAnim != null) panelAnim.stop();
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
        panelAnim = new Timeline(
                new KeyFrame(Duration.millis(220),
                        new KeyValue(detailPanel.prefWidthProperty(), DETAIL_PANEL_WIDTH),
                        new KeyValue(detailPanel.minWidthProperty(), DETAIL_PANEL_WIDTH)));
        panelAnim.play();
    }

    private void hideDetail() {
        if (panelAnim != null) panelAnim.stop();
        selectedClassroom = null;
        panelAnim = new Timeline(
                new KeyFrame(Duration.millis(180),
                        new KeyValue(detailPanel.prefWidthProperty(), 0),
                        new KeyValue(detailPanel.minWidthProperty(), 0)));
        panelAnim.setOnFinished(e -> {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
        });
        panelAnim.play();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String deriveStatusLabel(ClassroomSummaryDto r) {
        boolean running = r.getRunningCommandCount() > 0;
        if (r.getTotalPcs() <= 0) return running ? "명령 실행중" : "오프라인";
        boolean teacherOnline = isOnlineState(r.getTeacherPcStatus());
        int studentOnline = Math.max(0, r.getOnlinePcs() - (teacherOnline ? 1 : 0));
        boolean warned = !teacherOnline && studentOnline > 0;
        if (running && warned) return "경고, 명령 실행중";
        if (running) return "명령 실행중";
        if (warned) return "경고";
        if (teacherOnline) return "온라인";
        return "오프라인";
    }

    private static String statusToClass(String label) {
        return switch (label) {
            case "온라인" -> "ok";
            case "명령 실행중" -> "running";
            case "경고" -> "warn";
            case "오프라인" -> "offline";
            default -> "ok";
        };
    }

    private static boolean isOnlineState(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toUpperCase();
        return "ONLINE".equals(normalized) || "IDLE".equals(normalized);
    }
}
