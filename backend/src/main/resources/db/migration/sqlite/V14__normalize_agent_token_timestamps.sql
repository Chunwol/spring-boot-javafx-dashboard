-- agent_auth_tokens timestamp 정규화
-- 일부 레코드가 epoch millis 문자열(예: 1773840007091)로 저장되어
-- LocalDateTime 매핑 시 sqlite-jdbc 파싱 오류를 유발한다.
-- 숫자형/숫자 문자열 값을 SQLite datetime 텍스트로 변환한다.

UPDATE agent_auth_tokens
SET issued_at = datetime(
    CASE
        WHEN CAST(issued_at AS INTEGER) > 2000000000
            THEN CAST(issued_at AS INTEGER) / 1000
        ELSE CAST(issued_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE issued_at IS NOT NULL
  AND TRIM(CAST(issued_at AS TEXT)) GLOB '[0-9]*';

UPDATE agent_auth_tokens
SET last_used_at = datetime(
    CASE
        WHEN CAST(last_used_at AS INTEGER) > 2000000000
            THEN CAST(last_used_at AS INTEGER) / 1000
        ELSE CAST(last_used_at AS INTEGER)
    END,
    'unixepoch'
)
WHERE last_used_at IS NOT NULL
  AND TRIM(CAST(last_used_at AS TEXT)) GLOB '[0-9]*';
