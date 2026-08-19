-- LLM 후보와 관리자가 직접 선택한 후보를 운영 화면에서 구분한다.
BEGIN;

ALTER TABLE scholarship_merge_candidate
    ADD COLUMN IF NOT EXISTS origin VARCHAR(20);

UPDATE scholarship_merge_candidate
SET origin = 'LLM'
WHERE origin IS NULL;

ALTER TABLE scholarship_merge_candidate
    ALTER COLUMN origin SET NOT NULL;

ALTER TABLE scholarship_merge_candidate
    ALTER COLUMN origin SET DEFAULT 'LLM';

ALTER TABLE scholarship_merge_candidate
    DROP CONSTRAINT IF EXISTS scholarship_merge_candidate_origin_check;
ALTER TABLE scholarship_merge_candidate
    ADD CONSTRAINT scholarship_merge_candidate_origin_check
    CHECK (origin IN ('LLM', 'MANUAL'));

COMMIT;

-- 검증
--   SELECT origin, COUNT(*) FROM scholarship_merge_candidate GROUP BY origin;
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conname = 'scholarship_merge_candidate_origin_check';
