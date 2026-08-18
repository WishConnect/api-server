-- v4 파싱: 장학금이 아닌 공고를 가려내고, 제출방식에도 근거를 요구한다
--
-- 1) notice_kind = 'NOT_SCHOLARSHIP'
--    성균관대 notice06.do 는 URL 자체가 장학 카테고리라 수집 단계에서 거를 수 없는데,
--    학교가 그 게시판에 아무거나 올린다. 7회차에서 두 건이 모집공고로 저장됐다.
--
--      4229  "가상현실(VR) 도구 사용 숙련도 향상 연구 실험 참여자 모집"
--            → 조건 7개를 완벽하게 뽑았다. "만 18세 이상", "VR 멀미가 있는 경우" …
--              파싱이 틀린 게 아니라 장학금이 아니라는 걸 아무도 안 걸렀다.
--      4230  "배터리학과 행정직원 모집"  → 채용공고
--
--    RESULT·GUIDE 와 같이 추천·목록에서 빠진다.
--
-- 2) submission_channel = 'THIRD_PARTY'
--    학생이 직접 못 내고 학교·기관을 거치는 유형이 기존 6개 값 어디에도 없어서 ONLINE 이 됐다.
--
--      1882  본문 "학교장 명의 전자공문으로만 신청 가능"
--                 "우편, 방문, 이메일 접수는 불가하고, 전자공문 제출이…"
--            → 저장값 ONLINE. 학생 입장에서 정반대 안내다.
--
-- 3) submission_evidence
--    기간·조건·자소서·면접에는 인용 근거를 받아 본문과 대조하는데 제출방식만 검증이 없었다.
--    그래서 지어낸 값이 그대로 저장됐다.
--
--      3906  본문이 없는 공고(첨부 3개뿐)인데 채널 EMAIL / "이메일로 서류 접수"
--            → 원문에 '이메일'·'@' 가 한 번도 나오지 않는다.
--
--    전수조사에서 296/299 는 정확했다. 문제는 정확도가 아니라 틀릴 때 막을 장치가 없는 것이다.
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.
--    CHECK 제약은 ddl-auto: update 로 고쳐지지 않는다(같은 실수를 두 번 했다).

BEGIN;

ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS submission_evidence TEXT;

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_notice_kind_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_notice_kind_check
    CHECK (notice_kind IS NULL
           OR notice_kind IN ('RECRUITMENT', 'RESULT', 'GUIDE', 'NOT_SCHOLARSHIP'));

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_submission_channel_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_submission_channel_check
    CHECK (submission_channel IS NULL
           OR submission_channel IN ('ONLINE', 'EMAIL', 'POST', 'VISIT', 'FAX', 'MIXED',
                                     'THIRD_PARTY'));

COMMIT;

-- 검증
--   SELECT notice_kind, count(*) FROM scholarship GROUP BY 1 ORDER BY 2 DESC;
--   SELECT submission_channel, count(*) FROM scholarship GROUP BY 1 ORDER BY 2 DESC;
