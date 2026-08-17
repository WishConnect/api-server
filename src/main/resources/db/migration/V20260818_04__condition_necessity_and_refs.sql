-- 조건에 필수/우대 구분과 마스터 참조를 붙인다.
--
-- 배경: ConditionMatcher 는 모든 조건을 하드 게이트로 취급한다(eligible = mismatchCount == 0).
-- 지금은 REGION_RESIDENCY 외 5종이 판정 불가로 빠져 이 규칙이 드러나지 않지만, 참조를 채워
-- 판정이 가능해지는 순간 "우대: 봉사활동 실적자" 같은 조건으로 자격 있는 학생이 탈락하기 시작한다.
-- 그래서 참조(scholarship_condition_ref)와 필수/우대(necessity)는 반드시 함께 들어가야 한다.
--
-- ⚠️ 배포 전에 적용해야 한다. 두 변경 모두 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

-- ── 1) 필수/우대 구분 ────────────────────────────────────────────────
--
-- 기존 890행은 REQUIRED 로 채운다. NULL 로 두면 지금 작동 중인 소득·성적·학년 게이트가
-- 통째로 풀려 "조건 미충족" 섹션이 비어버린다. 재파싱이 우대사항을 PREFERRED 로 정정한다.

ALTER TABLE scholarship_condition
    ADD COLUMN IF NOT EXISTS necessity VARCHAR(20);

UPDATE scholarship_condition SET necessity = 'REQUIRED' WHERE necessity IS NULL;

ALTER TABLE scholarship_condition ALTER COLUMN necessity SET NOT NULL;
ALTER TABLE scholarship_condition ALTER COLUMN necessity SET DEFAULT 'REQUIRED';

ALTER TABLE scholarship_condition DROP CONSTRAINT IF EXISTS scholarship_condition_necessity_check;
ALTER TABLE scholarship_condition ADD CONSTRAINT scholarship_condition_necessity_check
    CHECK (necessity IN ('REQUIRED', 'PREFERRED'));

-- ── 2) 마스터 참조 ──────────────────────────────────────────────────
--
-- 한 조건에 여러 개가 붙는다. "기초생활수급자 또는 차상위계층" 처럼 OR 로 묶인 요건이 흔하고,
-- 판정은 사용자 값 집합과의 교집합으로 한다. 행을 나눠 저장하면 mismatchCount 규칙상
-- AND 로 뒤집혀 의미가 반대가 된다.
--
-- ref_id  : 테이블 기반 마스터의 PK (region · family_type · interest)
-- ref_code: enum 기반 마스터의 이름 (MajorCategory · EnrollmentStatus)
-- 둘 중 하나만 채워진다.

CREATE TABLE IF NOT EXISTS scholarship_condition_ref (
    condition_id BIGINT NOT NULL
        REFERENCES scholarship_condition (id) ON DELETE CASCADE,
    ref_id       BIGINT,
    ref_code     VARCHAR(40),

    CONSTRAINT ck_condition_ref_one_of
        CHECK (num_nonnulls(ref_id, ref_code) = 1)
);

-- 조건 하나의 참조를 한꺼번에 읽는다(매칭 시 교집합 계산).
CREATE INDEX IF NOT EXISTS idx_condition_ref_condition
    ON scholarship_condition_ref (condition_id);

-- 특정 지역·가정형태를 요구하는 장학금을 역으로 찾을 때 쓴다.
CREATE INDEX IF NOT EXISTS idx_condition_ref_value
    ON scholarship_condition_ref (ref_id) WHERE ref_id IS NOT NULL;

-- ── 3) 쓰이지 않던 단일 ref_id 컬럼 정리 ─────────────────────────────
--
-- 설계만 되고 890행 전부 NULL 이었다. 집합 참조로 대체되므로 이름만 바꿔 남긴다.
-- (엔티티는 legacy_ref_id 로 매핑해 두었다 — validate 통과를 위해 컬럼 자체는 유지한다)

ALTER TABLE scholarship_condition RENAME COLUMN ref_id TO legacy_ref_id;

COMMIT;

-- 검증
--   SELECT necessity, count(*) FROM scholarship_condition GROUP BY necessity;
--   -- 적용 직후에는 REQUIRED 만 나와야 한다.
--   SELECT count(*) FROM scholarship_condition_ref;   -- 재파싱 전에는 0
