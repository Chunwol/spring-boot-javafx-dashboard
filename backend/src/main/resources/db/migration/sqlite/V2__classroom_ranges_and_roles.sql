CREATE TABLE IF NOT EXISTS classroom_ip_ranges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL,
    cidr TEXT,
    start_ip TEXT,
    end_ip TEXT,
    description TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (group_id) REFERENCES classroom_groups (id)
);

CREATE INDEX IF NOT EXISTS idx_classroom_ip_ranges_group ON classroom_ip_ranges(group_id);

CREATE TABLE IF NOT EXISTS classroom_teacher_devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL,
    ip_address TEXT,
    mac_address TEXT,
    description TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (group_id) REFERENCES classroom_groups (id)
);

CREATE INDEX IF NOT EXISTS idx_teacher_devices_group ON classroom_teacher_devices(group_id);
CREATE INDEX IF NOT EXISTS idx_teacher_devices_ip ON classroom_teacher_devices(ip_address);
CREATE INDEX IF NOT EXISTS idx_teacher_devices_mac ON classroom_teacher_devices(mac_address);

ALTER TABLE pc_master ADD COLUMN role TEXT DEFAULT 'UNKNOWN';
