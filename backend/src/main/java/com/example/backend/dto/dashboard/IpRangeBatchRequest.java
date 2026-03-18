package com.example.backend.dto.dashboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class IpRangeBatchRequest {

    @NotNull(message = "groupId는 필수입니다.")
    private Long groupId;

    /** 예: 10.0.1. */
    private String basePrefix;

    /** 예: 10-20,30-40 또는 10.0.1.10-10.0.1.20,10.0.1.30-10.0.1.40 */
    @NotBlank(message = "rangeSpec은 필수입니다.")
    @Size(max = 1000, message = "rangeSpec이 너무 깁니다.")
    private String rangeSpec;

    private String description;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getBasePrefix() {
        return basePrefix;
    }

    public void setBasePrefix(String basePrefix) {
        this.basePrefix = basePrefix;
    }

    public String getRangeSpec() {
        return rangeSpec;
    }

    public void setRangeSpec(String rangeSpec) {
        this.rangeSpec = rangeSpec;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
