CREATE TABLE IF NOT EXISTS operational_alerts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    source_mac      TEXT NOT NULL,
    message         TEXT NOT NULL,
    severity        TEXT NOT NULL DEFAULT 'WARNING',
    alert_type      TEXT NOT NULL DEFAULT 'SYSTEM',
    acknowledged    BOOLEAN NOT NULL DEFAULT 0,
    acknowledged_at DATETIME,
    acknowledged_by TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_operational_alerts_created_at
    ON operational_alerts(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_operational_alerts_ack_created
    ON operational_alerts(acknowledged, created_at DESC);
