-- admin_audit_log.action CHECK 제약에 CONDITION_REF_BACKFILL 추가
--
-- V20260818_02 와 정확히 같은 실수를 반복했다. AdminAction enum 에 값을 더하면서
-- DB 의 CHECK 제약을 함께 고치지 않아, 새 관리자 API 를 부르는 순간 23514 로 터졌다.
--
-- 이번엔 증상이 더 나빴다. 감사 기록 실패가 트랜잭션을 rollback-only 로 만들어
-- 감사 로그만이 아니라 백필 작업 전체가 통째로 롤백됐다(응답은 500).
-- 즉 "기록은 실패해도 본래 동작은 살아야 한다"가 성립하지 않았다.
--
-- V20260816_01 의 주석에 "CHECK 를 일부러 걸지 않는다"고 적혀 있어 없는 줄 알았는데,
-- V20260818_02 가 나중에 추가했다. 테이블을 만든 마이그레이션만 보면 안 되고
-- 그 뒤 이력까지 봐야 한다.
--
-- 제약 이름은 Hibernate 가 생성한 것과 동일하게 유지한다.

BEGIN;

ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_action_check;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_action_check
    CHECK (action IN (
        'EXCEL_IMPORT',
        'SCHOLARSHIP_CREATE',
        'SCHOLARSHIP_UPDATE',
        'SCHOLARSHIP_DELETE',
        'REPORT_RESOLVE',
        'SYNC_TRIGGER',
        'COLLECT_TRIGGER',
        'CONDITION_EXTRACT_TRIGGER',
        'CONDITION_REF_BACKFILL',
        'ENRICH_TRIGGER',
        'MERGE_DETECT_TRIGGER',
        'SCHOLARSHIP_MERGE',
        'SCHOLARSHIP_MERGE_REJECT'
    ));

COMMIT;

-- 검증
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conname = 'admin_audit_log_action_check';
