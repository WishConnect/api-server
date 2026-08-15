-- 관리자 쓰기 작업 감사 로그
--
-- 관리자 콘솔을 팀원 여러 명(현재 ADMIN 5명)이 쓰게 되면서 필요해졌다.
-- 특히 엑셀 일괄 반영은 한 번에 수백 행을 바꾸는데, 기록이 없으면 잘못된 파일을 올려도
-- 누가 언제 무엇을 바꿨는지 되짚을 수가 없다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 테이블이 없으면 앱이 기동되지 않는다.

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id          BIGSERIAL   PRIMARY KEY,
    -- users(id) FK 를 걸지 않는다. 감사 기록은 대상이 지워져도 남아야 하는데,
    -- FK 가 있으면 회원 탈퇴가 막히거나 기록이 함께 지워진다.
    actor_id    UUID        NOT NULL,
    -- AdminAction enum. CHECK 제약을 일부러 걸지 않는다 —
    -- ddl-auto=update 는 기존 CHECK 를 고치지 못해, enum 값을 추가하면
    -- "코드는 맞는데 23514 로 500" 이 나는 사고가 이미 두 번 있었다(V20260805_01 참고).
    action      VARCHAR(50) NOT NULL,
    target_type VARCHAR(50),
    target_id   BIGINT,
    detail      VARCHAR(1000),
    created_at  TIMESTAMP   NOT NULL
);

-- 화면은 항상 최신순으로 훑는다.
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_id_desc
    ON admin_audit_log (id DESC);

-- 행위별 필터.
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_action
    ON admin_audit_log (action, id DESC);
