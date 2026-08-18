-- ParseStatus 에 IMAGE_ONLY 추가 (raw_scholarship · notice_parse_log 양쪽 CHECK)
--
-- 공고 내용을 포스터 이미지 한 장으로만 올리는 게시판이 있다. 읽을 글자가 없어 건너뛸 수밖에
-- 없는데, 이건 "내용이 없다"가 아니라 "형식이 다르다"에 가깝다. 내용은 이미지 안에 다 있고
-- OCR 이나 이미지를 읽는 모델을 붙이면 살릴 수 있다.
--
-- 그냥 SKIPPED 로 섞어 두면 나중에 그 대상을 골라낼 방법이 없어서 상태를 나눈다.
-- 실측(2026-08-18): 홍익대 12건, 서울시립대 2건.
--
-- ⚠️ 배포 전에 적용할 것. 적용 전까지는 IMAGE_ONLY 를 쓰는 순간 23514 로 실패한다.
--    (validate 는 CHECK 를 보지 않아 기동은 되고 파싱 배치만 터지는 형태다 — V20260818_07 와 같은 사고)

BEGIN;

ALTER TABLE raw_scholarship DROP CONSTRAINT IF EXISTS raw_scholarship_parse_status_check;
ALTER TABLE raw_scholarship ADD CONSTRAINT raw_scholarship_parse_status_check
    CHECK (parse_status IN ('PENDING', 'PARSED', 'FAILED', 'SKIPPED', 'IMAGE_ONLY'));

ALTER TABLE notice_parse_log DROP CONSTRAINT IF EXISTS notice_parse_log_status_check;
ALTER TABLE notice_parse_log ADD CONSTRAINT notice_parse_log_status_check
    CHECK (status IN ('PENDING', 'PARSED', 'FAILED', 'SKIPPED', 'IMAGE_ONLY'));

COMMIT;

-- 검증
--   SELECT parse_status, count(*) FROM raw_scholarship GROUP BY 1;
--   -- 재파싱을 돌리면 IMAGE_ONLY 가 나타난다. OCR 대상은 이걸로 고른다:
--   SELECT id, source, source_url FROM raw_scholarship WHERE parse_status = 'IMAGE_ONLY';
