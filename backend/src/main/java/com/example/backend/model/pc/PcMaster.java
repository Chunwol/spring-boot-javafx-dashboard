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
