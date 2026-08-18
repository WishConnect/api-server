-- 건국대 출처 코드를 KONKUK_NOTICE → UNIV_KONKUK 으로 통일
--
-- 다른 대학은 전부 UNIV_ 접두사를 쓰는데 건국대만 예외였다. 이름이 안 맞는 것보다 실제 문제가
-- 있었다 — LLM 재파싱 대상을 source LIKE 'UNIV_%' 로 고르기 때문에 건국대 48건이 통째로
-- 대상에서 빠져 있었다. 이름을 맞추면 그 문제도 함께 해결된다.
--
-- ⚠️ 코드 배포보다 먼저 적용할 것.
--
-- 순서를 지켜야 하는 이유: 수집기는 UNIQUE(source, source_id) 로 "이미 받은 공지인가"를 판단한다.
-- 코드가 먼저 UNIV_KONKUK 으로 바뀐 상태에서 배치가 돌면 기존 48건을 못 알아보고 전부 새로
-- 수집해 중복이 생긴다. 데이터를 먼저 바꿔 두면 그대로 이어진다.
--
-- dedup_key 는 손대지 않는다. 이미 수집된 공지는 수집기가 건너뛰므로 옛 키를 다시 조회할 일이
-- 없고, 새 공식(sha256(source|source_id))과 충돌할 수도 없다.

BEGIN;

UPDATE raw_scholarship SET source = 'UNIV_KONKUK' WHERE source = 'KONKUK_NOTICE';
UPDATE scholarship     SET primary_source = 'UNIV_KONKUK' WHERE primary_source = 'KONKUK_NOTICE';

COMMIT;

-- 검증
--   SELECT source, count(*) FROM raw_scholarship WHERE source LIKE '%KONKUK%' GROUP BY 1;
--   -- UNIV_KONKUK 만 나와야 한다(KONKUK_NOTICE 는 0건).
