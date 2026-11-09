-- 비교 실행 이력 전용. 운영 장학금/일정 테이블은 변경하지 않는다.
-- 신규 JAR 기동 전에 적용한다(ddl-auto=validate).
BEGIN;
SET LOCAL lock_timeout = '5s';
CREATE TABLE IF NOT EXISTS scholarship_pipeline_run (
    id UUID PRIMARY KEY,
    raw_scholarship_id BIGINT,
    scholarship_id BIGINT,
    source VARCHAR(50) NOT NULL,
    pipeline_version VARCHAR(40) NOT NULL,
    prompt_version VARCHAR(20),
    model_id VARCHAR(60),
    input_hash VARCHAR(64) NOT NULL,
    input_json TEXT NOT NULL,
    baseline_json TEXT,
    candidate_json TEXT,
    response_json TEXT,
    status VARCHAR(25) NOT NULL,
    error_message VARCHAR(300),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT scholarship_pipeline_run_status_check CHECK
        (status IN ('CAPTURED', 'COMPARED', 'SKIPPED', 'LLM_FAILED', 'INVALID_RESPONSE'))
);
CREATE INDEX IF NOT EXISTS ix_pipeline_run_created ON scholarship_pipeline_run (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_pipeline_run_hash_status ON scholarship_pipeline_run (input_hash, status);
COMMIT;

-- 앱 롤백 시 이 테이블을 유지한다. 비교 이력이므로 정제 데이터 복구에는 영향을 주지 않는다.
