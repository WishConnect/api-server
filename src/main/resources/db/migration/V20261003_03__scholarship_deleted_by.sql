-- 장학금 내린 사람·사유 (관리자 콘솔 W8)
--
-- 왜 컬럼이 필요한가
--   공공데이터 동기화(updateFromApi)와 대학 공지 재파싱(applyLlmParsed)은 같은 공고가 다시 들어오면
--   deleted_at 을 NULL 로 풀어 되살린다(원본에서 잠시 빠졌다 돌아온 공고를 살리려는 의도된 동작).
--   그런데 이 경로가 관리자가 "내린" 공고까지 다음 날 되살리고 모집 상태를 다시 계산해 OPEN 으로 만들 수 있었다.
--   배치가 매 행마다 "관리자가 내린 것인가" 를 싸게 판단하려면 행에 표시가 있어야 한다.
--   감사 로그로 판단하는 방법은 감사 기록 실패를 삼키는 구조라(REQUIRES_NEW + catch) 기록이 빠지면 보호도 빠진다.
--
--   deleted_by    관리자 내리기·병합으로 내린 경우 그 관리자 ID. 수집 배치가 스스로 내린 경우는 NULL.
--   delete_reason 내린 사유(관리자 내리기는 필수). 병합이면 '병합: #N 로 합쳐짐'.
--
-- ⚠️ 배포 전에 적용한다. Scholarship 엔티티에 두 컬럼이 생겨 없으면 validate 가 실패한다.
BEGIN;

ALTER TABLE scholarship
    ADD COLUMN IF NOT EXISTS deleted_by UUID,
    ADD COLUMN IF NOT EXISTS delete_reason VARCHAR(500);

COMMENT ON COLUMN scholarship.deleted_by IS '관리자 내리기·병합으로 내린 관리자 ID. 수집 배치가 되살리지 않는다';
COMMENT ON COLUMN scholarship.delete_reason IS '내린 사유';

-- 기존 관리자 내리기 백필: 아직 내려져 있고, 마지막 SCHOLARSHIP_DELETE 감사 기록과 삭제 시각이 5분 안으로 맞는 행만.
-- (관리자가 내린 뒤 동기화가 되살렸다가 동기화가 다시 내린 행은 시각이 달라 제외된다 — 그건 배치 삭제다)
UPDATE scholarship s
SET deleted_by = a.actor_id,
    delete_reason = LEFT('관리자 내리기(사유 기록 이전): ' || COALESCE(a.detail, ''), 500)
FROM (
    SELECT DISTINCT ON (target_id) target_id, actor_id, detail, created_at
    FROM admin_audit_log
    WHERE action = 'SCHOLARSHIP_DELETE' AND target_type = 'SCHOLARSHIP' AND target_id IS NOT NULL
    ORDER BY target_id, id DESC
) a
WHERE s.id = a.target_id
  AND s.deleted_at IS NOT NULL
  AND s.deleted_by IS NULL
  AND ABS(EXTRACT(EPOCH FROM (s.deleted_at - a.created_at))) < 300;

-- 병합으로 내린 중복 쪽 백필(2026-08-20 이후 병합 승인이 모두 실패했으므로 대개 0건이다).
UPDATE scholarship s
SET deleted_by = c.reviewed_by,
    delete_reason = '병합: #' || c.primary_scholarship_id || ' 로 합쳐짐'
FROM scholarship_merge_candidate c
WHERE c.status = 'MERGED'
  AND c.duplicate_scholarship_id = s.id
  AND c.reviewed_by IS NOT NULL
  AND s.deleted_at IS NOT NULL
  AND s.deleted_by IS NULL;

COMMIT;

-- 확인
--   SELECT count(*) FILTER (WHERE deleted_by IS NOT NULL) AS 관리자_병합, count(*) FILTER (WHERE deleted_at IS NOT NULL) AS 삭제_전체
--   FROM scholarship;
--
-- 되돌리기 (앱을 이전 버전으로 내린 뒤 실행. 되돌리면 관리자가 내린 공고를 동기화가 다시 살릴 수 있다)
--   ALTER TABLE scholarship DROP COLUMN IF EXISTS delete_reason, DROP COLUMN IF EXISTS deleted_by;
