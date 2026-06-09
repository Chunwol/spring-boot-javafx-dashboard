package com.example.frontend.controller.panel;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.frontend.service.ApiClient;
import com.example.frontend.service.DashboardService;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 네트워크 탭 — 라인 차트(시계열) + 강의실별 트래픽 표 + 우측 슬라이드 PC 상세.
 *
 * <p>이전 카드 그리드(반원 게이지)를 모두 표로 교체. 강의실 행 클릭 시 우측에
 * PC별 트래픽 상세 슬라이드 패널이 열림 — 대시보드 패턴과 동일.</p>
 */
public class NetworkTabController {

    private static final Logger log = LoggerFactory.getLogger(NetworkTabController.class);
    private static final DateTimeFormatter UPDATED_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final DateTimeFormatter CHART_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final double DETAIL_PANEL_WIDTH = 380.0;
    private static final int MAX_CHART_POINTS = 30;

    @FXML private AreaChart<String, Number> trafficChart;
    @FXML private CategoryAxis timeAxis;
    @FXML private NumberAxis  valueAxis;
    @FXML private TableView<Map<String, Object>> classroomTable;
    @FXML private TableColumn<Map<String, Object>, String> roomCol;
    @FXML private TableColumn<Map<String, Object>, String> pcCountCol;
    @FXML private TableColumn<Map<String, Object>, String> inCol;
    @FXML private TableColumn<Map<String, Object>, String> outCol;
    @FXML private TableColumn<Map<String, Object>, Double> loadCol;
    @FXML private TableColumn<Map<String, Object>, String> statusCol;
    @FXML private Label       updatedLabel;
    @FXML private Button      fullscreenBtn;
    @FXML private Button      refreshBtn;
    @FXML private Button      downloadBtn;
    @FXML private Button      printBtn;
    @FXML private ComboBox<String> statusFilter;

    @FXML private VBox      detailPanel;
    @FXML private Label     detailTitleLabel;
    @FXML private Button    detailCloseBtn;
    @FXML private Label     detailInLabel;
    @FXML private Label     detailOutLabel;
    @FXML private StackPane detailTableSlot;

    private XYChart.Series<String, Number> inSeries;
    private XYChart.Series<String, Number> outSeries;
    private TableView<Map<String, Object>> detailPcTable;

    private final ApiClient apiClient = ApiClient.getInstance();
    private final DashboardService dashboardService = new DashboardService();

    /** groupId → 강의실 행 (인/아웃 누적). */
    private final Map<Long, Map<String, Object>> rowByGroup = new LinkedHashMap<>();

    private Timeline autoRefresh;
    private Timeline panelAnim;
    private Long selectedGroupId;
    private double classroomThreshold = 1000.0;
    private double pcThreshold = 300.0;

