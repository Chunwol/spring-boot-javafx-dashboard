-- 사용자 UI 설정 저장용 테이블 (key-value)
CREATE TABLE IF NOT EXISTS user_settings (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL DEFAULT ''
);
