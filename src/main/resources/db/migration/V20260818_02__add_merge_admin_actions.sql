-- admin_audit_log.action CHECK 제약에 병합 관련 액션 3개 추가
--
-- 배경: V20260805_01 · V20260814_01 과 같은 유형의 문제다. Hibernate 의 ddl-auto=update 는
-- 기존 CHECK 제약을 고치지 않으므로, AdminAction enum 에 값을 더해도 DB 제약은 옛 값 그대로
-- 남는다. 그 상태로 감사 로그를 남기면 INSERT 가 23514 로 실패한다.
--
-- 병합 승인은 사용자 데이터(스크랩·자소서)를 다른 장학금으로 옮기는 파괴적 작업이다.
-- 감사 로그 INSERT 가 실패하면 "누가 무엇을 병합했는지"가 남지 않으므로 반드시 함께 반영해야 한다.
--
-- 추가: MERGE_DETECT_TRIGGER / SCHOLARSHIP_MERGE / SCHOLARSHIP_MERGE_REJECT
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
        'ENRICH_TRIGGER',
        'MERGE_DETECT_TRIGGER',
        'SCHOLARSHIP_MERGE',
        'SCHOLARSHIP_MERGE_REJECT'
    ));

COMMIT;
