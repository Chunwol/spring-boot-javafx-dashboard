package com.example.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Dashboard layout backup data transfer object.
 * Contains complete dashboard layout information including panels, zoom, and viewport state.
 */
public class LayoutBackup {

    @JsonProperty("id")
    private String id;

    @JsonProperty("dashboardId")
    private Long dashboardId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("timestamp")
    private LocalDateTime timestamp;

    @JsonProperty("layoutData")
    private Object layoutData; // Serialized layout configuration

    public LayoutBackup() {}

    public LayoutBackup(Long dashboardId, String name, LocalDateTime timestamp, Object layoutData) {
        this.dashboardId = dashboardId;
        this.name = name;
        this.timestamp = timestamp;
        this.layoutData = layoutData;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(Long dashboardId) {
        this.dashboardId = dashboardId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Object getLayoutData() {
        return layoutData;
    }

    public void setLayoutData(Object layoutData) {
        this.layoutData = layoutData;
    }

    @Override
    public String toString() {
        return "LayoutBackup{" +
                "id='" + id + '\'' +
                ", dashboardId=" + dashboardId +
                ", name='" + name + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
