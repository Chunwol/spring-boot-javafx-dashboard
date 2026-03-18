package com.example.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "대시보드 통합 새로고침 응답")
public class DashboardRefreshDto {
    @Schema(description = "강의실 목록")
    private List<ClassroomSummaryDto> classrooms = new ArrayList<>();

    @Schema(description = "네트워크 트래픽")
    private NetworkTrafficDto traffic = new NetworkTrafficDto();

    @Schema(description = "텍스트 위젯 목록")
    private List<Map<String, Object>> texts = new ArrayList<>();

    @Schema(description = "이미지 위젯 목록")
    private List<Map<String, Object>> images = new ArrayList<>();
}