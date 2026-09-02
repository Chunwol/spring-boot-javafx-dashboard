ALTER TABLE users ADD COLUMN active_session_id VARCHAR(36);
ALTER TABLE users ADD COLUMN login_ip_address VARCHAR(64);
ALTER TABLE users ADD COLUMN login_user_agent VARCHAR(500);
ALTER TABLE users ADD COLUMN login_at TIMESTAMP;
ALTER TABLE users ADD COLUMN last_activity_at TIMESTAMP;
ALTER TABLE users ADD COLUMN note VARCHAR(500);
ALTER TABLE users ADD COLUMN feature_permissions VARCHAR(500);

ALTER TABLE command_queue ADD COLUMN requested_by VARCHAR(100);
ALTER TABLE command_queue ADD COLUMN requested_ip VARCHAR(64);
