-- Normalize legacy epoch numeric timestamps to SQLite datetime text.
-- Handles both epoch seconds(10) and epoch millis(13+) stored as TEXT.

-- dashboards.created_at
UPDATE dashboards
SET created_at = datetime(
    CASE
        WHEN length(created_at) >= 13 THEN CAST(substr(created_at, 1, 10) AS INTEGER)
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND trim(created_at) <> ''
  AND created_at GLOB '[0-9]*'
  AND created_at NOT LIKE '%-%';

-- dashboard_panels.created_at
UPDATE dashboard_panels
SET created_at = datetime(
    CASE
        WHEN length(created_at) >= 13 THEN CAST(substr(created_at, 1, 10) AS INTEGER)
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND trim(created_at) <> ''
  AND created_at GLOB '[0-9]*'
  AND created_at NOT LIKE '%-%';

-- dashboard_panels.updated_at
UPDATE dashboard_panels
SET updated_at = datetime(
    CASE
        WHEN length(updated_at) >= 13 THEN CAST(substr(updated_at, 1, 10) AS INTEGER)
        ELSE CAST(updated_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE updated_at IS NOT NULL
  AND trim(updated_at) <> ''
  AND updated_at GLOB '[0-9]*'
  AND updated_at NOT LIKE '%-%';

-- dashboard_modules.created_at
UPDATE dashboard_modules
SET created_at = datetime(
    CASE
        WHEN length(created_at) >= 13 THEN CAST(substr(created_at, 1, 10) AS INTEGER)
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND trim(created_at) <> ''
  AND created_at GLOB '[0-9]*'
  AND created_at NOT LIKE '%-%';

-- command_queue time columns
UPDATE command_queue
SET created_at = datetime(
    CASE
        WHEN length(created_at) >= 13 THEN CAST(substr(created_at, 1, 10) AS INTEGER)
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND trim(created_at) <> ''
  AND created_at GLOB '[0-9]*'
  AND created_at NOT LIKE '%-%';

UPDATE command_queue
SET delivered_at = datetime(
    CASE
        WHEN length(delivered_at) >= 13 THEN CAST(substr(delivered_at, 1, 10) AS INTEGER)
        ELSE CAST(delivered_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE delivered_at IS NOT NULL
  AND trim(delivered_at) <> ''
  AND delivered_at GLOB '[0-9]*'
  AND delivered_at NOT LIKE '%-%';

UPDATE command_queue
SET acked_at = datetime(
    CASE
        WHEN length(acked_at) >= 13 THEN CAST(substr(acked_at, 1, 10) AS INTEGER)
        ELSE CAST(acked_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE acked_at IS NOT NULL
  AND trim(acked_at) <> ''
  AND acked_at GLOB '[0-9]*'
  AND acked_at NOT LIKE '%-%';

UPDATE command_queue
SET completed_at = datetime(
    CASE
        WHEN length(completed_at) >= 13 THEN CAST(substr(completed_at, 1, 10) AS INTEGER)
        ELSE CAST(completed_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE completed_at IS NOT NULL
  AND trim(completed_at) <> ''
  AND completed_at GLOB '[0-9]*'
  AND completed_at NOT LIKE '%-%';
