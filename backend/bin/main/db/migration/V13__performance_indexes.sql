-- Performance indexes for 830-node load and command delivery reliability.

-- command_queue hot paths: heartbeat delivery, in-flight listing, timeout retry scans
CREATE INDEX IF NOT EXISTS idx_command_queue_mac_status_task
    ON command_queue(mac_address, status, task_id);

CREATE INDEX IF NOT EXISTS idx_command_queue_status_created
    ON command_queue(status, created_at);

CREATE INDEX IF NOT EXISTS idx_command_queue_status_delivered
    ON command_queue(status, delivered_at);

CREATE INDEX IF NOT EXISTS idx_command_queue_status_acked
    ON command_queue(status, acked_at);

-- pc_master hot paths: online/offline scans and timeout transition
CREATE INDEX IF NOT EXISTS idx_pc_master_status_last_seen
    ON pc_master(status, last_seen);

-- traffic anomaly scans (recent window)
CREATE INDEX IF NOT EXISTS idx_traffic_logs_timestamp
    ON traffic_logs(timestamp);

CREATE INDEX IF NOT EXISTS idx_traffic_logs_mac_timestamp
    ON traffic_logs(mac_address, timestamp);

-- agent token validation and revocation stats
CREATE INDEX IF NOT EXISTS idx_agent_auth_tokens_revoked
    ON agent_auth_tokens(revoked);
