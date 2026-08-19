ALTER TABLE scholarship
    ADD COLUMN IF NOT EXISTS always_open_reviewed_at TIMESTAMP;

COMMENT ON COLUMN scholarship.always_open_reviewed_at
    IS '관리자가 상시모집 원문을 마지막으로 확인한 시각';
