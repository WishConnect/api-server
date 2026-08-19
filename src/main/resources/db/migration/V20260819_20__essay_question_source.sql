-- essay 에 문항 출처를 남긴다.
--
-- 배경: 맞춤 문항 생성 API 가 성공했는지 어디에도 기록하지 않아, 같은 지원서로 다시 호출하면
-- 이미 만든 문항을 지우고 새로 만들었다. 프론트 재시도·더블클릭·화면 재진입만으로
--   · LLM 을 또 부르고 (크레딧)
--   · 사용자 생성 한도를 또 깎고
--   · questionId 가 바뀌어 화면이 들고 있던 ID 가 무효가 된다
-- GENERATED 로 기록해 두면 다음 호출은 LLM 없이 현재 문항을 그대로 돌려줄 수 있다.
--
-- 기본값은 DEFAULT — 이미 있는 지원서는 전부 고정 문항으로 만들어졌다.
--
-- CHECK 제약을 함께 건다. Hibernate 의 ddl-auto=update 는 컬럼만 추가하고 CHECK 는 만들지
-- 않으며, 나중에 enum 값을 늘릴 때도 기존 제약을 고치지 않는다(V20260815_01 의 WORK_STUDY 사례).

BEGIN;

ALTER TABLE essay
    ADD COLUMN IF NOT EXISTS question_source VARCHAR(20) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE essay DROP CONSTRAINT IF EXISTS essay_question_source_check;
ALTER TABLE essay ADD CONSTRAINT essay_question_source_check
    CHECK (question_source IN ('DEFAULT', 'GENERATED'));

COMMIT;
