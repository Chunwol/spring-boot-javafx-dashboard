-- command_queue 시간 컬럼에 숫자형 epoch 값이 남아 있는 경우 다시 정규화

UPDATE command_queue
SET created_at = datetime(
    CASE
        WHEN length(trim(CAST(created_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(created_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(created_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND trim(CAST(created_at AS TEXT)) <> ''
  AND trim(CAST(created_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(created_at AS TEXT)) NOT LIKE '%-%';

UPDATE command_queue
SET delivered_at = datetime(
    CASE
        WHEN length(trim(CAST(delivered_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(delivered_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(delivered_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE delivered_at IS NOT NULL
  AND trim(CAST(delivered_at AS TEXT)) <> ''
  AND trim(CAST(delivered_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(delivered_at AS TEXT)) NOT LIKE '%-%';

UPDATE command_queue
SET acked_at = datetime(
    CASE
        WHEN length(trim(CAST(acked_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(acked_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(acked_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE acked_at IS NOT NULL
  AND trim(CAST(acked_at AS TEXT)) <> ''
  AND trim(CAST(acked_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(acked_at AS TEXT)) NOT LIKE '%-%';

UPDATE command_queue
SET completed_at = datetime(
    CASE
        WHEN length(trim(CAST(completed_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(completed_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(completed_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE completed_at IS NOT NULL
  AND trim(CAST(completed_at AS TEXT)) <> ''
  AND trim(CAST(completed_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(completed_at AS TEXT)) NOT LIKE '%-%';

UPDATE command_queue
SET scmate_reset_requested_at = datetime(
    CASE
        WHEN length(trim(CAST(scmate_reset_requested_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(scmate_reset_requested_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(scmate_reset_requested_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE scmate_reset_requested_at IS NOT NULL
  AND trim(CAST(scmate_reset_requested_at AS TEXT)) <> ''
  AND trim(CAST(scmate_reset_requested_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(scmate_reset_requested_at AS TEXT)) NOT LIKE '%-%';

UPDATE command_queue
SET scmate_reset_completed_at = datetime(
    CASE
        WHEN length(trim(CAST(scmate_reset_completed_at AS TEXT))) >= 13 THEN CAST(substr(trim(CAST(scmate_reset_completed_at AS TEXT)), 1, 10) AS INTEGER)
        ELSE CAST(trim(CAST(scmate_reset_completed_at AS TEXT)) AS INTEGER)
    END,
    'unixepoch'
)
WHERE scmate_reset_completed_at IS NOT NULL
  AND trim(CAST(scmate_reset_completed_at AS TEXT)) <> ''
  AND trim(CAST(scmate_reset_completed_at AS TEXT)) GLOB '[0-9]*'
  AND trim(CAST(scmate_reset_completed_at AS TEXT)) NOT LIKE '%-%';