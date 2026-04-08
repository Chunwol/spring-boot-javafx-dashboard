package com.example.backend.model.filedist;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_distribution_event")
public class FileDistributionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private FileDistributionJob job;

    @Column(nullable = false)
    private String eventType; // CREATED, CANCELLED, RETRIED, COMPLETED, FAILED, AGENT_RESULT, ...

    @Column(columnDefinition = "TEXT")
    private String detailJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
