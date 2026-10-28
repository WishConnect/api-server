-- 새로 수집된 장학금의 검수 대기(보류) — 선작업·후노출
--
-- 1) scholarship.review_held_at
--    수집·파싱으로 새로 만들어진 장학금은 이 시각을 갖고 시작해 사용자에게 보이지 않는다.
--    일일 배치 끝에서 중복 검사를 마쳤고 · 대기 중인 중복 후보가 없고 · 데이터 이상이 없으면 NULL 로 풀어 공개한다.
--    중복 후보는 병합(남는 쪽)·반려(양쪽) 시, 그 밖의 건은 관리자가 [공개] 하면 풀린다.
--    기존 행은 NULL 로 두어 지금 보이는 장학금은 그대로 보인다. 관리자 수기 등록분도 NULL 로 만든다.
-- 2) admin_audit_log.action CHECK 에 SCHOLARSHIP_PUBLISH(검수 대기 장학금 공개) 추가
--    값 목록은 AdminAction enum 과 정확히 같아야 한다(MigrationCheckConstraintTest 가 대조).
--
-- ⚠️ 배포보다 먼저 적용할 것. 운영은 ddl-auto=validate 라 컬럼이 없으면 새 버전이 기동하지 못한다.
-- 이전 버전이 떠 있는 상태에서 적용해도 안전하다(컬럼 추가·CHECK 확장만 한다). 여러 번 실행해도 결과가 같다.
BEGIN;

ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS review_held_at TIMESTAMP NULL;

-- 보류 건은 소수다. 배치 해제·관리자 목록 필터가 보류 건만 빠르게 찾도록 부분 인덱스를 둔다.
CREATE INDEX IF NOT EXISTS idx_scholarship_review_held
    ON scholarship (review_held_at) WHERE review_held_at IS NOT NULL;

ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
    'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
    'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
    'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
    'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
    'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE', 'SCHOLARSHIP_RESTORE',
    'MERGE_CANDIDATE_REOPEN', 'RAW_SCHOLARSHIP_DISMISS', 'RAW_SCHOLARSHIP_RESTORE',
    'ADMIN_LOGIN_SUCCESS', 'ADMIN_LOGIN_FAILURE', 'ADMIN_LOGIN_LOCKED', 'ADMIN_LOGOUT',
    'LLM_PARSE_TRIGGER', 'KOSAF_CONDITION_TRIGGER', 'ADMIN_REQUEST_FAILED', 'MERGE_CANDIDATE_FAILED',
    'INSIGHT_SYNC_TRIGGER', 'UNIVERSITY_SYNC_TRIGGER', 'SCHOLARSHIP_PUBLISH'
));

COMMIT;

-- 검증
--   SELECT column_name, is_nullable FROM information_schema.columns
--     WHERE table_name = 'scholarship' AND column_name = 'review_held_at';
--   SELECT count(*) FROM scholarship WHERE review_held_at IS NOT NULL;   -- 적용 직후 0
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'admin_audit_log_action_check';
--
-- 되돌리기 (앱을 이 변경 이전 버전으로 내린 뒤. 보류 중이던 장학금은 컬럼이 사라지면 곧바로 공개된다.
--          SCHOLARSHIP_PUBLISH 행이 있으면 CHECK 재생성이 실패하므로 먼저 확인한다)
--   SELECT count(*) FROM admin_audit_log WHERE action = 'SCHOLARSHIP_PUBLISH';
--   BEGIN;
--   ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
--   ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
--       'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
--       'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
--       'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
--       'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
--       'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE', 'SCHOLARSHIP_RESTORE',
--       'MERGE_CANDIDATE_REOPEN', 'RAW_SCHOLARSHIP_DISMISS', 'RAW_SCHOLARSHIP_RESTORE',
--       'ADMIN_LOGIN_SUCCESS', 'ADMIN_LOGIN_FAILURE', 'ADMIN_LOGIN_LOCKED', 'ADMIN_LOGOUT',
--       'LLM_PARSE_TRIGGER', 'KOSAF_CONDITION_TRIGGER', 'ADMIN_REQUEST_FAILED', 'MERGE_CANDIDATE_FAILED',
--       'INSIGHT_SYNC_TRIGGER', 'UNIVERSITY_SYNC_TRIGGER'));
--   DROP INDEX IF EXISTS idx_scholarship_review_held;
--   ALTER TABLE scholarship DROP COLUMN IF EXISTS review_held_at;
--   COMMIT;
