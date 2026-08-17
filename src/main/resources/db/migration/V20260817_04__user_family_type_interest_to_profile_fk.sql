-- user_family_type · user_interest 의 user_id 를 users(uuid) 참조에서
-- user_profile(bigint) 참조로 바꾼다.
--
-- 배경: 커밋 f24ed62 "household 매핑을 user profile 기준으로 저장" 에서 엔티티가
-- @ManyToOne User(uuid) → @ManyToOne UserProfile(bigint) 로 바뀌었는데,
-- 컬럼 타입을 바꾸는 마이그레이션이 함께 올라오지 않았다.
--
-- 운영은 ddl-auto=validate 라 타입이 어긋나면 기동이 실패한다. 실제로 2026-08-17 배포에서
--   Schema-validation: wrong column type encountered in column [user_id]
--   in table [user_family_type]; found [uuid], but expecting [bigint]
-- 로 애플리케이션이 뜨지 못했다.
--
-- 전환 방식: 임시 컬럼에 user_profile.id 를 채운 뒤 원본 컬럼과 바꿔치기한다.
-- user_profile.user_id(uuid) 가 users.id 와 1:1 이라 매핑 키로 쓸 수 있다.
--
-- 적용 시점 실측: user_family_type 7행, user_interest 22행이며 전 건이 프로필과 매칭되어
-- 유실 없이 전환된다. 매칭되지 않는 행이 있다면 프로필 없는 사용자의 잔여 데이터이므로
-- NOT NULL 을 걸기 전에 확인이 필요하다(아래 검증 쿼리 참고).

BEGIN;

-- ── user_family_type ──────────────────────────────────────────────

ALTER TABLE user_family_type ADD COLUMN user_profile_id BIGINT;

UPDATE user_family_type uft
SET user_profile_id = up.id
FROM user_profile up
WHERE up.user_id = uft.user_id;

-- 프로필이 없는 사용자의 잔여 행. 애초에 온보딩을 거치지 않으면 생길 수 없는 데이터라
-- 정상 상태에서는 0건이다. 남아 있으면 NOT NULL 을 걸 수 없으므로 정리한다.
DELETE FROM user_family_type WHERE user_profile_id IS NULL;

ALTER TABLE user_family_type DROP COLUMN user_id;
ALTER TABLE user_family_type RENAME COLUMN user_profile_id TO user_id;
ALTER TABLE user_family_type ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE user_family_type
    ADD CONSTRAINT fk_user_family_type_user_profile
    FOREIGN KEY (user_id) REFERENCES user_profile (id);

CREATE INDEX IF NOT EXISTS idx_user_family_type_user
    ON user_family_type (user_id);

-- ── user_interest ─────────────────────────────────────────────────

ALTER TABLE user_interest ADD COLUMN user_profile_id BIGINT;

UPDATE user_interest ui
SET user_profile_id = up.id
FROM user_profile up
WHERE up.user_id = ui.user_id;

DELETE FROM user_interest WHERE user_profile_id IS NULL;

ALTER TABLE user_interest DROP COLUMN user_id;
ALTER TABLE user_interest RENAME COLUMN user_profile_id TO user_id;
ALTER TABLE user_interest ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE user_interest
    ADD CONSTRAINT fk_user_interest_user_profile
    FOREIGN KEY (user_id) REFERENCES user_profile (id);

CREATE INDEX IF NOT EXISTS idx_user_interest_user
    ON user_interest (user_id);

COMMIT;

-- 검증
--   SELECT table_name, column_name, data_type FROM information_schema.columns
--   WHERE table_name IN ('user_family_type','user_interest') AND column_name = 'user_id';
--   -- 둘 다 bigint 여야 한다.
