package com.example.backend.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 대시보드 패널 생성/수정 요청 DTO.
 *
 * <p>패널 생성(Create)과 수정(Update) 모두 이 DTO를 사용한다.
 * {@code panelType}은 생성 시에만 필수이며, 수정 시 null이면 기존 값을 유지한다.
 * 서비스 레이어의 {@code applyPanelRequest(createMode=true/false)}에서 검증한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "대시보드 패널 생성/수정 요청 DTO")
public class DashboardPanelUpsertRequest {

    @Schema(description = "패널 유형 (생성 시 필수: CLASSROOM_STATUS / TRAFFIC_CHART 등)",
            example = "CLASSROOM_STATUS")
    private String panelType;

    @Schema(description = "연결할 강의실 그룹 ID (패널 유형에 따라 선택적)", example = "1")
    private Long refGroupId;

    @Size(max = 4000, message = "configJson은 4000자를 초과할 수 없습니다.")
    @Schema(description = "패널별 추가 설정 JSON (최대 4000자)", example = "{\"showOffline\":true}")
    private String configJson;

    @Schema(description = "패널 X 좌표 (레이아웃 그리드 기준)", example = "0.0")
    private Double x;

    @Schema(description = "패널 Y 좌표 (레이아웃 그리드 기준)", example = "0.0")
    private Double y;

    @Schema(description = "패널 너비", example = "400.0")
    private Double width;

    @Schema(description = "패널 높이", example = "300.0")
    private Double height;

    @Min(value = 0, message = "zIndex는 0 이상이어야 합니다.")
    @Schema(description = "패널 레이어 순서 (0 이상)", example = "0")
    private Integer zIndex;

    @Schema(description = "패널 표시 여부", example = "true")
    private Boolean visible;

    @Schema(description = "패널 이름 (사용자 지정)", example = "1강의실 현황")
    private String panelName;
}
