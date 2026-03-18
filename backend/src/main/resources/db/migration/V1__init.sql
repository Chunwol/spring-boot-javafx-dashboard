CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    message TEXT NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS pc_master (
    mac_address TEXT PRIMARY KEY,
    pc_name TEXT,
    ip_address TEXT,
    group_id INTEGER,
    status TEXT,
    last_seen TIMESTAMP
);

CREATE TABLE IF NOT EXISTS traffic_logs (
    log_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    inbound_kbps INTEGER,
    outbound_kbps INTEGER,
    timestamp TIMESTAMP,
    FOREIGN KEY (mac_address) REFERENCES pc_master(mac_address)
);

CREATE TABLE IF NOT EXISTS command_queue (
    task_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    command_type TEXT,
    script_url TEXT,
    is_executed BOOLEAN DEFAULT 0,
    FOREIGN KEY (mac_address) REFERENCES pc_master(mac_address)
);

CREATE TABLE IF NOT EXISTS classroom_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TEXT
);

CREATE TABLE IF NOT EXISTS client_positions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL,
    ip_address TEXT,
    group_id INTEGER,
    pos_x REAL,
    pos_y REAL
);

CREATE TABLE IF NOT EXISTS dashboard_modules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER,
    type TEXT NOT NULL,
    config TEXT,
    pos_x REAL,
    pos_y REAL,
    created_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_client_positions_group ON client_positions(group_id);
