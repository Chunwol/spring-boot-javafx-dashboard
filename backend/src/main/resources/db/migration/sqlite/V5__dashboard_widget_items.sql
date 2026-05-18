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
