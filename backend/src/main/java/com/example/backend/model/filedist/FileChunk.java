package com.example.backend.model.filedist;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 파일 청크 엔티티.
 * 파일 배포 작업에서 분할된 개별 청크의 해시, 크기, 업로드 상태를 관리한다.
 * 모든 청크가 VERIFIED 상태가 되면 배포 작업이 완료로 전환된다.
 */
@Schema(description = "파일 배포 청크 (분할 파일 단위)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "file_chunk")
public class FileChunk {

    /** 청크 ID (자동 생성) */
    @Schema(description = "청크 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 배포 작업 */
    @Schema(description = "소속 배포 작업")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private FileDistributionJob job;

    /** 청크 순서 인덱스 (0부터 시작) */
    @Schema(description = "청크 인덱스 (0부터 시작)")
    @Column(nullable = false)
    private Integer chunkIndex;

    /** 청크 SHA 해시 */
    @Schema(description = "청크 SHA 해시")
    @Column(nullable = false)
    private String chunkHash;

    /** 청크 크기 (바이트) */
    @Schema(description = "청크 크기 (바이트)")
    @Column(nullable = false)
    private Long sizeBytes;

    /** 청크 상태 (UPLOADED, VERIFIED, FAILED) */
    @Schema(description = "청크 상태 (UPLOADED, VERIFIED, FAILED)")
    @Column(nullable = false)
    private String status;

    /** 업로드 일시 */
    @Schema(description = "업로드 일시")
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /** 검증 완료 일시 */
    @Schema(description = "검증 완료 일시")
    private LocalDateTime verifiedAt;

    /** 오류 메시지 */
    @Schema(description = "오류 메시지")
    private String errorMessage;
}
