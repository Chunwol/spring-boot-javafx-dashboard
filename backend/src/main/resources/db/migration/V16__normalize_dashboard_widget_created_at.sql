-- dashboard_widget_items.created_at 정규화
-- 일부 레코드가 epoch millis 문자열로 저장되어 LocalDateTime 파싱 오류를 유발한다.
UPDATE dashboard_widget_items
SET created_at = datetime(
    CASE
        WHEN CAST(created_at AS INTEGER) > 2000000000
            THEN CAST(created_at AS INTEGER) / 1000
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND TRIM(CAST(created_at AS TEXT)) GLOB '[0-9]*';
