-- collect_artifact 테이블 생성 (파일 수집 아티팩트 임시 저장용)
-- 이 테이블은 V4 마이그레이션에서 script_artifact 테이블로 통합되어 제거됩니다.
CREATE TABLE IF NOT EXISTS collect_artifact (
    id          BIGSERIAL PRIMARY KEY,
    agent_mac   VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(512) NOT NULL,
    file_path   VARCHAR(1024),
    file_size   BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
