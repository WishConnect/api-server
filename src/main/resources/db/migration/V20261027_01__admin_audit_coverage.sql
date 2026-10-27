-- 작업 기록(admin_audit_log) 범위 확장 — 로그인·LLM 재처리·실패한 요청·동기화 기록 + 목록 필터용 인덱스
--
-- 1) actor_id NULL 허용
--    관리자 로그인 실패는 "누가" 를 모를 수 있다(없는 아이디, 비밀번호 오류). 그래도 시도 사실은 남겨야 한다.
--    성공한 작업은 지금처럼 늘 actor_id 가 있다.
-- 2) action CHECK 에 새 값 10개
--    ADMIN_LOGIN_SUCCESS / ADMIN_LOGIN_FAILURE / ADMIN_LOGIN_LOCKED / ADMIN_LOGOUT
--    LLM_PARSE_TRIGGER(대학 공지 LLM 파싱·재처리) / KOSAF_CONDITION_TRIGGER(공공데이터 조건 보강)
--    ADMIN_REQUEST_FAILED(관리자 쓰기 요청이 400·500 으로 실패) / MERGE_CANDIDATE_FAILED(일괄 병합·반려 중 실패한 후보)
--    INSIGHT_SYNC_TRIGGER / UNIVERSITY_SYNC_TRIGGER
--    값 목록은 AdminAction enum 과 정확히 같아야 한다(MigrationCheckConstraintTest 가 대조).
-- 3) 목록 필터 인덱스: 기간(created_at), 관리자(actor_id), 대상(target_type, target_id)
--
-- ⚠️ V20261026_01 다음에 적용한다(그 파일이 넣은 RAW_SCHOLARSHIP_* 값을 이 CHECK 가 그대로 포함한다).
-- 배포보다 먼저 적용할 것. 적용 전에 새 버전을 띄우면 기동은 되지만(validate 는 NULL 허용·CHECK·인덱스를 보지 않음)
-- 새 action 의 감사 기록 INSERT 가 CHECK 위반으로 조용히 빠진다. 이전 버전이 떠 있는 상태에서 적용해도 안전하다(추가만 한다).
-- 여러 번 실행해도 결과가 같다.
BEGIN;

ALTER TABLE admin_audit_log ALTER COLUMN actor_id DROP NOT NULL;

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
    'INSIGHT_SYNC_TRIGGER', 'UNIVERSITY_SYNC_TRIGGER'
));

-- 작업 기록 화면의 기간·관리자·대상 필터. 목록은 늘 id 최신순이라 id 를 뒤에 붙인다.
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created_at ON admin_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_actor ON admin_audit_log (actor_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_target ON admin_audit_log (target_type, target_id, id DESC);

COMMIT;

-- 검증
--   SELECT is_nullable FROM information_schema.columns WHERE table_name = 'admin_audit_log' AND column_name = 'actor_id';
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'admin_audit_log_action_check';
--   SELECT indexname FROM pg_indexes WHERE tablename = 'admin_audit_log';
--
-- 되돌리기 (앱을 이 변경 이전 버전으로 내린 뒤. actor_id 가 NULL 인 행이나 새 action 행이 있으면 실패하므로 먼저 확인한다.
--          그 행들은 지우지 말고 보관할지 먼저 정한다)
--   SELECT count(*) FROM admin_audit_log WHERE actor_id IS NULL;
--   SELECT action, count(*) FROM admin_audit_log WHERE action IN ('ADMIN_LOGIN_SUCCESS', 'ADMIN_LOGIN_FAILURE',
--       'ADMIN_LOGIN_LOCKED', 'ADMIN_LOGOUT', 'LLM_PARSE_TRIGGER', 'KOSAF_CONDITION_TRIGGER', 'ADMIN_REQUEST_FAILED',
--       'MERGE_CANDIDATE_FAILED', 'INSIGHT_SYNC_TRIGGER', 'UNIVERSITY_SYNC_TRIGGER') GROUP BY action;
--   BEGIN;
--   DROP INDEX IF EXISTS idx_admin_audit_log_created_at;
--   DROP INDEX IF EXISTS idx_admin_audit_log_actor;
--   DROP INDEX IF EXISTS idx_admin_audit_log_target;
--   ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
--   ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
--       'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
--       'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
--       'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
--       'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
--       'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE', 'SCHOLARSHIP_RESTORE',
--       'MERGE_CANDIDATE_REOPEN', 'RAW_SCHOLARSHIP_DISMISS', 'RAW_SCHOLARSHIP_RESTORE'));
--   ALTER TABLE admin_audit_log ALTER COLUMN actor_id SET NOT NULL;
--   COMMIT;
