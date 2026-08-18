-- 행동 기록에 섹션과 점수식 판을 남긴다
--
-- 지금은 노출이 전부 한 덩어리로 섞인다. 화면이 마감임박 배너·교내·추천·조건미충족 네 덩이로
-- 나뉘어 있는데 어디서 눌렸는지가 없어서, 배너가 먹히는지 교내 섹션이 쓸모 있는지를 따로 볼 수 없다.
--
-- 점수식 판도 함께 남긴다. 판을 올려도 좋아졌는지 나빠졌는지 말할 근거가 없었고, 같은 기간에
-- 두 판이 섞이면 비교 자체가 불가능하다.
--
-- 이벤트 종류도 셋 늘었다.
--   APPLY_CLICK  공고 원문으로 나갔다. 우리 화면 밖에서 지원하는 장학금이 많아
--                ESSAY_START 만으로는 전환이 안 잡힌다.
--   DISMISS      추천에서 치웠다. 유일한 부정 신호다 — 지금까지는 좋아한 흔적만 쌓여서
--                무엇을 내려야 하는지 알 수 없었다.
--   UNSCRAP      담았다가 물렀다. SCRAP 을 상쇄한다.
--
-- ⚠️ 배포 전에 적용할 것. 엔티티에 매핑되므로 없으면 validate 가 실패한다.

BEGIN;

ALTER TABLE scholarship_event ADD COLUMN IF NOT EXISTS section        VARCHAR(30);
ALTER TABLE scholarship_event ADD COLUMN IF NOT EXISTS ranker_version VARCHAR(20);

ALTER TABLE scholarship_event DROP CONSTRAINT IF EXISTS scholarship_event_event_type_check;
ALTER TABLE scholarship_event ADD CONSTRAINT scholarship_event_event_type_check
    CHECK (event_type IN ('IMPRESSION', 'CLICK', 'SCRAP', 'ESSAY_START',
                          'APPLY_CLICK', 'DISMISS', 'UNSCRAP'));

COMMIT;

-- 검증
--   SELECT event_type, section, count(*) FROM scholarship_event GROUP BY 1,2 ORDER BY 3 DESC;
