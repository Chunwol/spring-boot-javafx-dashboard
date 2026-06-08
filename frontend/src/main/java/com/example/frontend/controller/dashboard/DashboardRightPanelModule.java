package com.example.frontend.controller.dashboard;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.function.Consumer;

/**
 * Figma 스타일 우측 편집 패널.
 * <p>
 * 편집 모드 진입 시 캔버스 우측에 표시되며 다음을 제공한다:
 * <ul>
 *   <li>Flow Direction - 자유 / 행 / 열 / 격자</li>
 *   <li>Grid - 열 개수</li>
 *   <li>Gap - 가로/세로 간격</li>
 *   <li>Padding - 상/우/하/좌 여백</li>
 *   <li>Actions - 자동 정렬 / 겹침 해소</li>
 * </ul>
 */
public class DashboardRightPanelModule {

    private final VBox panel;
    private final DashboardGridSettings settings = new DashboardGridSettings();

    // Grid section (columns spinner) — shown only for ROW/GRID
    private VBox gridSection;
    private Spinner<Integer> columnsSpinner;

    // Selected node info — editable fields
    private TextField selXField, selYField, selWField, selHField;

    // Context section (shown when a panel is selected)
    private VBox contextSection;
    private Label contextTypeLabel;
    private Button contextDeleteBtn;
    private VBox textControlsSection;
    private VBox classroomControlsSection;
    private VBox zOrderSection;
    private VBox alignSection;
    private Spinner<Integer> textSizeSpinner;
    private ToggleButton boldToggle;
    private ToggleButton italicToggle;
    private ColorPicker textColorPicker;
    private Runnable currentDeleteAction;
    private Runnable currentGridSettingsAction;
    private javafx.beans.value.ChangeListener<Integer> textSizeListener;

    /** 우측 패널이 컨트롤러로 디스패치하는 이벤트들. 콜백 fan-out 대신 단일 컨텍스트로 묶음. */
    public interface RightPanelContext {
        /** 사용자가 X/Y/W/H 입력 후 Enter — vals는 [x, y, w, h] */
        void onPositionSizeChanged(double[] vals);
        /** "맨 앞으로" / "맨 뒤로" — toFront=true면 앞 */
        void onZOrderChanged(boolean toFront);
        /** "LEFT" / "CENTER_H" / "RIGHT" / "TOP" / "CENTER_V" / "BOTTOM" / "DIST_H" / "DIST_V" */
        void onAlignmentRequest(String action);
    }

    private RightPanelContext context;

    public void setContext(RightPanelContext context) { this.context = context; }

    public DashboardRightPanelModule(
            Consumer<DashboardGridSettings> onAutoArrange,
            Consumer<DashboardGridSettings> onResolveOverlap) {
        this.panel = buildPanel(onAutoArrange, onResolveOverlap);
    }

    public VBox getPanel() {
        return panel;
    }

    public DashboardGridSettings getSettings() {
        return settings;
    }

    @Deprecated
    public void updateSelectedInfo(double x, double y, double w, double h) {
        updateSelInfo(x, y, w, h);
    }

    @Deprecated
    public void clearSelection() {
        showNoSelection();
    }

    // ─────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────

    private VBox buildPanel(
            Consumer<DashboardGridSettings> onAutoArrange,
            Consumer<DashboardGridSettings> onResolveOverlap) {

        VBox root = new VBox(0);
        root.getStyleClass().add("edit-right-panel");
        root.setPrefWidth(256);
        root.setMinWidth(200);
        root.setMaxWidth(320);

        // ── 타이틀
        Label title = new Label("디자인");
        title.getStyleClass().add("edit-panel-title");
        VBox.setMargin(title, new Insets(14, 14, 10, 14));

        root.getChildren().add(title);
        root.getChildren().add(separator());

        // ── 선택 컨텍스트 (패널 선택 시 표시)
        contextSection = buildContextSection();
        contextSection.setVisible(false);
        contextSection.setManaged(false);
        root.getChildren().add(contextSection);

        // ── 레이아웃 (Flow Direction)
        root.getChildren().add(buildLayoutSection());
        root.getChildren().add(separator());

        // ── 그리드 (열 개수 — GRID/ROW 모드에서 표시)
        gridSection = buildGridSection();
        gridSection.setVisible(false);
        gridSection.setManaged(false);
        root.getChildren().add(gridSection);
        root.getChildren().add(separator());

        // ── 간격 (Gap)
        root.getChildren().add(buildGapSection());
        root.getChildren().add(separator());

        // ── 여백 (Padding)
        root.getChildren().add(buildPaddingSection());
        root.getChildren().add(separator());

        // ── 액션
        root.getChildren().add(buildActionsSection(onAutoArrange, onResolveOverlap));

        return root;
    }

