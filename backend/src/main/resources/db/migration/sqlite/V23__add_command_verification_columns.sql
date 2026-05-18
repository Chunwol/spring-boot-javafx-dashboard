ALTER TABLE command_queue ADD COLUMN verification_status TEXT;
ALTER TABLE command_queue ADD COLUMN verification_started_at TEXT;
ALTER TABLE command_queue ADD COLUMN verification_deadline_at TEXT;
ALTER TABLE command_queue ADD COLUMN verification_observed_offline_at TEXT;
ALTER TABLE command_queue ADD COLUMN verification_completed_at TEXT;

CREATE INDEX IF NOT EXISTS idx_command_queue_verification_status_deadline
    ON command_queue(verification_status, verification_deadline_at);