package com.example.frontend.component;

import javafx.scene.layout.Pane;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;

/**
 * 반원형(speedometer) 게이지.
 *
 * <p>Pane + 절대좌표로 배치해 StackPane 중앙정렬 오차를 제거.
 * 호의 중심(cx, cy)을 위젯 하단부에 고정하고, 텍스트를 그 위에 올린다.</p>
 */
public class CircularGauge extends Pane {

    private final Arc progressArc;
    private final Label valueLabel;

    private double maxValue;
    private final Color normalColor;
    private double warnRatio = 1.0;
    private static final Color WARN_COLOR = Color.web("#ef4444");

    /**
     * @param caption     하단 캡션 (예: "인바운드")
     * @param maxValue    최대값
     * @param normalColor 정상 호 색상
     * @param unit        단위 (예: "Mbps"), 빈 문자열이면 캡션만 표시
     * @param radius      반원 반지름(px)
     */
    public CircularGauge(String caption, double maxValue, Color normalColor, String unit, double radius) {
        this.maxValue = maxValue <= 0 ? 1.0 : maxValue;
        this.normalColor = normalColor;

        double stroke   = Math.max(6, radius * 0.13);
        // 숫자 폰트: 500 같은 3자리도 잘리지 않도록 0.25배로 약간 줄임
        double valueFsz = Math.max(11, radius * 0.25);
        double capFsz   = Math.max(9,  radius * 0.15);

        // 위젯 크기: 가로는 호 + 스트로크, 세로는 호 높이 + 캡션
        double w  = radius * 2 + stroke * 2;
        double h  = radius + stroke * 1.5 + capFsz * 2.2;
        setPrefSize(w, h);
        setMinSize(w, h);
        setMaxSize(w, h);

        // 호 중심 y = 호가 위로 뻗어 있으므로 radius + stroke 아래에 고정
        double cx = w / 2;
        double cy = radius + stroke;   // 호 중심 (위젯 상단 기준)

        // 트랙(회색) — 0°에서 180° 반시계방향 = 위쪽 반원
        Arc trackArc = makeArc(cx, cy, radius, stroke, Color.web("#e8edf3"));
        trackArc.setStartAngle(0);
        trackArc.setLength(180);

        // 진행 호 — 180°(9시)에서 시계방향(음수 length)으로 채움
        progressArc = makeArc(cx, cy, radius, stroke, normalColor);
        progressArc.setStartAngle(180);
        progressArc.setLength(0);

        // 값 라벨: 호 안쪽 중앙 — 폭을 w 전체로 잡아 어떤 숫자도 잘리지 않게
        valueLabel = new Label("0");
        valueLabel.getStyleClass().add("gauge-value");
        valueLabel.setStyle("-fx-font-size: " + (int) valueFsz + "px; -fx-font-weight: 800;");
        double lblH = valueFsz * 1.5;
        valueLabel.setPrefSize(w, lblH);
        valueLabel.setMinSize(w, lblH);
        valueLabel.setAlignment(javafx.geometry.Pos.CENTER);
        valueLabel.setLayoutX(0);
        valueLabel.setLayoutY(cy - radius * 0.60);  // 호 중심보다 위 (호 안쪽)

        // 캡션 라벨: 호 중심 바로 아래
        String capText = (unit == null || unit.isBlank()) ? caption : unit + "  " + caption;
        Label captionLabel = new Label(capText);
        captionLabel.getStyleClass().add("gauge-caption");
        captionLabel.setStyle("-fx-font-size: " + (int) capFsz + "px;");
        captionLabel.setPrefSize(w, capFsz * 1.8);
        captionLabel.setMinSize(w, capFsz * 1.8);
        captionLabel.setAlignment(javafx.geometry.Pos.CENTER);
        captionLabel.setLayoutX(0);
        captionLabel.setLayoutY(cy + stroke * 0.5);  // 지름선 바로 아래

        getChildren().addAll(trackArc, progressArc, valueLabel, captionLabel);
    }

    private static Arc makeArc(double cx, double cy, double radius, double stroke, Paint color) {
        Arc arc = new Arc(cx, cy, radius, radius, 0, 0);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(color);
        arc.setStrokeWidth(stroke);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        return arc;
    }

    public void setMaxValue(double v) { this.maxValue = v <= 0 ? 1.0 : v; }
    public void setWarnRatio(double r) { this.warnRatio = r; }

    public void setValue(double value) {
        double ratio = Math.max(0.0, Math.min(1.0, value / maxValue));
        progressArc.setLength(-ratio * 180);
        progressArc.setStroke((value / maxValue) >= warnRatio ? WARN_COLOR : normalColor);
        valueLabel.setText(value >= 100
                ? String.format("%.0f", value)
                : String.format("%.1f", value));
    }
}