    // ── Context section (selected panel) ────────────────────────

    private VBox buildContextSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 12, 14));

        contextTypeLabel = sectionHeader("선택 없음");

        // X/Y/W/H 직접 입력 가능한 TextField
        selXField = posSizeField();
        selYField = posSizeField();
        selWField = posSizeField();
        selHField = posSizeField();
        selXField.setOnAction(e -> commitPositionSize());
        selYField.setOnAction(e -> commitPositionSize());
        selWField.setOnAction(e -> commitPositionSize());
        selHField.setOnAction(e -> commitPositionSize());

        HBox posRow = new HBox(10);
        posRow.getChildren().addAll(fieldBox("X", selXField), fieldBox("Y", selYField));
        HBox sizeRow = new HBox(10);
        sizeRow.getChildren().addAll(fieldBox("W", selWField), fieldBox("H", selHField));

        contextDeleteBtn = new Button("삭제");
        contextDeleteBtn.getStyleClass().addAll("btn-danger", "edit-panel-action-btn");
        contextDeleteBtn.setMaxWidth(Double.MAX_VALUE);
        contextDeleteBtn.setOnAction(e -> { if (currentDeleteAction != null) currentDeleteAction.run(); });

        // 텍스트 컨트롤
        textControlsSection = buildTextControlsSection();
        textControlsSection.setVisible(false);
        textControlsSection.setManaged(false);

        // 강의실 컨트롤
        classroomControlsSection = buildClassroomControlsSection();
        classroomControlsSection.setVisible(false);
        classroomControlsSection.setManaged(false);

        // Z-순서 컨트롤 (단일 선택)
        zOrderSection = buildZOrderSection();
        zOrderSection.setVisible(false);
        zOrderSection.setManaged(false);

        // 정렬 도구 (다중 선택)
        alignSection = buildAlignSection();
        alignSection.setVisible(false);
        alignSection.setManaged(false);

        section.getChildren().addAll(contextTypeLabel, posRow, sizeRow,
                textControlsSection, classroomControlsSection,
                zOrderSection, alignSection, contextDeleteBtn);
        return section;
    }

    private VBox buildZOrderSection() {
        VBox section = new VBox(6);
        Button frontBtn = new Button("맨 앞으로");
        frontBtn.getStyleClass().addAll("btn-neutral", "edit-panel-action-btn");
        frontBtn.setMaxWidth(Double.MAX_VALUE);
        frontBtn.setOnAction(e -> { if (context != null) context.onZOrderChanged(true); });
        Button backBtn = new Button("맨 뒤로");
        backBtn.getStyleClass().addAll("btn-neutral", "edit-panel-action-btn");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setOnAction(e -> { if (context != null) context.onZOrderChanged(false); });
        section.getChildren().addAll(frontBtn, backBtn);
        return section;
    }

    private VBox buildAlignSection() {
        VBox section = new VBox(6);
        section.getChildren().add(sectionHeader("정렬"));
        // 가로
        HBox hRow = new HBox(4,
                alignBtn("⫷", "LEFT",     "왼쪽 정렬"),
                alignBtn("≡",  "CENTER_H", "가로 중앙 정렬"),
                alignBtn("⫸", "RIGHT",    "오른쪽 정렬"));
        // 세로
        HBox vRow = new HBox(4,
                alignBtn("⫶", "TOP",      "위쪽 정렬"),
                alignBtn("⇔",  "CENTER_V", "세로 중앙 정렬"),
                alignBtn("⫶", "BOTTOM",   "아래쪽 정렬"));
        // 분배
        HBox dRow = new HBox(4,
                alignBtn("⇿", "DIST_H", "가로 균등 분배"),
                alignBtn("⇕", "DIST_V", "세로 균등 분배"));
        section.getChildren().addAll(hRow, vRow, dRow);
        return section;
    }

    private Button alignBtn(String text, String action, String tip) {
        Button b = new Button(text);
        b.getStyleClass().add("edit-panel-flow-btn");
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> { if (context != null) context.onAlignmentRequest(action); });
        return b;
    }

    private static TextField posSizeField() {
        TextField tf = new TextField();
        tf.setPrefWidth(64);
        tf.getStyleClass().add("edit-panel-input");
        return tf;
    }

    private static HBox fieldBox(String labelText, TextField field) {
        Label l = fieldLabel(labelText);
        l.setMinWidth(14);
        HBox box = new HBox(4, l, field);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private void commitPositionSize() {
        if (context == null) return;
        try {
            double x = Double.parseDouble(selXField.getText().trim());
            double y = Double.parseDouble(selYField.getText().trim());
            double w = Double.parseDouble(selWField.getText().trim());
            double h = Double.parseDouble(selHField.getText().trim());
            context.onPositionSizeChanged(new double[]{x, y, w, h});
        } catch (NumberFormatException ignored) {}
        // Enter 후 포커스 해제 → 다음 updateSelInfo가 정상 반영되도록
        if (panel != null) panel.requestFocus();
    }

    private VBox buildTextControlsSection() {
        VBox section = new VBox(8);
        boldToggle = new ToggleButton("B");
        boldToggle.getStyleClass().add("edit-panel-flow-btn");
        italicToggle = new ToggleButton("I");
        italicToggle.getStyleClass().add("edit-panel-flow-btn");
        textSizeSpinner = intSpinner(8, 72, 14);
        textColorPicker = new ColorPicker(Color.BLACK);
        textColorPicker.setPrefWidth(72);
        textColorPicker.getStyleClass().add("edit-panel-spinner");
        HBox styleRow = new HBox(6, boldToggle, italicToggle);
        styleRow.setAlignment(Pos.CENTER_LEFT);
        HBox sizeRow = new HBox(6, fieldLabel("크기"), textSizeSpinner, textColorPicker);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().addAll(styleRow, sizeRow);
        return section;
    }

    private VBox buildClassroomControlsSection() {
        VBox section = new VBox(8);
        Button gridBtn = new Button("그리드 설정");
        gridBtn.getStyleClass().addAll("btn-neutral", "edit-panel-action-btn");
        gridBtn.setMaxWidth(Double.MAX_VALUE);
        gridBtn.setOnAction(e -> { if (currentGridSettingsAction != null) currentGridSettingsAction.run(); });
        section.getChildren().add(gridBtn);
        return section;
    }

    // ── Public context show methods ──────────────────────────────

    public void showNoSelection() {
        contextSection.setVisible(false);
        contextSection.setManaged(false);
        clearContextTypeControls();
        toggleZOrder(false);
        toggleAlign(false);
    }

    private void toggleZOrder(boolean show) {
        if (zOrderSection != null) { zOrderSection.setVisible(show); zOrderSection.setManaged(show); }
    }
    private void toggleAlign(boolean show) {
        if (alignSection != null) { alignSection.setVisible(show); alignSection.setManaged(show); }
    }

    public void showSingleClassroom(double x, double y, double w, double h,
                                     Runnable onDelete, Runnable onGridSettings) {
        currentDeleteAction = onDelete;
        currentGridSettingsAction = onGridSettings;
        contextTypeLabel.setText("강의실 패널");
        updateSelInfo(x, y, w, h);
        textControlsSection.setVisible(false);
        textControlsSection.setManaged(false);
        classroomControlsSection.setVisible(true);
        classroomControlsSection.setManaged(true);
        toggleZOrder(true);
        toggleAlign(false);
        contextSection.setVisible(true);
        contextSection.setManaged(true);
    }

    public void showSingleText(double x, double y, double w, double h,
                                Runnable onDelete,
                                boolean bold, boolean italic, int fontSize, String fontColor,
                                Runnable onStyleChange) {
        currentDeleteAction = onDelete;
        currentGridSettingsAction = null;
        contextTypeLabel.setText("텍스트 패널");
        updateSelInfo(x, y, w, h);
        textControlsSection.setVisible(true);
        textControlsSection.setManaged(true);
        classroomControlsSection.setVisible(false);
        classroomControlsSection.setManaged(false);
        // 리스너 제거 후 재설정
        boldToggle.setSelected(bold);
        italicToggle.setSelected(italic);
        textSizeSpinner.getValueFactory().setValue(fontSize);
        try { textColorPicker.setValue(Color.web(fontColor)); } catch (Exception ignored) {}
        // setOnAction replaces previous handler — no accumulation
        boldToggle.setOnAction(e -> { if (onStyleChange != null) onStyleChange.run(); });
        italicToggle.setOnAction(e -> { if (onStyleChange != null) onStyleChange.run(); });
        textColorPicker.setOnAction(e -> { if (onStyleChange != null) onStyleChange.run(); });
        // valueProperty listener must be replaced explicitly to avoid accumulation
        if (textSizeListener != null) textSizeSpinner.valueProperty().removeListener(textSizeListener);
        textSizeListener = (obs, ov, nv) -> { if (onStyleChange != null) onStyleChange.run(); };
        textSizeSpinner.valueProperty().addListener(textSizeListener);
        toggleZOrder(true);
        toggleAlign(false);
        contextSection.setVisible(true);
        contextSection.setManaged(true);
    }

    public void showSingleImage(double x, double y, double w, double h, Runnable onDelete) {
        showSimplePanel("이미지 패널", x, y, w, h, onDelete);
    }

    /** 단순 패널 (네트워크/연결로그/운영지표/이미지 등): 위치 + 삭제만 표시 */
    public void showSimplePanel(String typeLabel, double x, double y, double w, double h, Runnable onDelete) {
        currentDeleteAction = onDelete;
        currentGridSettingsAction = null;
        contextTypeLabel.setText(typeLabel);
        updateSelInfo(x, y, w, h);
        textControlsSection.setVisible(false);
        textControlsSection.setManaged(false);
        classroomControlsSection.setVisible(false);
        classroomControlsSection.setManaged(false);
        toggleZOrder(true);
        toggleAlign(false);
        contextSection.setVisible(true);
        contextSection.setManaged(true);
    }

    public void showMultiSelection(int count) {
        currentDeleteAction = null;
        currentGridSettingsAction = null;
        contextTypeLabel.setText(count + "개 선택됨");
        if (selXField != null) { selXField.setText("—"); selXField.setDisable(true); }
        if (selYField != null) { selYField.setText("—"); selYField.setDisable(true); }
        if (selWField != null) { selWField.setText("—"); selWField.setDisable(true); }
        if (selHField != null) { selHField.setText("—"); selHField.setDisable(true); }
        textControlsSection.setVisible(false);
        textControlsSection.setManaged(false);
        classroomControlsSection.setVisible(false);
        classroomControlsSection.setManaged(false);
        toggleZOrder(false);
        toggleAlign(true);
        contextSection.setVisible(true);
        contextSection.setManaged(true);
    }

    public boolean isBoldSelected() { return boldToggle != null && boldToggle.isSelected(); }
    public boolean isItalicSelected() { return italicToggle != null && italicToggle.isSelected(); }
    public int getTextFontSize() { return textSizeSpinner != null ? textSizeSpinner.getValue() : 14; }
    public String getTextColorHex() {
        if (textColorPicker == null) return "#111111";
        Color c = textColorPicker.getValue();
        return String.format("#%02x%02x%02x",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    private void updateSelInfo(double x, double y, double w, double h) {
        if (selXField != null && !selXField.isFocused()) selXField.setText(String.format("%.0f", x));
        if (selYField != null && !selYField.isFocused()) selYField.setText(String.format("%.0f", y));
        if (selWField != null && !selWField.isFocused()) selWField.setText(String.format("%.0f", w));
        if (selHField != null && !selHField.isFocused()) selHField.setText(String.format("%.0f", h));
        // 단일 선택 시 입력 가능, 다중 선택 시 비활성
        boolean enabled = w > 0 && h > 0;
        if (selXField != null) selXField.setDisable(!enabled);
        if (selYField != null) selYField.setDisable(!enabled);
        if (selWField != null) selWField.setDisable(!enabled);
        if (selHField != null) selHField.setDisable(!enabled);
    }

    private void clearContextTypeControls() {
        currentDeleteAction = null;
        currentGridSettingsAction = null;
        if (textControlsSection != null) { textControlsSection.setVisible(false); textControlsSection.setManaged(false); }
        if (classroomControlsSection != null) { classroomControlsSection.setVisible(false); classroomControlsSection.setManaged(false); }
    }

    // ── Layout section ──────────────────────────────────────────

    private VBox buildLayoutSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 12, 14));

        Label header = sectionHeader("레이아웃");

        ToggleGroup group = new ToggleGroup();
        HBox dirRow = new HBox(4);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        for (DashboardGridSettings.FlowDirection dir : DashboardGridSettings.FlowDirection.values()) {
            ToggleButton btn = new ToggleButton(dir.getLabel());
            btn.getStyleClass().add("edit-panel-flow-btn");
            btn.setToggleGroup(group);
            btn.setUserData(dir);
            if (dir == DashboardGridSettings.FlowDirection.FREE) {
                btn.setSelected(true);
            }
            dirRow.getChildren().add(btn);
        }

        group.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) {
                if (oldT != null) oldT.setSelected(true);
                return;
            }
            DashboardGridSettings.FlowDirection dir =
                    (DashboardGridSettings.FlowDirection) newT.getUserData();
            settings.setDirection(dir);
            boolean showGrid = dir == DashboardGridSettings.FlowDirection.GRID
                    || dir == DashboardGridSettings.FlowDirection.ROW;
            if (gridSection != null) {
                gridSection.setVisible(showGrid);
                gridSection.setManaged(showGrid);
            }
        });

        section.getChildren().addAll(header, dirRow);
        return section;
    }

    // ── Grid section ─────────────────────────────────────────────

    private VBox buildGridSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 12, 14));

        Label header = sectionHeader("그리드");

        HBox colRow = new HBox(8);
        colRow.setAlignment(Pos.CENTER_LEFT);
        Label colLabel = fieldLabel("열 개수");
        columnsSpinner = intSpinner(1, 20, settings.getColumns());
        columnsSpinner.valueProperty().addListener((obs, ov, nv) -> settings.setColumns(nv));
        HBox.setHgrow(columnsSpinner, Priority.ALWAYS);
        colRow.getChildren().addAll(colLabel, spacer(), columnsSpinner);

        section.getChildren().addAll(header, colRow);
        return section;
    }

    // ── Gap section ──────────────────────────────────────────────

    private VBox buildGapSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 12, 14));

        Label header = sectionHeader("간격");

        Spinner<Double> hGapSpinner = doubleSpinner(0, 200, settings.getHGap());
        hGapSpinner.valueProperty().addListener((obs, ov, nv) -> settings.setHGap(nv));

        Spinner<Double> vGapSpinner = doubleSpinner(0, 200, settings.getVGap());
        vGapSpinner.valueProperty().addListener((obs, ov, nv) -> settings.setVGap(nv));

        HBox gapRow = new HBox(10);
        gapRow.setAlignment(Pos.CENTER_LEFT);
        gapRow.getChildren().addAll(
                labeledSpinner("H", hGapSpinner),
                labeledSpinner("V", vGapSpinner)
        );

        section.getChildren().addAll(header, gapRow);
        return section;
    }

    // ── Padding section ──────────────────────────────────────────

    private VBox buildPaddingSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 12, 14));

        Label header = sectionHeader("여백");

        Spinner<Double> topSp    = doubleSpinner(0, 200, settings.getPaddingTop());
        Spinner<Double> rightSp  = doubleSpinner(0, 200, settings.getPaddingRight());
        Spinner<Double> bottomSp = doubleSpinner(0, 200, settings.getPaddingBottom());
        Spinner<Double> leftSp   = doubleSpinner(0, 200, settings.getPaddingLeft());

        topSp.valueProperty().addListener((obs, ov, nv) -> settings.setPaddingTop(nv));
        rightSp.valueProperty().addListener((obs, ov, nv) -> settings.setPaddingRight(nv));
        bottomSp.valueProperty().addListener((obs, ov, nv) -> settings.setPaddingBottom(nv));
        leftSp.valueProperty().addListener((obs, ov, nv) -> settings.setPaddingLeft(nv));

        // T row
        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER);
        topRow.getChildren().add(labeledSpinner("↑", topSp));

        // LR row
        HBox lrRow = new HBox(10);
        lrRow.setAlignment(Pos.CENTER_LEFT);
        lrRow.getChildren().addAll(
                labeledSpinner("←", leftSp),
                labeledSpinner("→", rightSp)
        );

        // B row
        HBox botRow = new HBox(10);
        botRow.setAlignment(Pos.CENTER);
        botRow.getChildren().add(labeledSpinner("↓", bottomSp));

        section.getChildren().addAll(header, topRow, lrRow, botRow);
        return section;
    }

    // ── Actions section ──────────────────────────────────────────

    private VBox buildActionsSection(
            Consumer<DashboardGridSettings> onAutoArrange,
            Consumer<DashboardGridSettings> onResolveOverlap) {

        VBox section = new VBox(8);
        section.getStyleClass().add("edit-panel-section");
        section.setPadding(new Insets(12, 14, 14, 14));

        Label header = sectionHeader("작업");

        Button arrangeBtn = new Button("자동 정렬");
        arrangeBtn.getStyleClass().addAll("btn-primary", "edit-panel-action-btn");
        arrangeBtn.setMaxWidth(Double.MAX_VALUE);
        arrangeBtn.setTooltip(new Tooltip(
                "방향/열/간격/여백 설정에 따라 모든 패널을 자동으로 배치합니다."));
        arrangeBtn.setOnAction(e -> {
            if (onAutoArrange != null) onAutoArrange.accept(settings);
        });

        Button resolveBtn = new Button("겹침 해소");
        resolveBtn.getStyleClass().addAll("btn-neutral", "edit-panel-action-btn");
        resolveBtn.setMaxWidth(Double.MAX_VALUE);
        resolveBtn.setTooltip(new Tooltip(
                "서로 겹치는 패널을 감지하여 겹치지 않도록 위치를 조정합니다."));
        resolveBtn.setOnAction(e -> {
            if (onResolveOverlap != null) onResolveOverlap.accept(settings);
        });

        section.getChildren().addAll(header, arrangeBtn, resolveBtn);
        return section;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static Separator separator() {
        Separator sep = new Separator();
        sep.getStyleClass().add("edit-panel-separator");
        return sep;
    }

    private static Label sectionHeader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("edit-panel-section-header");
        return l;
    }

    private static Label fieldLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("edit-panel-field-label");
        return l;
    }

    private static HBox labeledSpinner(String labelText, Node spinner) {
        Label l = fieldLabel(labelText);
        l.setMinWidth(16);
        HBox box = new HBox(6, l, spinner);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox.setHgrow(spinner, Priority.ALWAYS);
        return box;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> sp = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, initial));
        sp.setEditable(true);
        sp.setPrefWidth(72);
        sp.getStyleClass().add("edit-panel-spinner");
        return sp;
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double initial) {
        Spinner<Double> sp = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, initial, 4.0));
        sp.setEditable(true);
        sp.setPrefWidth(72);
        sp.getStyleClass().add("edit-panel-spinner");
        return sp;
    }
}
