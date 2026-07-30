-- 관리자 권한 도입: users.role 추가
--
-- 운영/개발용 수동 트리거(동기화·크롤링·LLM 조건추출)를 ADMIN 만 호출할 수 있도록
-- User 엔티티에 role 을 추가했다. 기존 사용자는 모두 USER 로 채운다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 컬럼이 없으면 앱이 기동되지 않는다.

ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20);

UPDATE users SET role = 'USER' WHERE role IS NULL;

ALTER TABLE users ALTER COLUMN role SET NOT NULL;

-- 관리자 지정은 아래처럼 직접 실행한다(가입 경로로는 ADMIN 이 될 수 없다).
-- UPDATE users SET role = 'ADMIN' WHERE email = '<운영자 이메일>' AND login_type = 'LOCAL';
