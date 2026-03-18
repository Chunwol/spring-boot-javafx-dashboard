package com.example.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassroomSummaryDto {
    private Long groupId;
    private String groupName;

    // legacy + frontend 호환 필드
    private int onlinePcCount;
    private int totalPcCount;

    private long onlinePcs;
    private long totalPcs;
    private String teacherPcStatus; // 예: "ONLINE", "OFFLINE"
    private LocalDateTime lastHeartbeat;
    private List<String> ipRanges;
    private boolean visible;
    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private List<PcStatusDto> pcList;
}