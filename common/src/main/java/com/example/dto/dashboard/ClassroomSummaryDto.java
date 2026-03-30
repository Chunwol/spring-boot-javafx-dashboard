

package com.example.dto.dashboard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "강의실(클래스룸) 요약 정보")
public class ClassroomSummaryDto {
    @Schema(description = "강의실 ID", example = "1")
    private Long groupId;
    
    @Schema(description = "강의실 이름", example = "강의실 101")
    private String groupName;
    
    @Schema(description = "온라인 PC 수", example = "15")
    private int onlinePcCount;
    
    @Schema(description = "전체 PC 수", example = "20")
    private int totalPcCount;
    
    @Schema(description = "온라인 PC 수 (long)", example = "15")
    private int onlinePcs;
    
    @Schema(description = "전체 PC 수 (long)", example = "20")
    private int totalPcs;
    
    @Schema(description = "교사 PC 상태", example = "ONLINE")
    private String teacherPcStatus;
    
    @Schema(description = "마지막 하트비트 시간")
    private String lastHeartbeat;
    
    @Schema(description = "강의실의 IP 범위 목록")
    private List<String> ipRanges;
    
    @Schema(description = "대시보드에 표시 여부", example = "true")
    private boolean visible = true;
    
    @Schema(description = "대시보드 X 위치", example = "100.5")
    private Double x;
    
    @Schema(description = "대시보드 Y 위치", example = "200.5")
    private Double y;
    
    @Schema(description = "대시보드 패널 너비", example = "400.0")
    private Double width;
    
    @Schema(description = "대시보드 패널 높이", example = "300.0")
    private Double height;
}