-- 관리자 감사 로그 action CHECK 갱신 (관리자 콘솔 W7·W8·W10)
--
-- 추가 값
--   AUDIT_RESTORE                 감사 로그 복구 자체를 새 기록으로 남긴다(W7)
--
-- 값 목록은 엔티티 enum(AdminAction)과 정확히 같아야 한다. 어긋나면 기동은 되고 그 작업의 감사 기록 INSERT 만
-- 실패한다(감사 기록 실패는 삼키므로 조용히 기록이 사라진다). MigrationCheckConstraintTest 가 대조한다.
--
-- 배포 전·후 어느 때 적용해도 기동에는 영향이 없지만, 적용 전에는 새 작업의 감사 기록이 남지 않는다. 배포 전에 적용한다.
BEGIN;

ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
    'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
    'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
    'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
    'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
    'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT', 'AUDIT_RESTORE'
));

COMMIT;

-- 검증
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'admin_audit_log_action_check';
--
-- 되돌리기 (새 값으로 남은 행이 있으면 CHECK 재생성이 실패한다. 행을 지우지 말고 앱을 내린 상태로 두거나,
-- 필요하면 아래처럼 새 값 행을 확인한 뒤 진행한다)
--   SELECT action, count(*) FROM admin_audit_log WHERE action IN ('AUDIT_RESTORE') GROUP BY action;
--   ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
--   ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check CHECK (action IN (
--       'EXCEL_IMPORT', 'SCHOLARSHIP_CREATE', 'SCHOLARSHIP_UPDATE', 'SCHOLARSHIP_AGGREGATE_UPDATE',
--       'SCHOLARSHIP_IMAGE_UPDATE', 'SCHOLARSHIP_DELETE', 'REPORT_RESOLVE', 'CONTENT_INQUIRY_RESOLVE',
--       'SYNC_TRIGGER', 'COLLECT_TRIGGER', 'CONDITION_EXTRACT_TRIGGER', 'CONDITION_REF_BACKFILL',
--       'ENRICH_TRIGGER', 'MERGE_DETECT_TRIGGER', 'MERGE_CANDIDATE_MANUAL_CREATE',
--       'SCHOLARSHIP_MERGE', 'SCHOLARSHIP_MERGE_REJECT'));
