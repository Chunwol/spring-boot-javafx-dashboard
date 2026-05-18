-- file distribution query performance and integrity hardening

CREATE UNIQUE INDEX IF NOT EXISTS ux_fd_agent_job_agent
    ON file_distribution_agent_status(job_id, agent_id);

CREATE INDEX IF NOT EXISTS ix_fd_agent_job_wave_status
    ON file_distribution_agent_status(job_id, wave, status);

CREATE INDEX IF NOT EXISTS ix_fd_agent_job_status
    ON file_distribution_agent_status(job_id, status);

CREATE INDEX IF NOT EXISTS ix_fd_event_job_created
    ON file_distribution_event(job_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_fd_job_created
    ON file_distribution_job(created_at DESC);
