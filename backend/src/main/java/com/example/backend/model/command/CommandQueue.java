package com.example.backend.model.command;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 명령 큐 엔티티.
 * 관리자 또는 시스템이 에이전트에게 전달할 명령(재부팅, 스크립트 실행 등)을
 * 큐에 적재하고, 에이전트가 폴링하여 수행한다.
 * 명령의 생성 → 전달 → 확인(ACK) → 완료 전 과정을 타임스탬프로 추적한다.
 */
@Schema(description = "에이전트 명령 큐 (원격 명령 적재 및 상태 추적)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "command_queue")
public class CommandQueue {

    /** 작업 ID (자동 생성) */
    @Schema(description = "작업 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;

    /** 대상 에이전트 MAC 주소 */
    @Schema(description = "대상 에이전트 MAC 주소")
    @Column(nullable = false)
    private String macAddress;

    /** 명령 유형 (예: REBOOT, RUN_SCRIPT, SHUTDOWN) */
    @Schema(description = "명령 유형")
    @Column(name = "command_type")
    private String commandType;

    /** 실행할 스크립트 URL */
    @Schema(description = "스크립트 URL")
    @Column(name = "script_url")
    private String scriptUrl;

    /** 실행 여부 */
    @Schema(description = "실행 여부")
    @Column(name = "is_executed")
    private Boolean isExecuted = false;

    /** 명령 상태 (QUEUED / DELIVERED / ACKED / COMPLETED / FAILED / CANCELLED) */
    @Schema(description = "명령 상태")
    @Column(name = "status")
    private String status = "QUEUED";

    /** 추가 페이로드 JSON */
    @Schema(description = "추가 페이로드 JSON")
    @Column(name = "payload_json")
    private String payloadJson;

    /** 명령 생성 일시 */
    @Schema(description = "명령 생성 일시")
    @Column(name = "created_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime createdAt;

    /** 에이전트에 전달된 일시 */
    @Schema(description = "에이전트 전달 일시")
    @Column(name = "delivered_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime deliveredAt;

    /** 에이전트 ACK 일시 */
    @Schema(description = "에이전트 ACK 일시")
    @Column(name = "acked_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime ackedAt;

    /** 명령 완료 일시 */
    @Schema(description = "명령 완료 일시")
    @Column(name = "completed_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime completedAt;

    /** 종료 코드 */
    @Schema(description = "프로세스 종료 코드")
    @Column(name = "exit_code")
    private Integer exitCode;

    /** 표준 출력 */
    @Schema(description = "표준 출력(stdout)")
    @Column(name = "stdout")
    private String stdout;

    /** 표준 오류 */
    @Schema(description = "표준 오류(stderr)")
    @Column(name = "stderr")
    private String stderr;

    /** 재시도 횟수 */
    @Schema(description = "재시도 횟수")
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** 관리자 작업 명령 여부 (파일 설치, 에이전트 업데이트 등) - SCMATE 초기화 감지용 */
    @Schema(description = "관리자 작업 명령 여부")
    @Column(name = "is_admin_operation")
    private Boolean isAdminOperation = false;

    /** SCMATE 초기화 복구 상태: null | PENDING | COMPLETED */
    @Schema(description = "SCMATE 초기화 복구 상태")
    @Column(name = "scmate_reset_status")
    private String scmateResetStatus;

    /** SCMATE 초기화 감지 시각 */
    @Schema(description = "SCMATE 초기화 감지 시각")
    @Column(name = "scmate_reset_requested_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime scmateResetRequestedAt;

    /** SCMATE 초기화 복구 완료 시각 */
    @Schema(description = "SCMATE 초기화 복구 완료 시각")
    @Column(name = "scmate_reset_completed_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime scmateResetCompletedAt;

    /** 검증 상태 */
    @Schema(description = "명령 실행 결과 검증 상태")
    @Column(name = "verification_status")
    private String verificationStatus;

    /** 검증 시작 일시 */
    @Schema(description = "검증 시작 일시")
    @Column(name = "verification_started_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime verificationStartedAt;

    /** 검증 마감 일시 */
    @Schema(description = "검증 마감 일시")
    @Column(name = "verification_deadline_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime verificationDeadlineAt;

    /** 검증 중 오프라인 감지 일시 */
    @Schema(description = "검증 중 오프라인 감지 일시")
    @Column(name = "verification_observed_offline_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime verificationObservedOfflineAt;

    /** 검증 완료 일시 */
    @Schema(description = "검증 완료 일시")
    @Column(name = "verification_completed_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime verificationCompletedAt;

    /**
     * null-safe isAdminOperation getter.
     * DB에 null 이 저장된 경우 false 를 반환한다.
     */
    public Boolean getIsAdminOperation() {
        return isAdminOperation != null ? isAdminOperation : false;
    }
}
