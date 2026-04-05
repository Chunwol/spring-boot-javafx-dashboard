-- scripts.created_at 정규화
-- 일부 레코드가 epoch seconds / millis 문자열로 저장되어
-- ScriptArtifact 조회 시 LocalDateTime 매핑 오류를 유발할 수 있다.

UPDATE scripts
SET created_at = datetime(
    CASE
        WHEN CAST(created_at AS INTEGER) > 2000000000
            THEN CAST(created_at AS INTEGER) / 1000
        ELSE CAST(created_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE created_at IS NOT NULL
  AND TRIM(CAST(created_at AS TEXT)) <> ''
  AND TRIM(CAST(created_at AS TEXT)) GLOB '[0-9]*';