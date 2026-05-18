package com.example.backend.model.filedist;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 파일 배포 에이전트 상태 엔티티.
 * 배포 작업에서 각 에이전트(PC)별 웨이브 배정 및 진행 상태(QUEUED → COMPLETED 등)를 추적한다.
 */
@Schema(description = "파일 배포 에이전트별 상태 (웨이브, 진행 상태)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "file_distribution_agent_status")
public class FileDistributionAgentStatus {

    /** 상태 ID (자동 생성) */
    @Schema(description = "상태 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 배포 작업 */
    @Schema(description = "소속 배포 작업")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private FileDistributionJob job;

    /** 에이전트 ID (MAC 주소) */
    @Schema(description = "에이전트 ID (MAC 주소)")
    @Column(nullable = false)
    private String agentId;

    /** 배포 웨이브 번호 */
    @Schema(description = "배포 웨이브 번호")
    @Column(nullable = false)
    private Integer wave;

    /** 배포 상태 (QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED) */
    @Schema(description = "배포 상태")
    @Column(nullable = false)
    private String status;

    /** 배포 시작 일시 */
    @Schema(description = "배포 시작 일시")
    private LocalDateTime startedAt;

    /** 배포 완료 일시 */
    @Schema(description = "배포 완료 일시")
    private LocalDateTime completedAt;

    /** 오류 메시지 */
    @Schema(description = "오류 메시지")
    private String errorMessage;
}
