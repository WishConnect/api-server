-- 본문을 이미지 alt 로 대체했는지 기록한다
--
-- 포스터 한 장만 올린 공고를 alt 설명 덕분에 살렸지만, 거기 담긴 건 이미지 설명 한 줄뿐이라
-- 조건·제출서류는 여전히 비어 있다. 실측에서 이런 게 100건 중 4건이었다.
--
--   "…선발 안내 포스터. 2026년 7월 27일부터 7월 30일까지 서류 접수. …"
--
-- 나중에 OCR 을 붙일 때 이 건들이 대상인데, 상태가 PARSED 라 parse_status='IMAGE_ONLY' 로는
-- 골라낼 수 없다. 그래서 파싱 이력에 따로 남긴다.
--
--   -- OCR 대상 고르기
--   SELECT DISTINCT r.id, r.source_url
--     FROM notice_parse_log l JOIN raw_scholarship r ON r.id = l.raw_scholarship_id
--    WHERE l.body_from_image_alt
--   UNION
--   SELECT id, source_url FROM raw_scholarship WHERE parse_status = 'IMAGE_ONLY';
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

ALTER TABLE notice_parse_log
    ADD COLUMN IF NOT EXISTS body_from_image_alt BOOLEAN NOT NULL DEFAULT false;

COMMIT;

-- 검증
--   SELECT body_from_image_alt, count(*) FROM notice_parse_log GROUP BY 1;
