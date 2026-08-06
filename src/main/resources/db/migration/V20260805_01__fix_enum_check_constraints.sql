-- 옛 enum 이 남아 있는 CHECK 제약 2건 정정
--
-- 배경: 운영은 ddl-auto=validate, 그 이전 로컬/운영은 update 로 스키마를 만들었는데
-- Hibernate 의 update 는 컬럼·테이블만 추가할 뿐 **기존 CHECK 제약을 절대 고치지 않는다.**
-- 그래서 코드의 enum 을 바꿔도 DB 제약은 옛 값 그대로 남아, INSERT/UPDATE 시점에
-- "코드는 맞는데 23514 violates check constraint" 로 500 이 난다.
-- 2026-08-05 운영 로그 기준 essay_status_check 216건, second_major_type_check 24건.
--
-- 제약 이름은 Hibernate 가 생성한 것과 동일하게 유지한다. 이름이 달라지면 나중에
-- update 모드로 도는 환경에서 같은 제약이 중복 생성될 수 있다.
--
-- 적용 전 데이터 확인(2026-08-05 운영):
--   essay                  0건               -> 데이터 마이그레이션 불필요
--   user_profile.dual_major NULL 3 / MINOR 1 -> DOUBLE_MAJOR 값 없음, 마이그레이션 불필요
-- 위반 행이 있으면 ADD CONSTRAINT 가 실패하므로, 실패하면 데이터를 먼저 정리할 것.

BEGIN;

-- 1) essay.status
--    DB:     DRAFT / IN_PROGRESS / COMPLETED   (옛 enum)
--    엔티티: NOT_STARTED / IN_PROGRESS / COMPLETED  (EssayStatus, 2026-07-12 Notion 명세 반영)
--    -> 지원서 생성 직후 NOT_STARTED 로 INSERT 하는 경로가 전부 막혀 있었다.
ALTER TABLE essay DROP CONSTRAINT IF EXISTS essay_status_check;
ALTER TABLE essay ADD CONSTRAINT essay_status_check
    CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED'));

-- 2) user_profile.dual_major  (컬럼명은 dual_major, 필드명은 secondMajorType)
--    DB:     DOUBLE_MAJOR / MINOR
--    엔티티: DOUBLE / MINOR                      (SecondMajorType, PR #62 에서 코드만 정정됨)
ALTER TABLE user_profile DROP CONSTRAINT IF EXISTS user_profile_second_major_type_check;
ALTER TABLE user_profile ADD CONSTRAINT user_profile_second_major_type_check
    CHECK (dual_major IN ('DOUBLE', 'MINOR'));

COMMIT;
