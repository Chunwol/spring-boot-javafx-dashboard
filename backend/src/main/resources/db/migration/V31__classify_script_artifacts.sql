ALTER TABLE scripts ADD COLUMN artifact_kind VARCHAR(32) NOT NULL DEFAULT 'ADMIN_UPLOAD';
ALTER TABLE scripts ADD COLUMN owner_mac VARCHAR(64);

UPDATE scripts s
   SET artifact_kind = 'AGENT_COLLECTION'
 WHERE EXISTS (
       SELECT 1
         FROM command_queue c
        WHERE UPPER(c.command_type) = 'COLLECT_FILES'
          AND c.stdout LIKE ('%/agent/uploads/' || s.id || '/download%')
 );

CREATE INDEX IF NOT EXISTS idx_scripts_sha_kind ON scripts (sha256, artifact_kind);
