-- V10__dashboard_layout_backups.sql
-- Create table for dashboard layout backup/restore functionality

CREATE TABLE IF NOT EXISTS dashboard_layout_backups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dashboard_id INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    layout_data TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    is_automatic BOOLEAN NOT NULL DEFAULT 0,
    FOREIGN KEY (dashboard_id) REFERENCES dashboards(id) ON DELETE CASCADE
);

-- Create indexes for efficient querying
CREATE INDEX idx_backup_dashboard_id ON dashboard_layout_backups(dashboard_id);
CREATE INDEX idx_backup_created_at ON dashboard_layout_backups(created_at DESC);
CREATE INDEX idx_backup_dashboard_created ON dashboard_layout_backups(dashboard_id, created_at DESC);
