-- Normalize legacy epoch numeric timestamps for pc_master and traffic_logs.

-- pc_master.last_seen
UPDATE pc_master
SET last_seen = datetime(
    CASE
        WHEN length(last_seen) >= 13 THEN CAST(substr(last_seen, 1, 10) AS INTEGER)
        ELSE CAST(last_seen AS INTEGER)
    END,
    'unixepoch'
)
WHERE last_seen IS NOT NULL
  AND trim(last_seen) <> ''
  AND last_seen GLOB '[0-9]*'
  AND last_seen NOT LIKE '%-%';

-- traffic_logs.timestamp
UPDATE traffic_logs
SET timestamp = datetime(
    CASE
        WHEN length(timestamp) >= 13 THEN CAST(substr(timestamp, 1, 10) AS INTEGER)
        ELSE CAST(timestamp AS INTEGER)
    END,
    'unixepoch'
)
WHERE timestamp IS NOT NULL
  AND trim(timestamp) <> ''
  AND timestamp GLOB '[0-9]*'
  AND timestamp NOT LIKE '%-%';
