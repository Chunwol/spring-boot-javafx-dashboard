CREATE TABLE IF NOT EXISTS script_library_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    category VARCHAR(64) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    requires_reboot BOOLEAN NOT NULL DEFAULT FALSE,
    requires_operator_mode BOOLEAN NOT NULL DEFAULT FALSE,
    powershell_requirement VARCHAR(32) NOT NULL DEFAULT 'WINDOWS_POWERSHELL_5_1',
    success_marker VARCHAR(300),
    built_in_key VARCHAR(80),
    inverse_template_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    current_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_built_in_key ON script_library_template (built_in_key);

CREATE TABLE IF NOT EXISTS script_library_version (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES script_library_template(id),
    version_no INTEGER NOT NULL,
    artifact_id BIGINT NOT NULL REFERENCES scripts(id),
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_version ON script_library_version (template_id, version_no);

CREATE TABLE IF NOT EXISTS script_library_execution (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES script_library_template(id),
    version_id BIGINT NOT NULL REFERENCES script_library_version(id),
    template_name_snapshot VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    script_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    run_mode VARCHAR(16) NOT NULL,
    window_mode VARCHAR(16) NOT NULL,
    arguments_text VARCHAR(1000),
    target_count INTEGER NOT NULL,
    source_execution_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_script_library_execution_created ON script_library_execution (created_at DESC);

CREATE TABLE IF NOT EXISTS script_library_execution_target (
    id BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL REFERENCES script_library_execution(id),
    mac_address VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL REFERENCES command_queue(task_id),
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_execution_target ON script_library_execution_target (execution_id, mac_address);
