-- 배치 부분 실패 상태와 실패 상세 (관리자 콘솔 W6)
--
-- 1) admin_job_run.status 에 PARTIAL_FAILURE 를 추가한다.
--    끝까지 돌았지만 일부 단계·출처·원문이 실패한 실행. 예전에는 단계 예외만 WARNING 으로 남았고,
--    단계 안의 건별 실패(LLM 크레딧 소진으로 원문 수십 건 실패 등)는 SUCCEEDED 로 기록됐다.
--    WARNING 은 이미 쌓인 이력 때문에 남겨 둔다. 엔티티 enum(AdminJobStatus)과 값이 정확히 같아야 한다.
--
-- 2) admin_job_failure: 실행 안에서 어느 단계의 무엇(원문·장학금·출처)이 왜 실패했는지.
--    failure_type 은 엔티티 enum(AdminJobFailureType)과 정확히 같아야 한다. 값이 어긋나면 기동은 되고
--    배치 기록 INSERT 만 실패한다(2026-08 CHECK 불일치 500 과 같은 유형).
--
-- ⚠️ 배포 전에 적용한다. 엔티티 AdminJobFailure 가 새로 생겨 테이블이 없으면 validate 가 실패한다.

ALTER TABLE admin_job_run DROP CONSTRAINT IF EXISTS admin_job_run_status_check;
ALTER TABLE admin_job_run ADD CONSTRAINT admin_job_run_status_check
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'WARNING', 'PARTIAL_FAILURE', 'FAILED'));

CREATE TABLE IF NOT EXISTS admin_job_failure (
    id           BIGSERIAL PRIMARY KEY,
    job_run_id   BIGINT        NOT NULL REFERENCES admin_job_run (id),
    step         VARCHAR(80)   NOT NULL,
    target_type  VARCHAR(30)   NOT NULL,
    target_id    BIGINT,
    target_label VARCHAR(500),
    failure_type VARCHAR(30)   NOT NULL,
    reason       VARCHAR(1000),
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT admin_job_failure_failure_type_check
        CHECK (failure_type IN ('LLM_CREDIT', 'LLM_AUTH', 'LLM_ERROR', 'COLLECT', 'PARSE', 'SAVE',
                                'STEP_ERROR', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_admin_job_failure_run
    ON admin_job_failure (job_run_id, id);

CREATE INDEX IF NOT EXISTS idx_admin_job_failure_type_created
    ON admin_job_failure (failure_type, created_at DESC);

-- 되돌리기 (앱을 이전 버전으로 내린 뒤에 실행. PARTIAL_FAILURE 행이 있으면 CHECK 재생성이 실패하므로 먼저 바꾼다)
-- DROP TABLE IF EXISTS admin_job_failure;
-- UPDATE admin_job_run SET status = 'WARNING' WHERE status = 'PARTIAL_FAILURE';
-- ALTER TABLE admin_job_run DROP CONSTRAINT IF EXISTS admin_job_run_status_check;
-- ALTER TABLE admin_job_run ADD CONSTRAINT admin_job_run_status_check
--     CHECK (status IN ('RUNNING', 'SUCCEEDED', 'WARNING', 'FAILED'));
