-- 오등록 신고 기능: scholarship_report 테이블 추가
--
-- 사용자가 발견한 장학금 정보 오류(마감일/금액/링크 등)를 접수받고, 관리자가 처리 상태를
-- 관리하기 위한 테이블이다. 신고 처리(상태 변경)와 실제 데이터 수정은 분리되어 있어,
-- 수정은 /api/v1/scholarships/manual/{id} 로 별도 수행한다.
--
-- 기존 테이블은 컬럼 변경이 없다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 테이블이 없으면 앱이 기동되지 않는다.

CREATE TABLE IF NOT EXISTS scholarship_report (
    id              BIGSERIAL   PRIMARY KEY,
    scholarship_id  BIGINT      NOT NULL REFERENCES scholarship (id),
    user_id         UUID        NOT NULL REFERENCES users (id),
    -- ReportReason: WRONG_DEADLINE / WRONG_AMOUNT / BROKEN_LINK / ALREADY_CLOSED / DUPLICATE / OTHER
    reason          VARCHAR(30) NOT NULL,
    detail          TEXT,
    -- ReportStatus: PENDING / RESOLVED / REJECTED
    status          VARCHAR(20) NOT NULL,
    admin_note      TEXT,
    resolved_at     TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);

-- 관리자 신고 목록은 상태별 최신순 조회가 기본이다.
CREATE INDEX IF NOT EXISTS idx_scholarship_report_status
    ON scholarship_report (status, id DESC);

-- 중복 접수 차단(같은 사용자 + 같은 장학금 + PENDING)은 애플리케이션에서 검사한다.
-- 동시 요청까지 막으려면 아래 부분 유니크 인덱스를 고려할 수 있다.
-- CREATE UNIQUE INDEX idx_scholarship_report_pending_unique
--     ON scholarship_report (scholarship_id, user_id) WHERE status = 'PENDING';
