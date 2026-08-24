CREATE TABLE IF NOT EXISTS script_library_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    category TEXT NOT NULL,
    risk_level TEXT NOT NULL,
    requires_reboot INTEGER NOT NULL DEFAULT 0,
    requires_operator_mode INTEGER NOT NULL DEFAULT 0,
    powershell_requirement TEXT NOT NULL DEFAULT 'WINDOWS_POWERSHELL_5_1',
    success_marker TEXT,
    built_in_key TEXT,
    inverse_template_id INTEGER,
    active INTEGER NOT NULL DEFAULT 1,
    current_version INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_built_in_key ON script_library_template (built_in_key);

CREATE TABLE IF NOT EXISTS script_library_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id INTEGER NOT NULL REFERENCES script_library_template(id),
    version_no INTEGER NOT NULL,
    artifact_id INTEGER NOT NULL REFERENCES scripts(id),
    sha256 TEXT NOT NULL,
    created_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_version ON script_library_version (template_id, version_no);

CREATE TABLE IF NOT EXISTS script_library_execution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id INTEGER NOT NULL REFERENCES script_library_template(id),
    version_id INTEGER NOT NULL REFERENCES script_library_version(id),
    template_name_snapshot TEXT NOT NULL,
    version_no INTEGER NOT NULL,
    script_sha256 TEXT NOT NULL,
    status TEXT NOT NULL,
    run_mode TEXT NOT NULL,
    window_mode TEXT NOT NULL,
    arguments_text TEXT,
    target_count INTEGER NOT NULL,
    source_execution_id INTEGER,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_script_library_execution_created ON script_library_execution (created_at);

CREATE TABLE IF NOT EXISTS script_library_execution_target (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    execution_id INTEGER NOT NULL REFERENCES script_library_execution(id),
    mac_address TEXT NOT NULL,
    task_id INTEGER NOT NULL REFERENCES command_queue(task_id),
    created_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_script_library_execution_target ON script_library_execution_target (execution_id, mac_address);
