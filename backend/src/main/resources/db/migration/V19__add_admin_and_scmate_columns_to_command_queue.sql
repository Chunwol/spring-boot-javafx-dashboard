ALTER TABLE command_queue ADD COLUMN is_admin_operation BOOLEAN DEFAULT 0;
ALTER TABLE command_queue ADD COLUMN scmate_reset_status TEXT;
ALTER TABLE command_queue ADD COLUMN scmate_reset_requested_at TEXT;
ALTER TABLE command_queue ADD COLUMN scmate_reset_completed_at TEXT;

CREATE INDEX IF NOT EXISTS idx_command_queue_admin_acked
    ON command_queue(is_admin_operation, acked_at);

CREATE INDEX IF NOT EXISTS idx_command_queue_mac_scmate_reset_status
    ON command_queue(mac_address, scmate_reset_status);