    @FXML
    public void initialize() {
        inSeries = new XYChart.Series<>(); inSeries.setName("인바운드");
        outSeries = new XYChart.Series<>(); outSeries.setName("아웃바운드");
        trafficChart.getData().addAll(inSeries, outSeries);
        valueAxis.setLabel("Mbps");

        // 강의실 표 컬럼 바인딩
        roomCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("groupName"))));
        pcCountCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getOrDefault("totalPcs", 0) + " / " + d.getValue().getOrDefault("onlinePcs", 0)));
        inCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("inboundMbps"))));
        outCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("outboundMbps"))));

        loadCol.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(
                (toDouble(d.getValue().get("inboundMbps")) + toDouble(d.getValue().get("outboundMbps"))) / classroomThreshold));
        loadCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private final Label pct = new Label();
            private final HBox box = new HBox(6, bar, pct);
            {
                bar.setPrefWidth(100);
                pct.setMinWidth(50);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setGraphic(null); return; }
                double v = Math.max(0.0, value);
                bar.setProgress(Math.min(1.0, v));
                bar.getStyleClass().removeAll("network-progress-normal", "network-progress-warning");
                bar.getStyleClass().add(v > 1.0 ? "network-progress-warning" : "network-progress-normal");
                pct.setText(String.format("%.1f%%", v * 100));
                setGraphic(box);
            }
        });

        statusCol.setCellValueFactory(d -> new SimpleStringProperty(roomStatus(d.getValue())));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); getStyleClass().removeAll("status-online", "status-offline"); return; }
                setText(item);
                getStyleClass().removeAll("status-online", "status-offline");
                getStyleClass().add("정상".equals(item) ? "status-online" : "status-offline");
            }
        });

        // 행 클릭 → 우측 상세
        classroomTable.setRowFactory(tv -> {
            TableRow<Map<String, Object>> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (!row.isEmpty() && ev.getButton() == MouseButton.PRIMARY) {
                    Map<String, Object> r = row.getItem();
                    Object gid = r.get("groupId");
                    if (gid instanceof Number n) {
                        showDetail(n.longValue(), str(r.get("groupName")));
                    }
                }
            });
            return row;
        });

        // 상세 패널 PC 표
        detailPcTable = createPcTrafficTable();
        detailTableSlot.getChildren().setAll(detailPcTable);

        // 상태 필터
        statusFilter.setItems(FXCollections.observableArrayList("전체", "정상", "이상"));
        statusFilter.setValue("전체");
        statusFilter.valueProperty().addListener((o, v, n) -> applyFilter());

        if (fullscreenBtn != null) fullscreenBtn.setOnAction(e -> toggleFullscreen());
        refreshBtn.setOnAction(e -> refresh());
        detailCloseBtn.setOnAction(e -> hideDetail());
        if (downloadBtn != null) downloadBtn.setOnAction(e -> exportToCsv());
        if (printBtn != null)    printBtn.setOnAction(e -> printView());

        applyThresholds();
        refresh();
    }

    private boolean fullscreen = false;

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        com.example.frontend.controller.MainController mc =
                com.example.frontend.controller.MainController.getInstance();
        if (mc != null) mc.setChromeHidden(fullscreen);
        javafx.stage.Stage stage =
                (javafx.stage.Stage) classroomTable.getScene().getWindow();
        stage.setFullScreen(fullscreen);
        var icon = (org.kordamp.ikonli.javafx.FontIcon) fullscreenBtn.getGraphic();
        icon.setIconLiteral(fullscreen ? "mdi2f-fullscreen-exit" : "mdi2f-fullscreen");
    }

    public void startAutoRefresh(int intervalSec) {
        stopAutoRefresh();
        autoRefresh = new Timeline(new KeyFrame(
                Duration.seconds(Math.max(5, intervalSec)), e -> refresh()));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
    }

    private void stopAutoRefresh() {
        if (autoRefresh != null) { autoRefresh.stop(); autoRefresh = null; }
    }

    public void shutdown() {
        stopAutoRefresh();
        if (panelAnim != null) { panelAnim.stop(); panelAnim = null; }
    }

    public void refreshData() {
        refresh();
    }

    // ── 데이터 갱신 ────────────────────────────────────────────────────────

    private void refresh() {
        LocalDateTime now = LocalDateTime.now();
        String chartTime = now.format(CHART_TIME_FMT);
        updatedLabel.setText(now.format(UPDATED_FMT) + " 갱신됨");

        // 1) 전체 트래픽 → 차트 포인트
        dashboardService.fetchNetworkTrafficAsync((in, out) -> Platform.runLater(() ->
                appendChartPoint(chartTime, in, out)));

        // 2) 강의실 목록 → 표 행 동기화 + 강의실별 PC 트래픽 합산
        apiClient.getPagedList("/dashboard/classrooms", new TypeReference<List<ClassroomSummaryDto>>() {})
                .thenAccept(list -> Platform.runLater(() -> {
                    syncRows(list);
                    for (ClassroomSummaryDto c : list) {
                        if (c.getGroupId() != null) loadClassroomTraffic(c.getGroupId(), c.getGroupName());
                    }
                }))
                .exceptionally(ex -> { log.warn("강의실 목록 조회 실패: {}", ex.getMessage()); return null; });
    }

    private void appendChartPoint(String time, double in, double out) {
        inSeries.getData().add(new XYChart.Data<>(time, in));
        outSeries.getData().add(new XYChart.Data<>(time, out));
        while (inSeries.getData().size() > MAX_CHART_POINTS)  inSeries.getData().remove(0);
        while (outSeries.getData().size() > MAX_CHART_POINTS) outSeries.getData().remove(0);
    }

    private void syncRows(List<ClassroomSummaryDto> list) {
        // 삭제된 강의실 정리
        rowByGroup.keySet().removeIf(id -> list.stream().noneMatch(c -> id.equals(c.getGroupId())));
        // 추가/유지
        for (ClassroomSummaryDto c : list) {
            if (c.getGroupId() == null) continue;
            Map<String, Object> row = rowByGroup.computeIfAbsent(c.getGroupId(), id -> new HashMap<>());
            row.put("groupId",     c.getGroupId());
            row.put("groupName",   c.getGroupName());
            row.put("totalPcs",    c.getTotalPcs());
            row.put("onlinePcs",   c.getOnlinePcs());
            row.putIfAbsent("inboundMbps", 0.0);
            row.putIfAbsent("outboundMbps", 0.0);
        }
        applyFilter();
    }

    private void loadClassroomTraffic(Long groupId, String groupName) {
        dashboardService.fetchClassroomPcTrafficAsync(groupId, rows -> Platform.runLater(() -> {
            if (rows == null) return;
            double in  = sum(rows, "inboundMbps");
            double out = sum(rows, "outboundMbps");

            Map<String, Object> r = rowByGroup.get(groupId);
            if (r != null) {
                r.put("inboundMbps",  in);
                r.put("outboundMbps", out);
            }
            applyFilter();

            // 상세 패널 열려있고 같은 강의실이면 갱신
            if (groupId.equals(selectedGroupId)) {
                detailInLabel.setText(formatMbps(in)  + " Mbps");
                detailOutLabel.setText(formatMbps(out) + " Mbps");
                detailPcTable.getItems().setAll(rows);
            }
        }));
    }

    private void applyFilter() {
        String f = statusFilter.getValue();
        List<Map<String, Object>> filtered = new ArrayList<>(rowByGroup.values());
        if (f != null && !"전체".equals(f)) {
            filtered.removeIf(r -> !f.equals(roomStatus(r)));
        }
        classroomTable.getItems().setAll(filtered);
    }

    /** 강의실 상태 판정 — 부하율 80% 이상이면 이상, 아니면 정상. */
    private String roomStatus(Map<String, Object> r) {
        double load = (toDouble(r.get("inboundMbps")) + toDouble(r.get("outboundMbps"))) / classroomThreshold;
        return load > 0.8 ? "이상" : "정상";
    }

    private static double sum(List<Map<String, Object>> rows, String key) {
        double total = 0;
        if (rows != null) for (Map<String, Object> r : rows) total += toDouble(r.get(key));
        return total;
    }

    // ── 상세 패널 ──────────────────────────────────────────────────────────

    private void showDetail(Long groupId, String name) {
        selectedGroupId = groupId;
        detailTitleLabel.setText(name != null ? name : "강의실");
        detailInLabel.setText("0.0 Mbps");
        detailOutLabel.setText("0.0 Mbps");
        detailPcTable.getItems().clear();
        slideIn();
        loadClassroomTraffic(groupId, name);
    }

    private void slideIn() {
        if (panelAnim != null) panelAnim.stop();
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
        panelAnim = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(detailPanel.prefWidthProperty(), DETAIL_PANEL_WIDTH),
                new KeyValue(detailPanel.minWidthProperty(), DETAIL_PANEL_WIDTH)));
        panelAnim.play();
    }

    private void hideDetail() {
        if (panelAnim != null) panelAnim.stop();
        selectedGroupId = null;
        panelAnim = new Timeline(new KeyFrame(Duration.millis(180),
                new KeyValue(detailPanel.prefWidthProperty(), 0),
                new KeyValue(detailPanel.minWidthProperty(), 0)));
        panelAnim.setOnFinished(e -> {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
        });
        panelAnim.play();
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createPcTrafficTable() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/network-traffic-table.fxml"));
            TableView<Map<String, Object>> table = loader.load();
            var ns = loader.getNamespace();
            TableColumn<Map<String, Object>, String> pcCol  = (TableColumn<Map<String, Object>, String>) ns.get("pcCol");
            TableColumn<Map<String, Object>, String> ipCol  = (TableColumn<Map<String, Object>, String>) ns.get("ipCol");
            TableColumn<Map<String, Object>, String> pcInCol  = (TableColumn<Map<String, Object>, String>) ns.get("inCol");
            TableColumn<Map<String, Object>, String> pcOutCol = (TableColumn<Map<String, Object>, String>) ns.get("outCol");
            TableColumn<Map<String, Object>, Double> pcLoadCol = (TableColumn<Map<String, Object>, Double>) ns.get("loadCol");

            pcCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("pcName"))));
            ipCol.setCellValueFactory(d -> new SimpleStringProperty(str(d.getValue().get("ipAddress"))));
            pcInCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("inboundMbps"))));
            pcOutCol.setCellValueFactory(d -> new SimpleStringProperty(formatMbps(d.getValue().get("outboundMbps"))));

            pcLoadCol.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(
                    (toDouble(d.getValue().get("inboundMbps")) + toDouble(d.getValue().get("outboundMbps"))) / pcThreshold));
            pcLoadCol.setCellFactory(col -> new TableCell<>() {
                private final ProgressBar bar = new ProgressBar(0);
                { bar.setPrefWidth(70); setContentDisplay(ContentDisplay.GRAPHIC_ONLY); }
                @Override protected void updateItem(Double value, boolean empty) {
                    super.updateItem(value, empty);
                    if (empty || value == null) { setGraphic(null); return; }
                    double v = Math.max(0.0, value);
                    bar.setProgress(Math.min(1.0, v));
                    bar.getStyleClass().removeAll("network-progress-normal", "network-progress-warning");
                    bar.getStyleClass().add(v > 1.0 ? "network-progress-warning" : "network-progress-normal");
                    setGraphic(bar);
                }
            });
            return table;
        } catch (Exception e) {
            throw new IllegalStateException("PC 트래픽 표 로딩 실패", e);
        }
    }

    private void applyThresholds() {
        dashboardService.fetchTrafficThresholdsAsync(thresholds -> Platform.runLater(() -> {
            if (thresholds == null) return;
            Double classroom = thresholds.get("classroomMbps");
            Double pc = thresholds.get("pcMbps");
            if (classroom != null) classroomThreshold = classroom;
            if (pc != null) pcThreshold = pc;
        }));
    }

    private void exportToCsv() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("강의실 트래픽 저장");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
        fc.setInitialFileName("network_classroom_traffic.csv");
        java.io.File file = fc.showSaveDialog(classroomTable.getScene().getWindow());
        if (file == null) return;
        try (java.io.PrintWriter pw = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            pw.println("강의실,온라인PC,전체PC,인바운드(Mbps),아웃바운드(Mbps),부하율,상태");
            for (Map<String, Object> r : classroomTable.getItems()) {
                double load = (toDouble(r.get("inboundMbps")) + toDouble(r.get("outboundMbps"))) / classroomThreshold;
                pw.printf("%s,%s,%s,%s,%s,%.1f%%,%s%n",
                        str(r.get("groupName")), r.get("onlinePcs"), r.get("totalPcs"),
                        formatMbps(r.get("inboundMbps")), formatMbps(r.get("outboundMbps")),
                        load * 100, roomStatus(r));
            }
        } catch (Exception ex) {
            log.warn("CSV 내보내기 실패", ex);
        }
    }

    private void printView() {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job != null && job.showPrintDialog(classroomTable.getScene().getWindow())) {
            job.printPage(classroomTable.getParent());
            job.endJob();
        }
    }

    private static String str(Object v) { return v == null ? "-" : v.toString(); }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private static String formatMbps(Object v) {
        double d = toDouble(v);
        return d >= 100 ? String.format("%.0f", d) : String.format("%.2f", d);
    }
}
