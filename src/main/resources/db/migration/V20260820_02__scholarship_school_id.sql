-- 장학금이 "어느 학교 공고인지"를 구조로 남긴다.
--
-- 그동안은 scholarship.provider(문자열)를 프로필 학교명과 견줬다. 표기가 조금만 달라도
-- ("인천대" / "인천대학교" / "국립인천대학교") 대조가 빗나갔고, 타입(INTERNAL)으로 거르는 방법도
-- 통하지 않았다 — 학교를 짚는 공고 17건 중 16건이 WORK_STUDY(9)·EXTERNAL(7) 로 분류돼 있었다.
-- 그래서 인천대에 다니지 않는 사용자에게 인천대 장학금이 추천됐다.
--
-- school_id 가 NULL 인 것은 "학교와 무관한 공고"가 아니라 "모른다"는 뜻이다.
-- 재단·기업 공고(공공데이터)가 대부분 여기에 해당하고, 추천 관문은 값이 있을 때만 건다.
--
-- ⚠️ 운영은 ddl-auto: validate 라 이 SQL 을 배포보다 먼저 적용해야 한다.
--    적용 전에 새 코드가 뜨면 컬럼이 없어 애플리케이션이 기동하지 못한다.

BEGIN;

ALTER TABLE scholarship
    ADD COLUMN IF NOT EXISTS school_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_scholarship_school'
    ) THEN
        ALTER TABLE scholarship
            ADD CONSTRAINT fk_scholarship_school
            FOREIGN KEY (school_id) REFERENCES school (id);
    END IF;
END $$;

-- 관문이 school_id 로 필터하므로 인덱스를 둔다. NULL 이 대다수라 부분 인덱스로 충분하다.
CREATE INDEX IF NOT EXISTS idx_scholarship_school_id
    ON scholarship (school_id) WHERE school_id IS NOT NULL;

-- 기존 데이터 백필: provider 를 학교 마스터 이름과 견준다.
-- 공백을 지우고 꼬리의 "대학교"/"대학"을 "대"로 맞춰 표기 차이를 흡수한다.
-- 매칭되는 학교가 정확히 한 곳일 때만 채운다 — 여러 곳이면 잘못 지정하는 쪽이 더 나쁘다.
WITH normalized AS (
    SELECT s.id AS scholarship_id,
           (SELECT sc.id
              FROM school sc
             WHERE regexp_replace(regexp_replace(sc.name, '\s', '', 'g'), '(대학교|대학)$', '대')
                 = regexp_replace(regexp_replace(s.provider, '\s', '', 'g'), '(대학교|대학)$', '대')
             LIMIT 2) AS matched_school_id,
           (SELECT count(*)
              FROM school sc
             WHERE regexp_replace(regexp_replace(sc.name, '\s', '', 'g'), '(대학교|대학)$', '대')
                 = regexp_replace(regexp_replace(s.provider, '\s', '', 'g'), '(대학교|대학)$', '대')) AS match_count
      FROM scholarship s
     WHERE s.school_id IS NULL
       AND s.provider IS NOT NULL
       AND s.provider <> ''
)
UPDATE scholarship s
   SET school_id = n.matched_school_id
  FROM normalized n
 WHERE s.id = n.scholarship_id
   AND n.match_count = 1;

COMMIT;

-- 적용 결과 확인
-- SELECT count(*) FILTER (WHERE school_id IS NOT NULL) AS 학교지정, count(*) AS 전체 FROM scholarship;
-- SELECT sc.name, count(*) FROM scholarship s JOIN school sc ON sc.id = s.school_id GROUP BY 1 ORDER BY 2 DESC;
