-- PostgreSQL baseline schema for production deployments.
-- This baseline represents the consolidated state of SQLite migrations V1~V29.

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message TEXT NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS classroom_groups (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE TABLE IF NOT EXISTS pc_master (
    mac_address VARCHAR(50) PRIMARY KEY,
    pc_name TEXT,
    ip_address TEXT,
    group_id BIGINT REFERENCES classroom_groups(id),
    status TEXT,
    last_seen TIMESTAMP,
    role TEXT DEFAULT 'UNKNOWN',
    broken_note TEXT,
    last_agent_session_id TEXT,
    agent_version VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS classroom_ip_ranges (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES classroom_groups(id) ON DELETE CASCADE,
    cidr TEXT,
    start_ip TEXT,
    end_ip TEXT,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS classroom_teacher_devices (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES classroom_groups(id) ON DELETE CASCADE,
    ip_address TEXT,
    mac_address TEXT,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS traffic_logs (
    log_id BIGSERIAL PRIMARY KEY,
    mac_address TEXT NOT NULL REFERENCES pc_master(mac_address) ON DELETE CASCADE,
    inbound_kbps BIGINT,
    outbound_kbps BIGINT,
    "timestamp" TIMESTAMP
);

CREATE TABLE IF NOT EXISTS command_queue (
    task_id BIGSERIAL PRIMARY KEY,
    mac_address TEXT NOT NULL REFERENCES pc_master(mac_address) ON DELETE CASCADE,
    command_type TEXT,
    script_url TEXT,
    is_executed BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    payload_json TEXT,
    created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    delivered_at TEXT,
    acked_at TEXT,
    completed_at TEXT,
    exit_code INTEGER,
    stdout TEXT,
    stderr TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    is_admin_operation BOOLEAN NOT NULL DEFAULT FALSE,
    scmate_reset_status TEXT,
    scmate_reset_requested_at TEXT,
    scmate_reset_completed_at TEXT,
    verification_status TEXT,
    verification_started_at TEXT,
    verification_deadline_at TEXT,
    verification_observed_offline_at TEXT,
    verification_completed_at TEXT
);

CREATE TABLE IF NOT EXISTS client_positions (
    id BIGSERIAL PRIMARY KEY,
    mac_address TEXT NOT NULL,
    ip_address TEXT,
    group_id BIGINT,
    pos_x DOUBLE PRECISION,
    pos_y DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS dashboards (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    display_order INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE TABLE IF NOT EXISTS dashboard_modules (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT REFERENCES classroom_groups(id),
    type TEXT NOT NULL,
    config TEXT,
    pos_x DOUBLE PRECISION,
    pos_y DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dashboard_panels (
    id BIGSERIAL PRIMARY KEY,
    dashboard_id BIGINT NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    panel_type TEXT NOT NULL,
    ref_group_id BIGINT REFERENCES classroom_groups(id),
    config_json TEXT,
    x DOUBLE PRECISION DEFAULT 0,
    y DOUBLE PRECISION DEFAULT 0,
    width DOUBLE PRECISION DEFAULT 300,
    height DOUBLE PRECISION DEFAULT 220,
    z_index INTEGER DEFAULT 0,
    visible BOOLEAN DEFAULT TRUE,
    panel_name TEXT,
    created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS dashboard_widget_items (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT REFERENCES classroom_groups(id),
    dashboard_id BIGINT REFERENCES dashboards(id) ON DELETE SET NULL,
    type TEXT NOT NULL,
    config TEXT,
    x DOUBLE PRECISION,
    y DOUBLE PRECISION,
    width DOUBLE PRECISION,
    height DOUBLE PRECISION,
    visible BOOLEAN DEFAULT TRUE,
    created_at TEXT
);

CREATE TABLE IF NOT EXISTS scripts (
    id BIGSERIAL PRIMARY KEY,
    original_filename TEXT NOT NULL,
    stored_filename TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type TEXT,
    created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE TABLE IF NOT EXISTS agent_auth_tokens (
    id BIGSERIAL PRIMARY KEY,
    mac_address TEXT NOT NULL UNIQUE REFERENCES pc_master(mac_address) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    last_used_at TEXT,
    revoked BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    detail_json TEXT,
    ip_address TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS file_distribution_job (
    id BIGSERIAL PRIMARY KEY,
    upload_id TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    total_chunks INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    manifest_path TEXT,
    final_sha256 TEXT,
    error_message TEXT,
    artifact_id BIGINT,
    target_version VARCHAR(40),
    source_mode VARCHAR(32),
    ramp_up_factor DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS file_chunk (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES file_distribution_job(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_hash TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    status TEXT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    error_message TEXT,
    CONSTRAINT ux_file_chunk_job_index UNIQUE (job_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS file_distribution_agent_status (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES file_distribution_job(id) ON DELETE CASCADE,
    agent_id TEXT NOT NULL,
    wave INTEGER NOT NULL,
    status TEXT NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS file_distribution_event (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES file_distribution_job(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    detail_json TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS dashboard_layout_backups (
    id BIGSERIAL PRIMARY KEY,
    dashboard_id BIGINT NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    layout_data TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    is_automatic BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS operational_alerts (
    id BIGSERIAL PRIMARY KEY,
    source_mac TEXT NOT NULL,
    message TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'WARNING',
    alert_type TEXT NOT NULL DEFAULT 'SYSTEM',
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged_at TEXT,
    acknowledged_by TEXT,
    created_at TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_client_positions_group
    ON client_positions(group_id);
CREATE INDEX IF NOT EXISTS idx_classroom_ip_ranges_group
    ON classroom_ip_ranges(group_id);
CREATE INDEX IF NOT EXISTS idx_teacher_devices_group
    ON classroom_teacher_devices(group_id);
CREATE INDEX IF NOT EXISTS idx_teacher_devices_ip
    ON classroom_teacher_devices(ip_address);
CREATE INDEX IF NOT EXISTS idx_teacher_devices_mac
    ON classroom_teacher_devices(mac_address);
CREATE INDEX IF NOT EXISTS idx_command_queue_mac_status_task
    ON command_queue(mac_address, status, task_id);
CREATE INDEX IF NOT EXISTS idx_command_queue_status_created
    ON command_queue(status, created_at);
CREATE INDEX IF NOT EXISTS idx_command_queue_status_delivered
    ON command_queue(status, delivered_at);
CREATE INDEX IF NOT EXISTS idx_command_queue_status_acked
    ON command_queue(status, acked_at);
CREATE INDEX IF NOT EXISTS idx_command_queue_admin_acked
    ON command_queue(is_admin_operation, acked_at);
CREATE INDEX IF NOT EXISTS idx_command_queue_mac_scmate_reset_status
    ON command_queue(mac_address, scmate_reset_status);
CREATE INDEX IF NOT EXISTS idx_command_queue_verification_status_deadline
    ON command_queue(verification_status, verification_deadline_at);
CREATE INDEX IF NOT EXISTS idx_pc_master_status_last_seen
    ON pc_master(status, last_seen);
CREATE INDEX IF NOT EXISTS idx_traffic_logs_timestamp
    ON traffic_logs("timestamp");
CREATE INDEX IF NOT EXISTS idx_traffic_logs_mac_timestamp
    ON traffic_logs(mac_address, "timestamp");
CREATE INDEX IF NOT EXISTS idx_dashboard_panels_dashboard_id
    ON dashboard_panels(dashboard_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_panels_type
    ON dashboard_panels(panel_type);
CREATE INDEX IF NOT EXISTS idx_scripts_sha256
    ON scripts(sha256);
CREATE INDEX IF NOT EXISTS idx_agent_auth_tokens_mac
    ON agent_auth_tokens(mac_address);
CREATE INDEX IF NOT EXISTS idx_agent_auth_tokens_revoked
    ON agent_auth_tokens(revoked);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor
    ON audit_logs(actor);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action
    ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created
    ON audit_logs(created_at);
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
CREATE INDEX IF NOT EXISTS ix_fd_job_artifact
    ON file_distribution_job(artifact_id);
CREATE INDEX IF NOT EXISTS idx_backup_dashboard_id
    ON dashboard_layout_backups(dashboard_id);
CREATE INDEX IF NOT EXISTS idx_backup_created_at
    ON dashboard_layout_backups(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_dashboard_created
    ON dashboard_layout_backups(dashboard_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operational_alerts_created_at
    ON operational_alerts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operational_alerts_ack_created
    ON operational_alerts(acknowledged, created_at DESC);

INSERT INTO dashboards (name, description, display_order)
SELECT '메인 대시보드', '기본 통합 관제 대시보드', 0
WHERE NOT EXISTS (
    SELECT 1 FROM dashboards WHERE name = '메인 대시보드'
);

