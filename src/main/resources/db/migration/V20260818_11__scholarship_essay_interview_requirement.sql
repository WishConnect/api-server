-- 자기소개서·면접 필요 여부를 장학금에 붙인다
--
-- 기존에는 scholarship_document.is_essay 하나뿐이었고, 그것도 서류 이름에 키워드가 있는지만
-- 봤다("자기소개"·"자소서"·"학업계획"·"에세이"). 두 가지가 문제였다.
--
--   1) "수학계획서"·"지원동기서" 처럼 이름이 다르면 놓친다
--   2) 언급이 없으면 무조건 false 라, 자소서가 필요한 장학금이 필요 없다고 표시된다
--
-- 그리고 면접은 아예 판단하지 않았다. 면접은 서류 목록에 안 나오고 본문에만 있다 —
-- "1차 서류심사 후 2차 면접전형 진행" 처럼.
--
-- boolean 대신 3값 + NULL 로 둔다. "명시적으로 없음(NOT_REQUIRED)" 과 "언급 없음(NULL)" 은
-- 다르다. 앞은 화면에 "면접 없음" 이라고 적을 수 있지만, 뒤는 "공고 확인 필요" 다.
--
-- 근거 문장도 함께 저장한다. CONDITIONAL 은 "무슨 조건인지" 가 있어야 쓸모가 있고,
-- 사용자에게 우리 판단 대신 공고 원문을 보여주는 편이 신뢰를 산다.
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS essay_requirement     VARCHAR(20);
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS essay_evidence        TEXT;
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS interview_requirement VARCHAR(20);
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS interview_evidence    TEXT;

-- CHECK 를 건다. 값이 세 개뿐이고 앞으로 늘어날 일이 없다.
-- (늘리게 되면 이 제약도 함께 고쳐야 한다 — V20260818_07 에서 놓쳐 사고가 났다)
ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_essay_requirement_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_essay_requirement_check
    CHECK (essay_requirement IS NULL
           OR essay_requirement IN ('REQUIRED', 'CONDITIONAL', 'NOT_REQUIRED'));

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_interview_requirement_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_interview_requirement_check
    CHECK (interview_requirement IS NULL
           OR interview_requirement IN ('REQUIRED', 'CONDITIONAL', 'NOT_REQUIRED'));

COMMIT;

-- 검증
--   SELECT essay_requirement, count(*) FROM scholarship GROUP BY 1;
--   SELECT interview_requirement, count(*) FROM scholarship GROUP BY 1;
--   -- 재파싱 전에는 전부 NULL 이다.
