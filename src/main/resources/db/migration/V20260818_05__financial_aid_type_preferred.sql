-- 지원 성격(FINANCIAL_AID_TYPE) 조건을 우대로 내린다.
--
-- 배경: V20260818_04 는 기존 890행을 전부 REQUIRED 로 채웠다. 그때는 그게 맞았다 —
-- 작동 중이던 소득·성적·학년 게이트를 보존해야 했기 때문이다.
--
-- 그런데 FINANCIAL_AID_TYPE 은 "등록금 지원"·"생활비 지원"·"해외연수" 같은 지원 성격이지
-- 자격요건이 아니다. Phase 3 에서 이 유형에 마스터 참조를 붙여 판정이 켜지면,
-- ConditionMatcher 의 eligible = (mismatchCount == 0) 규칙 때문에
-- "생활비 지원" 장학금이 관심분야에 생활비를 넣지 않은 학생을 전부 탈락시킨다.
--
-- 지금 미리 내려둔다. 판정이 켜지기 전에 고쳐야 사고가 안 난다.

BEGIN;

UPDATE scholarship_condition
   SET necessity = 'PREFERRED'
 WHERE condition_type = 'FINANCIAL_AID_TYPE'
   AND necessity <> 'PREFERRED';

COMMIT;

-- 검증
--   SELECT condition_type, necessity, count(*)
--     FROM scholarship_condition
--    GROUP BY condition_type, necessity
--    ORDER BY condition_type;
--   -- FINANCIAL_AID_TYPE 은 PREFERRED 만 나와야 한다.
