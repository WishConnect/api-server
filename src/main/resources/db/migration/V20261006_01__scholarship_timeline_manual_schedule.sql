-- 선발 일정 수기 입력 + 모집기간 수기 고정
--
-- 왜 필요한가
--   scholarship_timeline 은 제목·날짜 두 칸뿐이라 "12월 중 예정" 같은 미정 일정, 하루짜리 발표일과 기간을
--   구분할 수 없었고, 어떤 단계(접수/서류 발표/면접…)인지도 몰라 사용자 화면이 접수 줄을 언제 대신 만들지
--   정할 수 없었다. 관리자 콘솔에서 일정을 수기로 넣으려면 단계·날짜 모양·근거를 함께 저장해야 한다.
--
--   scholarship.period_locked 는 관리자가 고친 모집기간을 공공데이터 동기화(updateFromApi)와 대학 공지
--   재파싱(applyLlmParsed)이 다음 날 덮어쓰지 않게 하는 표시다. true 면 두 경로가 기간만 건너뛴다.
--
-- 운영 상태(조사 시점): scholarship_timeline 0행. 아래 백필 UPDATE 는 로컬·검증 DB 에 행이 있을 때만 의미가 있다.
--
-- ⚠️ 배포 전에 적용한다. ScholarshipTimeline·Scholarship 엔티티에 컬럼이 생겨 없으면 validate 가 실패한다.
BEGIN;

ALTER TABLE scholarship_timeline
    ADD COLUMN IF NOT EXISTS stage_code VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN IF NOT EXISTS date_type  VARCHAR(10) NOT NULL DEFAULT 'SINGLE',
    ADD COLUMN IF NOT EXISTS date_text  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS note       VARCHAR(200),
    ADD COLUMN IF NOT EXISTS evidence   TEXT,
    ADD COLUMN IF NOT EXISTS origin     VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP   NOT NULL DEFAULT now();

-- 기존 행이 있으면 날짜 모양 CHECK 를 통과하도록 맞춘다(운영 0행이라 대개 0건).
UPDATE scholarship_timeline
SET date_type = 'TBD', date_text = COALESCE(date_text, '일정 미정')
WHERE start_date IS NULL AND end_date IS NULL;

UPDATE scholarship_timeline
SET date_type = 'SINGLE', start_date = COALESCE(start_date, end_date), end_date = COALESCE(start_date, end_date)
WHERE (start_date IS NULL) <> (end_date IS NULL);

UPDATE scholarship_timeline
SET date_type = 'RANGE', start_date = LEAST(start_date, end_date), end_date = GREATEST(start_date, end_date)
WHERE start_date IS NOT NULL AND end_date IS NOT NULL AND start_date <> end_date;

ALTER TABLE scholarship_timeline DROP CONSTRAINT IF EXISTS scholarship_timeline_stage_code_check;
ALTER TABLE scholarship_timeline ADD CONSTRAINT scholarship_timeline_stage_code_check
    CHECK (stage_code IN ('APPLICATION', 'DOC_REVIEW', 'DOC_RESULT', 'INTERVIEW', 'FINAL_RESULT', 'PAYMENT', 'CUSTOM'));

ALTER TABLE scholarship_timeline DROP CONSTRAINT IF EXISTS scholarship_timeline_date_type_check;
ALTER TABLE scholarship_timeline ADD CONSTRAINT scholarship_timeline_date_type_check
    CHECK (date_type IN ('SINGLE', 'RANGE', 'TBD'));

ALTER TABLE scholarship_timeline DROP CONSTRAINT IF EXISTS scholarship_timeline_date_shape_check;
ALTER TABLE scholarship_timeline ADD CONSTRAINT scholarship_timeline_date_shape_check
    CHECK (
        (date_type = 'TBD' AND start_date IS NULL AND end_date IS NULL AND date_text IS NOT NULL)
        OR (date_type = 'SINGLE' AND start_date IS NOT NULL AND start_date = end_date)
        OR (date_type = 'RANGE' AND start_date IS NOT NULL AND end_date IS NOT NULL AND start_date <= end_date)
    );

ALTER TABLE scholarship_timeline DROP CONSTRAINT IF EXISTS scholarship_timeline_origin_check;
ALTER TABLE scholarship_timeline ADD CONSTRAINT scholarship_timeline_origin_check
    CHECK (origin IN ('MANUAL', 'LLM', 'KOSAF'));

CREATE INDEX IF NOT EXISTS idx_scholarship_timeline_scholarship ON scholarship_timeline (scholarship_id);

COMMENT ON COLUMN scholarship_timeline.stage_code IS '단계 코드. APPLICATION 행이 있으면 사용자 화면이 모집기간 접수 줄을 만들지 않는다';
COMMENT ON COLUMN scholarship_timeline.date_type IS 'SINGLE(하루)·RANGE(기간)·TBD(미정, date_text 만)';
COMMENT ON COLUMN scholarship_timeline.date_text IS '미정일 때 표시 문구(예: 12월 중 예정)';
COMMENT ON COLUMN scholarship_timeline.note IS '비고(18:00 마감, 트랙 구분 등)';
COMMENT ON COLUMN scholarship_timeline.evidence IS '공고 원문 근거 문장';
COMMENT ON COLUMN scholarship_timeline.origin IS '행을 만든 주체. 지금은 MANUAL 만 쓴다';

ALTER TABLE scholarship
    ADD COLUMN IF NOT EXISTS period_locked BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN scholarship.period_locked IS '관리자가 고친 모집기간. true 면 동기화·재파싱이 기간을 덮어쓰지 않는다';

COMMIT;

-- 확인
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--   WHERE conrelid = 'scholarship_timeline'::regclass AND contype = 'c';
--   SELECT count(*) FILTER (WHERE period_locked) AS 잠김, count(*) AS 전체 FROM scholarship;
--
-- 되돌리기 (앱을 이전 버전으로 내린 뒤 실행. 수기로 넣은 일정의 단계·미정 문구·근거와 기간 고정 표시가 사라진다)
--   BEGIN;
--   ALTER TABLE scholarship DROP COLUMN IF EXISTS period_locked;
--   DROP INDEX IF EXISTS idx_scholarship_timeline_scholarship;
--   ALTER TABLE scholarship_timeline
--       DROP CONSTRAINT IF EXISTS scholarship_timeline_origin_check,
--       DROP CONSTRAINT IF EXISTS scholarship_timeline_date_shape_check,
--       DROP CONSTRAINT IF EXISTS scholarship_timeline_date_type_check,
--       DROP CONSTRAINT IF EXISTS scholarship_timeline_stage_code_check;
--   ALTER TABLE scholarship_timeline
--       DROP COLUMN IF EXISTS updated_at,
--       DROP COLUMN IF EXISTS origin,
--       DROP COLUMN IF EXISTS evidence,
--       DROP COLUMN IF EXISTS note,
--       DROP COLUMN IF EXISTS date_text,
--       DROP COLUMN IF EXISTS date_type,
--       DROP COLUMN IF EXISTS stage_code;
--   COMMIT;
