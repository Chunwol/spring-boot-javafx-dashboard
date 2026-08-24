ALTER TABLE script_library_template
    ALTER COLUMN created_at TYPE TEXT USING created_at::text,
    ALTER COLUMN updated_at TYPE TEXT USING updated_at::text;

ALTER TABLE script_library_version
    ALTER COLUMN created_at TYPE TEXT USING created_at::text;

ALTER TABLE script_library_execution
    ALTER COLUMN created_at TYPE TEXT USING created_at::text,
    ALTER COLUMN updated_at TYPE TEXT USING updated_at::text;

ALTER TABLE script_library_execution_target
    ALTER COLUMN created_at TYPE TEXT USING created_at::text;
