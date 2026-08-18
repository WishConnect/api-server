-- 연세대 수집분에서 장학이 아닌 공지를 목록에서 내린다
--
-- 연세대는 한 게시판에 장학·학사·일반이 섞여 올라온다. 목록 URL 에 분류 필터(findClSeq=257)를
-- 걸어 뒀지만 그건 목록에만 적용되고, 상단 고정 공지 등은 필터를 무시하고 노출된다.
-- 그래서 수집분 42건 중 18건이 장학금이 아니었다 — 셔틀버스 운행 시간표, 수강신청 안내,
-- 등록금 분할납부 안내, 졸업신청 안내 같은 것들이다.
--
-- 수집기는 앞으로 상세 페이지의 분류 표기를 보고 거른다(같은 PR). 이 마이그레이션은 이미
-- 들어와 있는 것을 치운다.
--
-- 지우지 않고 deleted_at 으로 내린다. 스크랩·자소서가 걸려 있을 수 있고(가능성은 낮지만
-- 되돌릴 수 없다), 무엇보다 나중에 "무엇이 왜 빠졌는지" 확인할 수 있어야 한다.
--
-- 판별은 본문에 찍힌 분류 표기로 한다. 연세대 스킨은 제목 아래에 "분류 [학사]" 형태로 넣는다.

BEGIN;

-- 1) 정제 목록에서 내린다
UPDATE scholarship s
   SET deleted_at = now()
 WHERE s.deleted_at IS NULL
   AND s.id IN (
       SELECT r.scholarship_id FROM raw_scholarship r
        WHERE r.source = 'UNIV_YONSEI'
          AND r.scholarship_id IS NOT NULL
          AND r.raw_html LIKE '%분류%'
          AND r.raw_html NOT LIKE '%[장학]%');

-- 2) 원본은 SKIPPED 로 바꿔 재파싱 대상에서 뺀다(다시 수집되지도 않는다)
UPDATE raw_scholarship r
   SET parse_status = 'SKIPPED',
       parse_error  = '장학 분류가 아닌 공지입니다.',
       scholarship_id = NULL
 WHERE r.source = 'UNIV_YONSEI'
   AND r.raw_html LIKE '%분류%'
   AND r.raw_html NOT LIKE '%[장학]%';

COMMIT;

-- 검증
--   SELECT parse_status, count(*) FROM raw_scholarship WHERE source='UNIV_YONSEI' GROUP BY 1;
--   SELECT count(*) FROM raw_scholarship r JOIN scholarship s ON s.id=r.scholarship_id
--    WHERE r.source='UNIV_YONSEI' AND s.deleted_at IS NULL;   -- 장학 공지만 남아야 한다
