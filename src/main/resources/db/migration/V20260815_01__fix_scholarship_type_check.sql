-- scholarship.scholarship_type CHECK 제약에 WORK_STUDY 추가
--
-- 배경: V20260805_01 과 같은 문제다. 운영은 ddl-auto=validate 지만 그 이전 update 로 만든
-- CHECK 제약이 남아 있고, Hibernate 의 update 는 기존 CHECK 제약을 절대 고치지 않는다.
-- 게다가 validate 는 CHECK 제약을 검사하지 않아 부팅은 정상적으로 되고, INSERT 시점에만
-- "23514 violates check constraint" 로 터진다. 그래서 배포 후에도 한동안 드러나지 않는다.
--
--   코드(ScholarshipType) : INTERNAL / EXTERNAL / WORK_STUDY
--   운영 DB              : INTERNAL / EXTERNAL          (2026-08-14 PR #77 에서 확인)
--
-- WORK_STUDY 는 2026-07-23 커밋 2ae8a8c(근로장학 카테고리 분리)에서 추가됐는데 마이그레이션이
-- 함께 들어가지 않았다. 2026-08-05 CHECK 제약 점검(V20260805_01) 때도 이 건은 누락됐다.
--
-- 영향: 근로장학 공고가 포함된 대학은 수집 배치가 그 트랜잭션째로 롤백된다. PR #77 의 신규
-- 수집기뿐 아니라 이미 운영 중인 UnivNoticeCollector 도 같은 값을 쓰므로 현재도 발생 중이다.
--
-- 제약 이름은 Hibernate 가 생성한 것과 동일하게 유지한다. 이름이 달라지면 update 모드로 도는
-- 환경에서 같은 제약이 중복 생성될 수 있다.
--
-- ── 적용 전 확인 ──────────────────────────────────────────────────────────
-- 1) 실제 제약 이름과 정의 (Hibernate 가 다른 이름을 붙였을 수 있다)
--      SELECT conname, pg_get_constraintdef(oid)
--        FROM pg_constraint
--       WHERE conrelid = 'scholarship'::regclass AND contype = 'c';
--
-- 2) 위반 행 존재 여부 — 있으면 ADD CONSTRAINT 가 실패하므로 데이터를 먼저 정리할 것
--      SELECT scholarship_type, COUNT(*)
--        FROM scholarship
--       GROUP BY scholarship_type;
-- ─────────────────────────────────────────────────────────────────────────

BEGIN;

ALTER TABLE scholarship DROP CONSTRAINT IF EXISTS scholarship_scholarship_type_check;
ALTER TABLE scholarship ADD CONSTRAINT scholarship_scholarship_type_check
    CHECK (scholarship_type IS NULL OR scholarship_type IN ('INTERNAL', 'EXTERNAL', 'WORK_STUDY'));

COMMIT;
