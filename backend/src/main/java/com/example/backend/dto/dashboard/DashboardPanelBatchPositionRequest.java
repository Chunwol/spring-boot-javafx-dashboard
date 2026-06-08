package com.example.backend.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 여러 패널의 위치/크기/Z-순서를 한 번의 요청으로 일괄 업데이트하는 DTO.
 *
 * <p>정렬·균등 분배·맨앞/맨뒤 같은 다중 패널 작업 시 N번의 PUT을 1번으로 줄여
 * 서버 부하와 네트워크 왕복 비용을 절감한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "여러 패널의 위치/크기/Z-순서 일괄 업데이트 요청")
public class DashboardPanelBatchPositionRequest {

    @NotNull
    @Schema(description = "업데이트할 패널 항목 목록")
    private List<Item> items;

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(description = "단일 패널 위치/크기/Z-순서 변경 항목")
    public static class Item {
        @NotNull
        private Long panelId;
        private Double x;
        private Double y;
        private Double width;
        private Double height;
        private Integer zIndex;
        private Boolean visible;
    }
}
