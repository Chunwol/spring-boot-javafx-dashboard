CREATE TABLE IF NOT EXISTS agent_auth_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac_address TEXT NOT NULL UNIQUE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at TEXT DEFAULT (datetime('now')),
    last_used_at TEXT,
    revoked BOOLEAN DEFAULT 0,
    FOREIGN KEY (mac_address) REFERENCES pc_master(mac_address)
);

CREATE INDEX IF NOT EXISTS idx_agent_auth_tokens_mac ON agent_auth_tokens(mac_address);
