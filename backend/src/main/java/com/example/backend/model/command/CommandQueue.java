package com.example.backend.model.command;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "command_queue")
public class CommandQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;

    @Column(nullable = false)
    private String macAddress;

    @Column(name = "command_type")
    private String commandType;

    @Column(name = "script_url")
    private String scriptUrl;

    @Column(name = "is_executed")
    private Boolean isExecuted = false;

    @Column(name = "status")
    private String status = "QUEUED";

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "stdout")
    private String stdout;

    @Column(name = "stderr")
    private String stderr;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** 관리자 작업 명령 여부 (파일 설치, 에이전트 업데이트 등) - SCMATE 초기화 감지용 */
    @Column(name = "is_admin_operation")
    private Boolean isAdminOperation = false;

    /** SCMATE 초기화 복구 상태: null | PENDING | COMPLETED */
    @Column(name = "scmate_reset_status")
    private String scmateResetStatus;

    /** SCMATE 초기화 감지 시각 */
    @Column(name = "scmate_reset_requested_at")
    private LocalDateTime scmateResetRequestedAt;

    /** SCMATE 초기화 복구 완료 시각 */
    @Column(name = "scmate_reset_completed_at")
    private LocalDateTime scmateResetCompletedAt;

    public CommandQueue() {
    }

    // getters and setters
    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getScriptUrl() {
        return scriptUrl;
    }

    public void setScriptUrl(String scriptUrl) {
        this.scriptUrl = scriptUrl;
    }

    public Boolean getIsExecuted() {
        return isExecuted;
    }

    public void setIsExecuted(Boolean executed) {
        isExecuted = executed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public LocalDateTime getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(LocalDateTime ackedAt) {
        this.ackedAt = ackedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getIsAdminOperation() {
        return isAdminOperation != null ? isAdminOperation : false;
    }

    public void setIsAdminOperation(Boolean adminOperation) {
        isAdminOperation = adminOperation;
    }

    public String getScmateResetStatus() {
        return scmateResetStatus;
    }

    public void setScmateResetStatus(String scmateResetStatus) {
        this.scmateResetStatus = scmateResetStatus;
    }

    public LocalDateTime getScmateResetRequestedAt() {
        return scmateResetRequestedAt;
    }

    public void setScmateResetRequestedAt(LocalDateTime scmateResetRequestedAt) {
        this.scmateResetRequestedAt = scmateResetRequestedAt;
    }

    public LocalDateTime getScmateResetCompletedAt() {
        return scmateResetCompletedAt;
    }

    public void setScmateResetCompletedAt(LocalDateTime scmateResetCompletedAt) {
        this.scmateResetCompletedAt = scmateResetCompletedAt;
    }
}
