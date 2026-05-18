-- V29: file_distribution_job 확장 (에이전트 업데이트/점진 웨이브 배포 메타데이터)
ALTER TABLE file_distribution_job ADD COLUMN artifact_id INTEGER;
ALTER TABLE file_distribution_job ADD COLUMN target_version VARCHAR(40);
ALTER TABLE file_distribution_job ADD COLUMN source_mode VARCHAR(32);
ALTER TABLE file_distribution_job ADD COLUMN ramp_up_factor REAL;

CREATE INDEX IF NOT EXISTS ix_fd_job_artifact
    ON file_distribution_job(artifact_id);
