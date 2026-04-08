-- 대용량 파일 배포 Job/Manifest/청크/상태 관리용 테이블 설계 (초안)

-- 배포 Job(파일 단위)
CREATE TABLE IF NOT EXISTS file_distribution_job (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    upload_id       TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    total_chunks    INTEGER NOT NULL,
    status          TEXT NOT NULL, -- QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    created_at      DATETIME NOT NULL,
    completed_at    DATETIME,
    cancelled_at    DATETIME,
    manifest_path   TEXT, -- 매니페스트 JSON 파일 경로
    final_sha256    TEXT, -- 전체 파일 해시
    error_message   TEXT
);

-- 청크 업로드 상태
CREATE TABLE IF NOT EXISTS file_chunk (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id          INTEGER NOT NULL REFERENCES file_distribution_job(id),
    chunk_index     INTEGER NOT NULL,
    chunk_hash      TEXT NOT NULL,
    size_bytes      INTEGER NOT NULL,
    status          TEXT NOT NULL, -- UPLOADED, VERIFIED, FAILED
    uploaded_at     DATETIME NOT NULL,
    verified_at     DATETIME,
    error_message   TEXT
);

-- 에이전트별 배포 Wave/상태/결과
CREATE TABLE IF NOT EXISTS file_distribution_agent_status (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id          INTEGER NOT NULL REFERENCES file_distribution_job(id),
    agent_id        TEXT NOT NULL, -- PC/에이전트 식별자
    wave            INTEGER NOT NULL,
    status          TEXT NOT NULL, -- QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    started_at      DATETIME,
    completed_at    DATETIME,
    error_message   TEXT
);

-- 배포 이력/이벤트(취소/재시도/오류 등)
CREATE TABLE IF NOT EXISTS file_distribution_event (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id          INTEGER NOT NULL REFERENCES file_distribution_job(id),
    event_type      TEXT NOT NULL, -- CREATED, CANCELLED, RETRIED, COMPLETED, FAILED, AGENT_RESULT, ...
    detail_json     TEXT,
    created_at      DATETIME NOT NULL
);
