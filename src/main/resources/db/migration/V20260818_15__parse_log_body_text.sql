-- 파서가 LLM 에 실제로 보낸 본문을 기록한다
--
-- 지금까지 파싱 결과를 검증할 때 raw_html 을 정규식으로 벗겨 "파서가 봤을 법한 본문"을
-- 재현해 대조했다. 그 재현이 틀려서 오진이 두 번 났다.
--
--   성균관대 4243 · 동국대 4178  "셀렉터가 빗나가 네비게이션이 들어갔다"
--     → 실제로는 본문 1,890자 / 271자를 제대로 뽑고 있었다. 내가 벗긴 텍스트가 페이지 전체였다.
--
--   한국외대 2041·3906·4148 등  "본문이 없는데 파싱됐다"
--     → 실제로는 포스터 alt 를 본문으로 쓴 건들이고, 이미 body_from_image_alt 로 표시돼 있었다.
--
-- 원본을 다시 벗기는 한 같은 오진이 반복된다. 파서가 무엇을 보고 판단했는지를 그대로 남겨
-- "본문에 없어서 못 뽑은 것"과 "본문에 있는데 놓친 것"을 확실히 가른다.
--
--   -- 마감일이 비었는데 본문에는 날짜가 있는 건 = 진짜 누락
--   SELECT l.raw_scholarship_id
--     FROM notice_parse_log l JOIN raw_scholarship r ON r.id = l.raw_scholarship_id
--     JOIN scholarship s ON s.id = r.scholarship_id
--    WHERE s.application_end_at IS NULL AND l.body_text ~ '\d{1,2}\s*[./월]\s*\d{1,2}';
--
-- 용량: 본문 평균 3천 자 × 600건 ≈ 2MB. TOAST 압축이 걸려 실제로는 더 작다.
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

ALTER TABLE notice_parse_log
    ADD COLUMN IF NOT EXISTS body_text TEXT;

COMMIT;

-- 검증
--   SELECT count(*) FILTER (WHERE body_text IS NOT NULL) AS 본문있음, count(*) FROM notice_parse_log;
