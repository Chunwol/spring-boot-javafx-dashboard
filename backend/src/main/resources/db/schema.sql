CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    role TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    message TEXT NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id)
);

-- PC master table
CREATE TABLE IF NOT EXISTS pc_master (
    mac_address TEXT PRIMARY KEY,
    pc_name TEXT,
    ip_address TEXT,
    group_id INTEGER,
    status TEXT,
    last_seen TIMESTAMP
);

-- Traffic logs
CREATE TABLE IF NOT EXISTS traffic_logs (
    log_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    inbound_kbps INTEGER,
    outbound_kbps INTEGER,
    timestamp TIMESTAMP,
    FOREIGN KEY (mac_address) REFERENCES pc_master(mac_address)
);

-- Command queue
CREATE TABLE IF NOT EXISTS command_queue (
    task_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    command_type TEXT,
    script_url TEXT,
    is_executed BOOLEAN DEFAULT 0,
    is_admin_operation BOOLEAN DEFAULT 0,
    scmate_reset_status TEXT,
    scmate_reset_requested_at TEXT,
    scmate_reset_completed_at TEXT,
    FOREIGN KEY (mac_address) REFERENCES pc_master(mac_address)
);

-- Classroom groups
CREATE TABLE IF NOT EXISTS classroom_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TEXT
);

-- Client positions for dashboard layout
CREATE TABLE IF NOT EXISTS client_positions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    ip_address TEXT,
    group_id INTEGER,
    pos_x REAL,
    pos_y REAL
);

-- Dashboard modules for modular dashboard items
CREATE TABLE IF NOT EXISTS dashboard_modules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER,
    type TEXT NOT NULL,
    config TEXT,
    pos_x REAL,
    pos_y REAL,
    created_at TEXT
);

-- Dashboard widget items (for drag/drop widgets)
CREATE TABLE IF NOT EXISTS dashboard_widget_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER,
    type TEXT NOT NULL,
    config TEXT,
    x REAL,
    y REAL,
    width REAL,
    height REAL,
    visible BOOLEAN DEFAULT 1,
    created_at TEXT
);

-- Ensure pc_master.group_id is present as integer (SQLite flexible types). Add column if missing
PRAGMA foreign_keys=OFF;
BEGIN TRANSACTION;
ALTER TABLE pc_master RENAME TO _pc_master_old;

CREATE TABLE pc_master (
    mac_address TEXT PRIMARY KEY,
    pc_name TEXT,
    ip_address TEXT,
    group_id INTEGER,
    status TEXT,
    last_seen TIMESTAMP
);

INSERT INTO pc_master (mac_address, pc_name, ip_address, group_id, status, last_seen)
SELECT mac_address, pc_name, ip_address, CASE WHEN group_id GLOB '[0-9]*' THEN CAST(group_id AS INTEGER) ELSE NULL END, status, last_seen
FROM _pc_master_old;

DROP TABLE _pc_master_old;
COMMIT;
PRAGMA foreign_keys=ON;

CREATE INDEX IF NOT EXISTS idx_client_positions_group ON client_positions(group_id);

-- Note: existing pc_master.group_id was TEXT. Applications now treat it as numeric ID. SQLite is typeless so both coexist; migrate manually if needed.
