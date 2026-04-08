package com.example.backend.model.filedist;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_distribution_agent_status")
public class FileDistributionAgentStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private FileDistributionJob job;

    @Column(nullable = false)
    private String agentId;

    @Column(nullable = false)
    private Integer wave;

    @Column(nullable = false)
    private String status; // QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FileDistributionJob getJob() {
        return job;
    }

    public void setJob(FileDistributionJob job) {
        this.job = job;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getWave() {
        return wave;
    }

    public void setWave(Integer wave) {
        this.wave = wave;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
