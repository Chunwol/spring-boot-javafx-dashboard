-- 월간 운영 보고서 (분기 정리 전 집계 스냅샷)
-- 월 1일 03:00에 전월 데이터 기준 1행 INSERT
CREATE TABLE IF NOT EXISTS monthly_reports (
    id              BIGSERIAL PRIMARY KEY,
    year_month      VARCHAR(7)    NOT NULL UNIQUE,  -- 'YYYY-MM'
    total_pcs       INTEGER       NOT NULL,
    avg_online_pcs  INTEGER       NOT NULL,
    peak_online_pcs INTEGER       NOT NULL,
    online_ratio    DECIMAL(5,2)  NOT NULL,         -- 0.00 ~ 100.00
    total_commands  INTEGER       NOT NULL,
    failed_commands INTEGER       NOT NULL,
    total_alerts    INTEGER       NOT NULL,
    total_traffic_gb DECIMAL(12,2) NOT NULL,
    summary_json    TEXT,                            -- 상세(강의실별 등) JSON
    generated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_monthly_reports_year_month ON monthly_reports(year_month);
