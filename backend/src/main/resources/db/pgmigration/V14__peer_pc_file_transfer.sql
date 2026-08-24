ALTER TABLE file_distribution_job ADD COLUMN IF NOT EXISTS source_agent_id TEXT;
ALTER TABLE file_distribution_job ADD COLUMN IF NOT EXISTS source_path TEXT;
ALTER TABLE file_distribution_job ADD COLUMN IF NOT EXISTS target_path TEXT;
ALTER TABLE file_distribution_job ADD COLUMN IF NOT EXISTS overwrite_existing BOOLEAN DEFAULT FALSE;
ALTER TABLE file_distribution_job ADD COLUMN IF NOT EXISTS peer_port INTEGER;

ALTER TABLE file_distribution_agent_status ADD COLUMN IF NOT EXISTS launcher_task_id BIGINT;
ALTER TABLE file_distribution_agent_status ADD COLUMN IF NOT EXISTS audit_task_id BIGINT;
ALTER TABLE file_distribution_agent_status ADD COLUMN IF NOT EXISTS verified_files BIGINT DEFAULT 0;
ALTER TABLE file_distribution_agent_status ADD COLUMN IF NOT EXISTS verified_bytes BIGINT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_file_distribution_job_source_status
    ON file_distribution_job(source_agent_id, status);
