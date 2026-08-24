ALTER TABLE file_distribution_job ADD COLUMN source_agent_id TEXT;
ALTER TABLE file_distribution_job ADD COLUMN source_path TEXT;
ALTER TABLE file_distribution_job ADD COLUMN target_path TEXT;
ALTER TABLE file_distribution_job ADD COLUMN overwrite_existing BOOLEAN DEFAULT 0;
ALTER TABLE file_distribution_job ADD COLUMN peer_port INTEGER;

ALTER TABLE file_distribution_agent_status ADD COLUMN launcher_task_id INTEGER;
ALTER TABLE file_distribution_agent_status ADD COLUMN audit_task_id INTEGER;
ALTER TABLE file_distribution_agent_status ADD COLUMN verified_files INTEGER DEFAULT 0;
ALTER TABLE file_distribution_agent_status ADD COLUMN verified_bytes INTEGER DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_file_distribution_job_source_status
    ON file_distribution_job(source_agent_id, status);
