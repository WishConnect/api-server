-- 원본과 끊긴 장학금의 연결을 복구한다
--
-- scholarship 과 raw_scholarship 은 한 방향으로만 이어져 있다(raw.scholarship_id). 그래서
-- 원본이 자기 장학금을 가리키지 않게 되면 그 장학금은 아무도 못 찾는 행으로 남는다.
-- 운영에서 164건이 그렇게 떠 있었고, 한 달 동안 아무도 몰랐다.
--
-- 어쩌다 생겼나
--   7월  옛 수집기가 정규식으로 scholarship 을 만들고 raw 도 따로 저장했다.
--        이때 raw.scholarship_id 를 채우지 않았다.
--   8월  "수집기는 raw 만, 정제는 LLM 이" 로 구조를 바꿨다. LLM 파싱이 그 raw 를 집었지만
--        본문이 없어 SKIPPED 로 끝났고, 연결은 끝내 채워지지 않았다.
--
-- 고아와 URL 이 같은 원본들의 상태가 이걸 그대로 보여준다.
--   SKIPPED 294 · IMAGE_ONLY 32  → 전부 미연결
--   PARSED   19                  → 전부 연결됨
--
-- 여기서는 <원본이 정확히 하나만 대응되는 70건>만 잇는다. 16건은 한 원본이 여러 고아에
-- 걸려(게시판 목록 URL 이 같은 경우) 어느 쪽이 맞는지 정할 수 없어 손대지 않는다.
--
-- 지우지 않는다. 고아 중 7건은 사용자가 스크랩했고 9건은 자소서를 쓰던 장학금이다.
-- 연결을 복구해 두면 나중에 OCR·첨부 파싱이 붙을 때 자연히 다시 파싱된다.

BEGIN;

WITH candidate AS (
    SELECT o.id AS orphan_id, MIN(r.id) AS raw_id
      FROM scholarship o
      JOIN raw_scholarship r
        ON r.source_url = o.homepage_url
       AND r.scholarship_id IS NULL
     WHERE NOT EXISTS (SELECT 1 FROM raw_scholarship r2 WHERE r2.scholarship_id = o.id)
     GROUP BY o.id
    HAVING COUNT(*) = 1
),
unambiguous AS (
    -- 한 원본이 여러 고아에 걸리면 어느 쪽이 맞는지 알 수 없다. 그건 제외한다.
    SELECT orphan_id, raw_id FROM candidate
     WHERE raw_id IN (SELECT raw_id FROM candidate GROUP BY raw_id HAVING COUNT(*) = 1)
)
UPDATE raw_scholarship r
   SET scholarship_id = u.orphan_id
  FROM unambiguous u
 WHERE r.id = u.raw_id;

COMMIT;

-- 검증
--   SELECT count(*) AS 남은고아 FROM scholarship s
--    WHERE NOT EXISTS (SELECT 1 FROM raw_scholarship r WHERE r.scholarship_id = s.id);
