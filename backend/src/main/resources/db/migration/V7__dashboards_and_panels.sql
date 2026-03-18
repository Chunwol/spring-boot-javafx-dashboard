CREATE TABLE IF NOT EXISTS dashboards (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    display_order INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS dashboard_panels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dashboard_id INTEGER NOT NULL,
    panel_type TEXT NOT NULL,
    ref_group_id INTEGER,
    config_json TEXT,
    x REAL DEFAULT 0,
    y REAL DEFAULT 0,
    width REAL DEFAULT 300,
    height REAL DEFAULT 220,
    z_index INTEGER DEFAULT 0,
    visible BOOLEAN DEFAULT 1,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT,
    FOREIGN KEY (dashboard_id) REFERENCES dashboards(id) ON DELETE CASCADE,
    FOREIGN KEY (ref_group_id) REFERENCES classroom_groups(id)
);

CREATE INDEX IF NOT EXISTS idx_dashboard_panels_dashboard_id ON dashboard_panels(dashboard_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_panels_type ON dashboard_panels(panel_type);

INSERT INTO dashboards (name, description, display_order)
SELECT '메인 대시보드', '기본 통합 관제 대시보드', 0
WHERE NOT EXISTS (SELECT 1 FROM dashboards WHERE name = '메인 대시보드');
