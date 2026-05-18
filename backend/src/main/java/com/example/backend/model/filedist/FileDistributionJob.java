package com.example.backend.model.filedist;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_distribution_job")
public class FileDistributionJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String uploadId;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private Integer totalChunks;

    @Column(nullable = false)
    private String status; // QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String manifestPath;
    private String finalSha256;
    private String errorMessage;

    @Column(name = "artifact_id")
    private Long artifactId;

    @Column(name = "target_version", length = 40)
    private String targetVersion;

    @Column(name = "source_mode", length = 32)
    private String sourceMode;

    @Column(name = "ramp_up_factor")
    private Double rampUpFactor;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public void setManifestPath(String manifestPath) {
        this.manifestPath = manifestPath;
    }

    public String getFinalSha256() {
        return finalSha256;
    }

    public void setFinalSha256(String finalSha256) {
        this.finalSha256 = finalSha256;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(Long artifactId) {
        this.artifactId = artifactId;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public Double getRampUpFactor() {
        return rampUpFactor;
    }

    public void setRampUpFactor(Double rampUpFactor) {
        this.rampUpFactor = rampUpFactor;
    }
}
