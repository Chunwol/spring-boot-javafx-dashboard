-- Convert legacy PostgreSQL timestamp columns to TEXT for converter-backed entities.
-- Some environments were bootstrapped before pg baseline was aligned with
-- LenientLocalDateTimeStringConverter-backed columns.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('agent_auth_tokens', 'issued_at'),
            ('agent_auth_tokens', 'last_used_at'),
            ('command_queue', 'created_at'),
            ('command_queue', 'delivered_at'),
            ('command_queue', 'acked_at'),
            ('command_queue', 'completed_at'),
            ('command_queue', 'scmate_reset_requested_at'),
            ('command_queue', 'scmate_reset_completed_at'),
            ('command_queue', 'verification_started_at'),
            ('command_queue', 'verification_deadline_at'),
            ('command_queue', 'verification_observed_offline_at'),
            ('command_queue', 'verification_completed_at'),
            ('dashboard_widget_items', 'created_at'),
            ('scripts', 'created_at'),
            ('operational_alerts', 'acknowledged_at'),
            ('operational_alerts', 'created_at')
        ) AS t(tbl, col)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = r.tbl
              AND c.column_name = r.col
              AND c.data_type <> 'text'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ALTER COLUMN %I TYPE TEXT USING CASE WHEN %2$I IS NULL THEN NULL ELSE %2$I::text END',
                r.tbl, r.col
            );
        END IF;
    END LOOP;
END $$;
