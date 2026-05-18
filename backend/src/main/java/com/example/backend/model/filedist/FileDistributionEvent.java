package com.example.backend.model.filedist;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 파일 배포 이벤트 엔티티.
 * 배포 작업 중 발생하는 이벤트(CREATED, CANCELLED, RETRIED, COMPLETED, FAILED, AGENT_RESULT 등)를
 * JSON 상세 정보와 함께 시계열로 기록한다.
 */
@Schema(description = "파일 배포 이벤트 로그 (작업 생명주기 추적)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "file_distribution_event")
public class FileDistributionEvent {

    /** 이벤트 ID (자동 생성) */
    @Schema(description = "이벤트 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 배포 작업 */
    @Schema(description = "소속 배포 작업")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private FileDistributionJob job;

    /** 이벤트 유형 (CREATED, CANCELLED, RETRIED, COMPLETED, FAILED, AGENT_RESULT 등) */
    @Schema(description = "이벤트 유형")
    @Column(nullable = false)
    private String eventType;

    /** 이벤트 상세 JSON */
    @Schema(description = "이벤트 상세 JSON")
    @Column(columnDefinition = "TEXT")
    private String detailJson;

    /** 이벤트 발생 일시 */
    @Schema(description = "이벤트 발생 일시")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
