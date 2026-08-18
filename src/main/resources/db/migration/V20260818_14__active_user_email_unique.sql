-- 탈퇴 회원의 이메일 재사용과 로그인 방식별 계정 공존 허용
--
-- 애플리케이션은 사용자를 (login_type, provider_id) 기준으로 구분하고,
-- 탈퇴 행은 deleted_at IS NOT NULL 로 남긴다. 기존 UNIQUE(email)은
-- 1) 탈퇴 후 같은 이메일 재가입과
-- 2) 같은 이메일의 LOCAL/GOOGLE/KAKAO/NAVER 계정 공존을 모두 막았다.
--
-- 활성 계정에서 같은 로그인 방식과 이메일이 중복되는 것만 DB가 막도록 교체한다.

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS uk_users_email;

-- 과거 환경에서 제약이 아닌 인덱스로 만들어졌을 가능성까지 안전하게 정리한다.
DROP INDEX IF EXISTS uk_users_email;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_active_email_login_type
    ON users (email, login_type)
    WHERE deleted_at IS NULL;

-- 확인용
-- SELECT indexdef FROM pg_indexes
-- WHERE tablename = 'users' AND indexname = 'uk_users_active_email_login_type';
