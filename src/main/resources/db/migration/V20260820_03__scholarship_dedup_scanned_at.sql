-- 중복 후보 탐지를 "최신 N건 다시 보기"에서 "안 본 것부터 한 바퀴"로 바꾼다.
--
-- 그동안 배치는 매일 id 내림차순 30건만 검사했다. 그래서 두 가지가 동시에 일어났다.
--   · 오래된 공고는 영영 검사되지 않는다.
--   · 중복 쌍이 <같은 창에 함께 담겨야만> 잡힌다. 그런데 실제 중복은 대부분 며칠 차이로
--     들어온 출처가 다른 쌍(공공데이터 vs 대학공지)이라 그 조건이 거의 성립하지 않는다.
--     같은 출처 중복은 dedup_key 가 애초에 막고 있다.
-- 결과적으로 중복 판정 큐가 계속 비어 있었다.
--
-- 이 컬럼이 있으면 안 본 것부터 가져가 전체가 한 바퀴 돈다. 새 공고가 들어오면 그 공고가
-- 속한 묶음이 다시 대상이 되므로, 오래된 쪽과 짝이 맞는지도 그때 확인된다.
--
-- NULL = "아직 한 번도 검사하지 않음". 기존 행을 전부 NULL 로 두는 것이 의도다 —
-- 처음 몇 번의 배치가 밀린 분량을 나눠 처리한다.
--
-- ⚠️ 운영은 ddl-auto: validate 라 이 SQL 을 배포보다 먼저 적용해야 한다.

BEGIN;

ALTER TABLE scholarship
    ADD COLUMN IF NOT EXISTS dedup_scanned_at TIMESTAMP;

-- 배치가 "안 본 것"을 찾는 데 쓴다. 한 바퀴 돌고 나면 NULL 이 사라지므로 부분 인덱스로 둔다.
CREATE INDEX IF NOT EXISTS idx_scholarship_dedup_unscanned
    ON scholarship (id) WHERE dedup_scanned_at IS NULL;

COMMIT;

-- 적용 결과 확인
-- SELECT count(*) FILTER (WHERE dedup_scanned_at IS NULL) AS 미검사, count(*) AS 전체 FROM scholarship;
