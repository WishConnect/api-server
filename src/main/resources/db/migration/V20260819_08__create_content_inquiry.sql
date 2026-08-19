-- 콘텐츠 이용·권리 문의와 선택 첨부파일 메타데이터.
-- 운영은 ddl-auto=validate이므로 애플리케이션 배포 전에 실행해야 한다.

CREATE TABLE IF NOT EXISTS content_inquiry (
    id                      BIGSERIAL PRIMARY KEY,
    inquiry_type            VARCHAR(40),
    inquiry_target          VARCHAR(200),
    organization_name       VARCHAR(100),
    email                   VARCHAR(254) NOT NULL,
    phone                   VARCHAR(30),
    content                 VARCHAR(500) NOT NULL,
    attachment_key          VARCHAR(500),
    attachment_name         VARCHAR(255),
    attachment_content_type VARCHAR(100),
    attachment_size         BIGINT,
    status                  VARCHAR(20) NOT NULL,
    admin_note              VARCHAR(1000),
    resolved_at             TIMESTAMP,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_content_inquiry_status
    ON content_inquiry (status, id DESC);
