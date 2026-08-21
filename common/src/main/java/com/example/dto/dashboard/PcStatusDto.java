package com.example.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "PC의 상태 정보")
public class PcStatusDto {
    @Schema(description = "PC MAC 주소", example = "00:1A:2B:3C:4D:5E")
    private String macAddress;

    @Schema(description = "PC 호스트명", example = "PC-101-001")
    private String pcName;

    @Schema(description = "PC IP 주소", example = "192.168.10.10")
    private String ipAddress;

    @Schema(description = "강의실 ID", example = "101")
    private Long groupId;

    @Schema(description = "강의실 이름", example = "강의실 101")
    private String groupName;

    @Schema(description = "PC 온라인 여부", example = "true")
    private boolean online;

    @Schema(description = "마지막 하트비트 수신 시간")
    private LocalDateTime lastSeen;

    @Schema(description = "PC 상태", example = "ONLINE", allowableValues = {"ONLINE", "OFFLINE", "IDLE"})
    private String status;

    @Schema(description = "PC 역할", example = "STUDENT", allowableValues = {"STUDENT", "TEACHER"})
    private String role;

    @Schema(description = "에이전트 버전", example = "1.2.0")
    private String agentVersion;

    @Schema(description = "검증 완료된 전체 설치 번들 버전", example = "1.1.20")
    private String bundleVersion;

    private Long systemBootTimeEpochSeconds;

    @Schema(description = "고장 사유", example = "메인보드 불량")
    private String brokenNote;

    private Boolean scmateAvailable;
    private String scmateAvailabilityReason;
    private String scmateMode;
    private String scmateSchedule;
    private String scmateSelectedPoint;
    private String scmateProductVariant;
    private String scmateStateSource;
    private Boolean scmateTransitionPending;
    private Long scmateRawState;
    private LocalDateTime scmateObservedAt;
    private LocalDateTime scmateBaselineUpdatedAt;
    private LocalDateTime scmateBaselineCreatedAt;
    private LocalDateTime scmateSnap1CreatedAt;
    private LocalDateTime scmateSnap2CreatedAt;
    private String scmateError;
    private String scmateWorkflowStage;
    private String scmateWorkflowId;
    private List<String> scmateAllowedActions;
}
