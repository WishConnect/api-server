-- 재파싱이 새 행을 만들면서 남은 옛 행을 정리한다
--
-- 같은 원본이 더 나은 장학금 행을 가리키고 있는데, 옛 행이 목록에 그대로 노출되고 있었다.
-- 19건 전부 연세대·홍익대이고, 새 행과 나란히 놓으면 차이가 분명하다.
--
--   고아 70   "UNIV_YONSEI 공고 943655"   조건 0   →  새 625  "학사경고자 학사지도 안내"     조건 0
--   고아 400  "공지사항 공유팝업 열기…"    조건 3   →  새 619  "동산장학회 장학생 신청"      조건 7
--   고아 127  "…연세소식단: Blue"         조건 2   →  새 639  "…연세소식단: Blue"          조건 4
--
-- 옛 행은 제목이 폴백이거나 셀렉터 실패 잔재이고, 조건도 같거나 적다.
--
-- 지우기 전에 사용자 데이터를 확인했다 — 19건 모두 스크랩 0건, 자소서 0건이다.
-- (다른 고아에는 스크랩 7건·자소서 9건이 물려 있어 그쪽은 손대지 않는다.)

BEGIN;

CREATE TEMP TABLE superseded AS
SELECT DISTINCT o.id
  FROM scholarship o
  JOIN raw_scholarship r
    ON r.source_url = o.homepage_url
   AND r.scholarship_id IS NOT NULL
 WHERE NOT EXISTS (SELECT 1 FROM raw_scholarship r2 WHERE r2.scholarship_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM scrap x WHERE x.scholarship_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM essay x WHERE x.scholarship_id = o.id);

DELETE FROM scholarship_condition      WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship_document       WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship_timeline       WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship_recommendation WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship_report         WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM notification_dispatch_log  WHERE scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship_merge_candidate
 WHERE primary_scholarship_id IN (SELECT id FROM superseded)
    OR duplicate_scholarship_id IN (SELECT id FROM superseded);
DELETE FROM scholarship                WHERE id IN (SELECT id FROM superseded);

COMMIT;

-- 검증
--   SELECT count(*) FROM scholarship s
--    WHERE NOT EXISTS (SELECT 1 FROM raw_scholarship r WHERE r.scholarship_id = s.id);
