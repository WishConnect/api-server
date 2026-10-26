-- 실패 재처리 원본 "만료 처리"(숨기기) + 감사 action 2개
--
-- 파싱에 실패했거나 건너뛴 원본 중 기간을 모르는 것(파싱 실패라 마감일을 못 읽음)은 실패 재처리 목록에 영원히 남는다.
-- 관리자가 "모집기간 경과·장학금 공고 아님·중복·기타"로 만료 처리하면 목록·배지·대시보드에서 빠지고,
-- 매일 배치 파싱·LLM 재처리·공공데이터 동기화가 그 원본을 다시 처리하지 않는다. 되돌리기(restore)는 세 칸을 비운다.
--
-- parse_status enum 을 늘리지 않고 별도 nullable 컬럼으로 둔다. 파싱 상태(FAILED·SKIPPED·IMAGE_ONLY)와 "사람이 그만
-- 보기로 함"은 다른 축이라, 상태를 덮어쓰면 되돌릴 때 원래 상태를 잃는다.
--
--   dismissed_at      만료 처리 시각(NULL = 만료 처리 안 됨)
--   dismissed_reason  PERIOD_EXPIRED / NOT_SCHOLARSHIP / DUPLICATE / OTHER
--   dismissed_note    메모(기타면 필수, 200자)
--
-- 감사 action 추가: RAW_SCHOLARSHIP_DISMISS, RAW_SCHOLARSHIP_RESTORE (값 목록은 AdminAction enum 과 같아야 한다.
-- MigrationCheckConstraintTest 가 대조한다).
--
-- ⚠️ 배포 전에 적용할 것. 운영은 ddl-auto=validate 라 컬럼이 없으면 애플리케이션이 뜨지 않는다.
--    여러 번 실행해도 결과가 같다(IF NOT EXISTS / DROP CONSTRAINT IF EXISTS).
BEGIN;

ALTER TABLE raw_scholarship ADD COLUMN IF NOT EXISTS dismissed_at TIMESTAMP(6);
ALTER TABLE raw_scholarship ADD COLUMN IF NOT EXISTS dismissed_reason VARCHAR(30);
ALTER TABLE raw_scholarship ADD COLUMN IF NOT EXISTS dismissed_note VARCHAR(200);

-- 제약 이름을 Hibernate 생성 규칙과 맞춰, update 모드 환경에서 중복 생성되지 않게 한다.
ALTER TABLE raw_scholarship DROP CONSTRAINT IF EXISTS raw_scholarship_dismissed_reason_check;
ALTER TABLE raw_scholarship ADD CONSTRAINT raw_scholarship_dismissed_reason_check
    CHECK (dismissed_reason IN ('PERIOD_EXPIRED', 'NOT_SCHOLARSHIP', 'DUPLICATE', 'OTHER'));

COMMENT ON COLUMN raw_scholarship.dismissed_at IS '관리자가 실패 재처리 대상에서 만료 처리한 시각. NULL 이면 만료 처리 안 됨';
COMMENT ON COLUMN raw_scholarship.dismissed_reason IS '만료 처리 사유(PERIOD_EXPIRED/NOT_SCHOLARSHIP/DUPLICATE/OTHER)';
COMMENT ON COLUMN raw_scholarship.dismissed_note IS '만료 처리 메모(기타면 필수)';

ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
    'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
    'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
    'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
    'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
    'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE', 'SCHOLARSHIP_RESTORE',
    'MERGE_CANDIDATE_REOPEN', 'RAW_SCHOLARSHIP_DISMISS', 'RAW_SCHOLARSHIP_RESTORE'
));

COMMIT;

-- 검증
--   SELECT column_name, data_type, character_maximum_length FROM information_schema.columns
--    WHERE table_name = 'raw_scholarship' AND column_name LIKE 'dismissed%';
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conname IN ('raw_scholarship_dismissed_reason_check', 'admin_audit_log_action_check');
--   -- 적용 직후에는 0 이어야 한다
--   SELECT count(*) FROM raw_scholarship WHERE dismissed_at IS NOT NULL;
--
-- 되돌리기 (앱을 이 변경 이전 버전으로 내린 뒤 실행한다. 새 버전이 떠 있는 상태에서 컬럼을 지우면 validate·조회가 실패한다.
--          만료 처리 기록과 새 action 감사 행이 있으면 함께 사라지거나 CHECK 재생성이 실패하므로 먼저 확인한다)
--   SELECT count(*) FROM raw_scholarship WHERE dismissed_at IS NOT NULL;
--   SELECT action, count(*) FROM admin_audit_log
--    WHERE action IN ('RAW_SCHOLARSHIP_DISMISS', 'RAW_SCHOLARSHIP_RESTORE') GROUP BY action;
--   BEGIN;
--   ALTER TABLE raw_scholarship DROP CONSTRAINT IF EXISTS raw_scholarship_dismissed_reason_check;
--   ALTER TABLE raw_scholarship DROP COLUMN IF EXISTS dismissed_note;
--   ALTER TABLE raw_scholarship DROP COLUMN IF EXISTS dismissed_reason;
--   ALTER TABLE raw_scholarship DROP COLUMN IF EXISTS dismissed_at;
--   ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
--   ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
--       'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
--       'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
--       'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
--       'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
--       'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE', 'SCHOLARSHIP_RESTORE',
--       'MERGE_CANDIDATE_REOPEN'));
--   COMMIT;
