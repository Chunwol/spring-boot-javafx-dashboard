package com.example.backend.dto.dashboard;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class DashboardPanelUpsertRequest {

    @NotBlank(message = "panelType은 필수입니다.")
    @Pattern(regexp = "^(CLASSROOM_HEARTBEAT|NETWORK_TRAFFIC|TEXT|IMAGE|COMMAND_STATUS)$",
            message = "panelType 값이 올바르지 않습니다.")
    private String panelType;
    private Long refGroupId;

    @Size(max = 4000, message = "configJson은 4000자를 초과할 수 없습니다.")
    private String configJson;
    private Double x;
    private Double y;

    @DecimalMin(value = "1.0", message = "width는 1.0 이상이어야 합니다.")
    private Double width;

    @DecimalMin(value = "1.0", message = "height는 1.0 이상이어야 합니다.")
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
