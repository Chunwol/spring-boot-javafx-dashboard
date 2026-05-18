-- Normalize remaining legacy epoch numeric timestamps.

-- classroom_groups.created_at
UPDATE classroom_groups
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

-- classroom_ip_ranges.created_at
UPDATE classroom_ip_ranges
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

-- classroom_teacher_devices.created_at
UPDATE classroom_teacher_devices
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

-- users.created_at
UPDATE users
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

-- scripts.created_at / uploaded_at (schema variations)
UPDATE scripts
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

-- dashboard_widget_items.created_at
UPDATE dashboard_widget_items
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

-- audit_logs.created_at
UPDATE audit_logs
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
