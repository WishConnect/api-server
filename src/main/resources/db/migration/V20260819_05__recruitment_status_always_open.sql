-- 마감일 없이 열려 있는 공고를 위한 상태를 만든다
--
-- 마감일이 없는 것이 정상인 공고가 있다.
--
--   "접수 기간 : 충원 시 마감"      "모집 마감"      자동선발(학생 신청 없음)
--
-- 지금은 이런 것들이 OPEN 으로 남는데, 날짜가 없으니 자동으로 닫히지 않는다. 운영에서
-- 183건이 그 상태였고 그중 20건이 실제 모집공고다. 나머지 163건은 선발 결과·행정 안내라
-- 애초에 모집 상태를 가질 이유가 없다.
--
-- 자동 판정을 포기하고 사람이 닫는 쪽을 택한다. 관리자 화면에 모아 보여준다.
--
-- ⚠️ 배포 전에 적용할 것. CHECK 제약은 ddl-auto: update 로 고쳐지지 않는다.

BEGIN;

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_recruitment_status_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_recruitment_status_check
    CHECK (recruitment_status IS NULL
           OR recruitment_status IN ('UPCOMING', 'OPEN', 'ALWAYS_OPEN', 'CLOSED'));

-- 1) 모집공고가 아닌 것은 목록에 있을 이유가 없다.
UPDATE scholarship
   SET recruitment_status = 'CLOSED'
 WHERE recruitment_status = 'OPEN'
   AND application_end_at IS NULL
   AND (notice_kind IN ('RESULT', 'GUIDE', 'NOT_SCHOLARSHIP') OR notice_kind IS NULL);

-- 2) 실제 모집공고인데 마감일이 없는 것만 ALWAYS_OPEN 으로 옮긴다. 관리자가 확인한다.
UPDATE scholarship
   SET recruitment_status = 'ALWAYS_OPEN'
 WHERE recruitment_status = 'OPEN'
   AND application_end_at IS NULL
   AND notice_kind = 'RECRUITMENT';

-- 3) 마감일이 지났는데 OPEN 인 것을 닫는다. resolveStatus 가 마감일을 안 보던 탓에
--    재파싱할 때마다 다시 생겼다. 코드도 같은 PR 에서 고친다.
UPDATE scholarship
   SET recruitment_status = 'CLOSED'
 WHERE recruitment_status = 'OPEN'
   AND application_end_at < now();

COMMIT;

-- 검증
--   SELECT recruitment_status, count(*) FROM scholarship GROUP BY 1 ORDER BY 2 DESC;
--   SELECT count(*) FROM scholarship WHERE recruitment_status='OPEN' AND application_end_at < now();
