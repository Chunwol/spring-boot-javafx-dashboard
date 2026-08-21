package com.example.backend.model.pc;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PC 마스터 엔티티.
 * 강의실에 등록된 개별 PC의 식별 정보(MAC, IP, 이름),
 * 소속 그룹, 현재 상태, 마지막 에이전트 세션 정보를 관리한다.
 */
@Schema(description = "강의실 PC 마스터 정보")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pc_master")
public class PcMaster {

    /** MAC 주소 (PK) */
    @Schema(description = "MAC 주소 (기본키)")
    @Id
    @Column(name = "mac_address", nullable = false, length = 50)
    private String macAddress;

    /** PC 이름 */
    @Schema(description = "PC 이름")
    @Column(name = "pc_name")
    private String pcName;

    /** IP 주소 */
    @Schema(description = "IP 주소")
    @Column(name = "ip_address")
    private String ipAddress;

    /** 소속 강의실 그룹 ID */
    @Schema(description = "소속 강의실 그룹 ID")
    @Column(name = "group_id")
    private Long groupId;

    /** 현재 상태 (ONLINE / OFFLINE / BROKEN 등) */
    @Schema(description = "PC 상태 (ONLINE, OFFLINE, BROKEN 등)")
    @Column(name = "status")
    private String status;

    /** 마지막 하트비트 수신 일시 */
    @Schema(description = "마지막 하트비트 수신 일시")
    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    /** 마지막 에이전트 세션 ID */
    @Schema(description = "마지막 에이전트 세션 ID")
    @Column(name = "last_agent_session_id")
    private String lastAgentSessionId;

    /** PC 역할 (STUDENT / TEACHER 등) */
    @Schema(description = "PC 역할 (STUDENT, TEACHER 등)")
    @Column(name = "role")
    private String role;

    /** 고장 메모 */
    @Schema(description = "고장 메모")
    @Column(name = "broken_note")
    private String brokenNote;

    /** 에이전트 버전 */
    @Schema(description = "설치된 에이전트 버전")
    @Column(name = "agent_version", length = 40)
    private String agentVersion;

    /** 마지막으로 검증된 전체 설치 번들 버전 */
    @Schema(description = "검증 완료된 전체 설치 번들 버전")
    @Column(name = "bundle_version", length = 40)
    private String bundleVersion;

    @Column(name = "system_boot_time_epoch_seconds")
    private Long systemBootTimeEpochSeconds;

    @Column(name = "scmate_available")
    private Boolean scmateAvailable;

    @Column(name = "scmate_availability_reason", length = 40)
    private String scmateAvailabilityReason;

    @Column(name = "scmate_mode", length = 20)
    private String scmateMode;

    @Column(name = "scmate_schedule", length = 40)
    private String scmateSchedule;

    @Column(name = "scmate_selected_point", length = 20)
    private String scmateSelectedPoint;

    @Column(name = "scmate_product_variant", length = 80)
    private String scmateProductVariant;

    @Column(name = "scmate_state_source", length = 40)
    private String scmateStateSource;

    @Column(name = "scmate_transition_pending")
    private Boolean scmateTransitionPending;

    @Column(name = "scmate_raw_state")
    private Long scmateRawState;

    @Column(name = "scmate_observed_at")
    private LocalDateTime scmateObservedAt;

    /** 대시보드를 통해 마지막으로 SCMATE 현재 시점 저장이 성공한 시각 */
    @Column(name = "scmate_baseline_updated_at")
    private LocalDateTime scmateBaselineUpdatedAt;

    @Column(name = "scmate_baseline_created_at")
    private LocalDateTime scmateBaselineCreatedAt;

    @Column(name = "scmate_snap1_created_at")
    private LocalDateTime scmateSnap1CreatedAt;

    @Column(name = "scmate_snap2_created_at")
    private LocalDateTime scmateSnap2CreatedAt;

    @Column(name = "scmate_error", length = 255)
    private String scmateError;

    @Column(name = "scmate_workflow_stage", length = 40)
    private String scmateWorkflowStage;

    @Column(name = "scmate_workflow_id", length = 40)
    private String scmateWorkflowId;

    @Column(name = "scmate_workflow_start_session_id", length = 80)
    private String scmateWorkflowStartSessionId;

    @Column(name = "scmate_workflow_start_boot_time_epoch_seconds")
    private Long scmateWorkflowStartBootTimeEpochSeconds;

    // ── 도메인 메서드 ──────────────────────────────────────────────────────────

    /** 에이전트 등록 시 PC 기본 정보를 초기화한다. */
    public void initFromRegistration(String mac, String hostname, String ip) {
        this.macAddress = mac;
        this.pcName = hostname;
        this.ipAddress = ip;
        if (this.status == null) this.status = "OFFLINE";
        if (this.role == null) this.role = "UNKNOWN";
    }

    /** 하트비트 수신 시 상태를 온라인으로 갱신한다. */
    public void goOnline(LocalDateTime now) {
        this.status = "ONLINE";
        this.lastSeen = now;
    }

    /** PC를 오프라인으로 표시한다. */
    public void goOffline() {
        this.status = "OFFLINE";
    }

    /** PC를 고장 상태로 표시하고 메모를 저장한다. */
    public void markBroken(String note) {
        this.status = "BROKEN";
        this.brokenNote = note;
    }

    /** 고장 상태를 해제하고 오프라인으로 복구한다. */
    public void clearBroken() {
        this.status = "OFFLINE";
        this.brokenNote = null;
    }

    /** 강의실에 배정한다. */
    public void assignToGroup(Long groupId) {
        this.groupId = groupId;
    }
}
