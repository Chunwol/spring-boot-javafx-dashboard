package com.example.backend.model.filedist;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 파일 배포 작업 엔티티.
 * 에이전트에 파일을 청크 단위로 배포하는 작업의 메타데이터를 관리한다.
 * 작업 상태(QUEUED → IN_PROGRESS → COMPLETED/FAILED/CANCELLED)와
 * 청크 수, 소스 모드, 아티팩트 연결 정보를 포함한다.
 */
@Schema(description = "파일 배포 작업 (청크 기반 에이전트 파일 배포)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "file_distribution_job")
public class FileDistributionJob {

    /** 작업 ID (자동 생성) */
    @Schema(description = "작업 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 업로드 세션 ID */
    @Schema(description = "업로드 세션 ID")
    @Column(nullable = false)
    private String uploadId;

    /** 원본 파일명 */
    @Schema(description = "원본 파일명")
    @Column(nullable = false)
    private String originalFilename;

    /** 총 청크 수 */
    @Schema(description = "총 청크 수")
    @Column(nullable = false)
    private Integer totalChunks;

    /** 작업 상태 (QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED) */
    @Schema(description = "작업 상태")
    @Column(nullable = false)
    private String status;

    /** 작업 생성 일시 */
    @Schema(description = "작업 생성 일시")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 작업 완료 일시 */
    @Schema(description = "작업 완료 일시")
    private LocalDateTime completedAt;

    /** 작업 취소 일시 */
    @Schema(description = "작업 취소 일시")
    private LocalDateTime cancelledAt;

    /** 매니페스트 파일 경로 */
    @Schema(description = "매니페스트 파일 경로")
    private String manifestPath;

    /** 최종 파일 SHA-256 해시 */
    @Schema(description = "최종 파일 SHA-256 해시")
    private String finalSha256;

    /** 오류 메시지 */
    @Schema(description = "오류 메시지")
    private String errorMessage;

    /** 연결된 스크립트 아티팩트 ID */
    @Schema(description = "연결된 스크립트 아티팩트 ID")
    @Column(name = "artifact_id")
    private Long artifactId;

    /** 배포 대상 에이전트 버전 */
    @Schema(description = "배포 대상 에이전트 버전")
    @Column(name = "target_version", length = 40)
    private String targetVersion;

    /** 소스 모드 (artifact, upload 등) */
    @Schema(description = "소스 모드 (artifact, upload 등)")
    @Column(name = "source_mode", length = 32)
    private String sourceMode;

    /** 웨이브별 단계적 배포 비율 */
    @Schema(description = "웨이브별 배포 비율 (ramp-up factor)")
    @Column(name = "ramp_up_factor")
    private Double rampUpFactor;

    /** 업데이트 패키지 유형 (JAR 또는 FULL_BUNDLE) */
    @Schema(description = "업데이트 패키지 유형", allowableValues = {"JAR", "FULL_BUNDLE"})
    @Column(name = "update_package_type", length = 24)
    private String updatePackageType;
}
