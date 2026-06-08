package com.example.frontend.controller.dashboard;

/**
 * 대시보드 그리드 레이아웃 설정 (Figma Auto Layout 대응).
 * 편집 모드 우측 패널에서 조정 가능하며, 자동 정렬 및 겹침 해소에 사용된다.
 */
public class DashboardGridSettings {

    public enum FlowDirection {
        FREE("자유"),
        ROW("행"),
        COLUMN("열"),
        GRID("격자");

        private final String label;

        FlowDirection(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private FlowDirection direction = FlowDirection.FREE;
    private int columns = 3;
    private double hGap = 20.0;
    private double vGap = 20.0;
    private double paddingTop = 20.0;
    private double paddingRight = 20.0;
    private double paddingBottom = 20.0;
    private double paddingLeft = 20.0;

    public FlowDirection getDirection() { return direction; }
    public void setDirection(FlowDirection direction) { this.direction = direction; }

    public int getColumns() { return columns; }
    public void setColumns(int columns) { this.columns = Math.max(1, columns); }

    public double getHGap() { return hGap; }
    public void setHGap(double hGap) { this.hGap = Math.max(0, hGap); }

    public double getVGap() { return vGap; }
    public void setVGap(double vGap) { this.vGap = Math.max(0, vGap); }

    public double getPaddingTop() { return paddingTop; }
    public void setPaddingTop(double paddingTop) { this.paddingTop = Math.max(0, paddingTop); }

    public double getPaddingRight() { return paddingRight; }
    public void setPaddingRight(double paddingRight) { this.paddingRight = Math.max(0, paddingRight); }

    public double getPaddingBottom() { return paddingBottom; }
    public void setPaddingBottom(double paddingBottom) { this.paddingBottom = Math.max(0, paddingBottom); }

    public double getPaddingLeft() { return paddingLeft; }
    public void setPaddingLeft(double paddingLeft) { this.paddingLeft = Math.max(0, paddingLeft); }
}
