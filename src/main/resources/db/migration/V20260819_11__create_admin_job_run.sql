CREATE TABLE IF NOT EXISTS admin_job_run (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(80) NOT NULL,
    trigger VARCHAR(20) NOT NULL,
    actor_id UUID,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    summary VARCHAR(2000),
    error_message VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT admin_job_run_status_check
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'WARNING', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_admin_job_run_started
    ON admin_job_run (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_job_run_status_started
    ON admin_job_run (status, started_at DESC);
