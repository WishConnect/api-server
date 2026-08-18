-- 공지 종류 · 통합 공고 여부 · 제출 방식
--
-- ■ notice_kind — 장학 게시판에 모집 공고만 올라오지 않는다
--
-- 실측 30건 중 4건이 "장학복지팀 전화번호 변경 안내", "장학금 수혜용 계좌 등록 안내" 같은
-- 것이었다. 장학금으로 목록에 올라와 있었다. 연세대는 게시판이 분류를 표기해 줘서 수집 단계에서
-- 걸렀지만, 건국대처럼 표기가 없는 곳은 본문을 읽어야 안다.
--
-- 부수 효과로 지표가 정확해진다. RESULT(선발 결과·지급 안내)는 모집기간이 없는 게 정상인데,
-- 지금은 분모에 들어가 "기간 채움률 50%" 처럼 실제보다 나쁘게 나온다. 모집 공고만 세면 76% 다.
--
-- ■ is_combined — 한 공고에 여러 장학금
--
-- "교외통합장학금" 처럼 표로 7~8개를 나열하는 공고가 있다. 조건을 성실히 뽑으면 서로 다른
-- 장학금의 요건이 한 행에 섞이는데, eligible = (mismatchCount == 0) 규칙상 전부 AND 로 걸린다.
-- 실측에서 조건 11개가 뭉쳐 아무도 통과할 수 없는 상태가 됐다 — 시각디자인전공이면서 선교사
-- 자녀인 학생만 지원 가능해진다. 26명을 뽑는 큰 공고가 추천에서 통째로 사라졌다.
--
-- 조건은 사실대로 REQUIRED 로 저장하고 판정에서만 제외한다. 나중에 장학금별로 행을 나누게 되면
-- 데이터를 고칠 필요 없이 예외만 걷어내면 된다.
--
-- ■ submission_method — 온라인이냐 우편·방문이냐
--
-- 마감이 "온라인 자정" 인지 "오전 10시 도착분에 한함" 인지에 따라 준비가 완전히 달라진다.
-- 상세 응답에 자리는 이미 있었는데 늘 null 이었다.
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS notice_kind       VARCHAR(20);
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS is_combined       BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS submission_method  VARCHAR(300);
-- 화면 배지·필터용. 구체적인 안내 문구는 submission_method 에 있다.
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS submission_channel VARCHAR(20);

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_submission_channel_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_submission_channel_check
    CHECK (submission_channel IS NULL
           OR submission_channel IN ('ONLINE', 'EMAIL', 'POST', 'VISIT', 'FAX', 'MIXED'));

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_notice_kind_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_notice_kind_check
    CHECK (notice_kind IS NULL OR notice_kind IN ('RECRUITMENT', 'RESULT', 'GUIDE'));

COMMIT;

-- 검증
--   SELECT notice_kind, count(*) FROM scholarship GROUP BY 1;
--   SELECT count(*) FROM scholarship WHERE is_combined;      -- 재파싱 전에는 0
--   -- 모집 공고만 놓고 본 기간 채움률:
--   SELECT count(*) FILTER (WHERE application_end_at IS NOT NULL) || ' / ' || count(*)
--     FROM scholarship WHERE notice_kind = 'RECRUITMENT';
