-- 추천 노출·클릭 기록
--
-- 지금은 추천이 맞는지 알 방법이 없다. 점수식을 바꿔도 좋아졌는지 나빠졌는지 말할 근거가 없어
-- 고치는 것마다 취향 논쟁이 된다. 노출 대비 클릭을 보려면 노출부터 남아야 한다.
--
-- FK 를 걸지 않는다. 세 가지 이유다 —
--   1) 노출은 화면 한 번에 수십 건이라, 넣을 때마다 대상 조회가 따라붙으면 비싸다
--   2) 중복 장학금을 병합하면 기록이 함께 지워진다
--   3) 탈퇴한 회원의 기록도 사라져 과거 실험 결과가 흔들린다
-- 기록은 대상이 없어져도 남아야 하는 데이터다(admin_audit_log 와 같은 이유).
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 테이블이 없으면 앱이 기동되지 않는다.

CREATE TABLE IF NOT EXISTS scholarship_event (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        UUID        NOT NULL,
    scholarship_id BIGINT      NOT NULL,
    -- ScholarshipEventType. CHECK 를 걸지 않는다 — ddl-auto=update 는 기존 CHECK 를 고치지 못해
    -- enum 값을 추가하면 "코드는 맞는데 23514 로 500" 이 나는 사고가 이미 두 번 있었다.
    event_type     VARCHAR(20) NOT NULL,
    -- 노출 당시의 값이다. 나중에 다시 계산하면 그때의 점수식이 아니라 지금 점수식이 나온다.
    position       INTEGER,
    match_score    INTEGER,
    view_mode      VARCHAR(30),
    created_at     TIMESTAMP   NOT NULL
);

-- "이 장학금의 노출 대비 클릭" 을 재는 기본 질의.
CREATE INDEX IF NOT EXISTS idx_scholarship_event_target
    ON scholarship_event (scholarship_id, event_type, created_at);

-- 기간별 집계(점수식 변경 전후 비교).
CREATE INDEX IF NOT EXISTS idx_scholarship_event_created
    ON scholarship_event (created_at);

-- 검증
--   SELECT event_type, count(*) FROM scholarship_event GROUP BY event_type;
