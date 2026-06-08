package com.example.frontend.controller.dashboard;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.List;

/**
 * Figma 스타일 스마트 가이드 시스템.
 * <p>
 * 패널을 드래그할 때 다른 패널들과의 정렬을 감지하여
 * <ul>
 *   <li>좌/우/중앙(X), 상/하/중앙(Y) 모서리가 임계값 이내로 가까워지면 자동 스냅</li>
 *   <li>스냅된 위치에 분홍색 가이드 라인 표시</li>
 * </ul>
 * 한다.
 */
public class DashboardSmartGuides {
    private static final double SNAP_THRESHOLD = 6.0;
    private static final int    POOL_PER_AXIS = 3;
    private static final Color  GUIDE_COLOR   = Color.web("#ff3d71");

    @FunctionalInterface
    public interface SmartSnap {
        /** 드래그 시도 위치(tryX, tryY)에 대해 스냅 보정된 [x, y]를 반환 */
        double[] snap(Region panel, double tryX, double tryY);
    }

    private final Pane container;
    private final List<Line> vGuides = new ArrayList<>();
    private final List<Line> hGuides = new ArrayList<>();

    public DashboardSmartGuides(Pane container) {
        this.container = container;
        for (int i = 0; i < POOL_PER_AXIS; i++) {
            vGuides.add(createGuide());
            hGuides.add(createGuide());
        }
    }

    private Line createGuide() {
        Line line = new Line();
        line.setStroke(GUIDE_COLOR);
        line.setStrokeWidth(1.0);
        line.setManaged(false);
        line.setVisible(false);
        line.setMouseTransparent(true);
        container.getChildren().add(line);
        return line;
    }

    public void clearGuides() {
        vGuides.forEach(l -> l.setVisible(false));
        hGuides.forEach(l -> l.setVisible(false));
    }

    /** 드래그 위치를 다른 패널의 모서리/중앙과 정렬되도록 보정 (스냅 + 가이드 라인). */
    /** 단일 축에 대한 스냅 결과: bestDiff(=0이면 매칭 없음)와 가이드 라인 목록 */
    private static final class AxisSnap {
        double bestDiff = Double.POSITIVE_INFINITY;
        final List<double[]> guides = new ArrayList<>();
        boolean matched() { return Math.abs(bestDiff) <= SNAP_THRESHOLD; }
    }

    public double[] snap(Region dragged, double tryX, double tryY) {
        clearGuides();

        double dragW = sizeOf(dragged, true);
        double dragH = sizeOf(dragged, false);

        double dL = tryX,  dR = tryX + dragW,  dCx = tryX + dragW / 2;
        double dT = tryY,  dB = tryY + dragH,  dCy = tryY + dragH / 2;

        AxisSnap xSnap = new AxisSnap();
        AxisSnap ySnap = new AxisSnap();

        // 드래그 중 자식 리스트 변경 가능성 차단을 위한 스냅샷
        Node[] children = container.getChildren().toArray(new Node[0]);
        for (Node other : children) {
            if (other == dragged || !isContentNode(other)) continue;
            double oL = other.getLayoutX();
            double oR = oL + sizeOf(other, true);
            double oCx = (oL + oR) / 2;
            double oT = other.getLayoutY();
            double oB = oT + sizeOf(other, false);
            double oCy = (oT + oB) / 2;

            // X축: drag-edge ↔ other-edge 5쌍, 가이드 길이는 [min(top), max(bottom)]
            tryAxis(xSnap,
                    new double[][]{{dL, oL, oL}, {dR, oR, oR}, {dL, oR, oR}, {dR, oL, oL}, {dCx, oCx, oCx}},
                    Math.min(dT, oT), Math.max(dB, oB));
            // Y축: 5쌍, 가이드 길이는 [min(left), max(right)]
            tryAxis(ySnap,
                    new double[][]{{dT, oT, oT}, {dB, oB, oB}, {dT, oB, oB}, {dB, oT, oT}, {dCy, oCy, oCy}},
                    Math.min(dL, oL), Math.max(dR, oR));
        }

        double newX = tryX, newY = tryY;
        if (xSnap.matched()) { newX = tryX + xSnap.bestDiff; drawVerticalGuides(xSnap.guides); }
        if (ySnap.matched()) { newY = tryY + ySnap.bestDiff; drawHorizontalGuides(ySnap.guides); }
        return new double[]{newX, newY};
    }

    /** 한 축에 대한 5쌍의 edge 후보 평가 + best 갱신. guideExtentLo/Hi는 가이드 라인 길이용. */
    private static void tryAxis(AxisSnap snap, double[][] pairs, double guideExtentLo, double guideExtentHi) {
        for (double[] p : pairs) {
            double diff = p[1] - p[0];
            double absDiff = Math.abs(diff);
            if (absDiff > SNAP_THRESHOLD) continue;
            double best = Math.abs(snap.bestDiff);
            if (absDiff < best) {
                snap.bestDiff = diff;
                snap.guides.clear();
                snap.guides.add(new double[]{p[2], guideExtentLo, guideExtentHi});
            } else if (absDiff == best) {
                snap.guides.add(new double[]{p[2], guideExtentLo, guideExtentHi});
            }
        }
    }

    private void drawVerticalGuides(List<double[]> snaps) {
        int n = Math.min(snaps.size(), vGuides.size());
        for (int i = 0; i < n; i++) {
            double[] s = snaps.get(i);
            Line line = vGuides.get(i);
            line.setStartX(s[0]); line.setEndX(s[0]);
            line.setStartY(s[1] - 4); line.setEndY(s[2] + 4);
            if (!line.isVisible()) {
                line.setVisible(true);
                line.toFront(); // 처음 보일 때만 z-order 조정
            }
        }
    }

    private void drawHorizontalGuides(List<double[]> snaps) {
        int n = Math.min(snaps.size(), hGuides.size());
        for (int i = 0; i < n; i++) {
            double[] s = snaps.get(i);
            Line line = hGuides.get(i);
            line.setStartY(s[0]); line.setEndY(s[0]);
            line.setStartX(s[1] - 4); line.setEndX(s[2] + 4);
            if (!line.isVisible()) {
                line.setVisible(true);
                line.toFront();
            }
        }
    }

    private static boolean isContentNode(Node n) {
        return n.getProperties().containsKey("panelType");
    }

    private static double sizeOf(Node n, boolean width) {
        if (n instanceof Region r) {
            double pref = width ? r.getPrefWidth() : r.getPrefHeight();
            if (pref > 0) return pref;
            return width ? r.getWidth() : r.getHeight();
        }
        return width ? n.getBoundsInLocal().getWidth() : n.getBoundsInLocal().getHeight();
    }
}
