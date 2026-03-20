package com.example.backend.dto.dashboard;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class DashboardPanelUpsertRequest {

    // panelType은 Create 시에만 필수 — Service의 applyPanelRequest(createMode=true)에서 검증
    private String panelType;
    private Long refGroupId;

    @Size(max = 4000, message = "configJson은 4000자를 초과할 수 없습니다.")
    private String configJson;
    private Double x;
    private Double y;
    private Double width;
    private Double height;

    @Min(value = 0, message = "zIndex는 0 이상이어야 합니다.")
    private Integer zIndex;
    private Boolean visible;

    public String getPanelType() {
        return panelType;
    }

    public void setPanelType(String panelType) {
        this.panelType = panelType;
    }

    public Long getRefGroupId() {
        return refGroupId;
    }

    public void setRefGroupId(Long refGroupId) {
        this.refGroupId = refGroupId;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Integer getZIndex() {
        return zIndex;
    }

    public void setZIndex(Integer zIndex) {
        this.zIndex = zIndex;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
}
