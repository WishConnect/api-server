-- 장학금 태그 테이블 + 제출서류 다운로드 URL 컬럼
--
-- 배경(2026-08-16 공공데이터 적재 점검):
--  1) 응답의 tags 가 전 건 빈 배열이었다. 서비스 코드에 List.of() 가 하드코딩돼 있었고
--     ("TODO: 태그 연동" 주석까지 있었다), 원문에는 분류 정보가 멀쩡히 들어 있었다.
--  2) 상세 응답 DTO 에 RequiredDocument(name, downloadUrl) 이 있는데 컬럼이 없어 늘 null 이었다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 없으면 앱이 기동되지 않는다.

-- 1) 장학금 태그
--    정규화 테이블로 둔 이유: 나중에 "성적우수만" 같은 태그 필터를 붙일 때
--    콤마 문자열 컬럼이면 LIKE 로 긁어야 하고 부분일치 오탐이 난다.
CREATE TABLE IF NOT EXISTS scholarship_tag (
    id             BIGSERIAL   PRIMARY KEY,
    scholarship_id BIGINT      NOT NULL REFERENCES scholarship (id),
    name           VARCHAR(50) NOT NULL,
    display_order  INT         NOT NULL,
    created_at     TIMESTAMP   NOT NULL
);

-- 상세·검색 모두 장학금 단위로 읽는다.
CREATE INDEX IF NOT EXISTS idx_scholarship_tag_scholarship
    ON scholarship_tag (scholarship_id, display_order);

-- 태그 역방향 조회(나중에 태그 필터를 붙일 때).
CREATE INDEX IF NOT EXISTS idx_scholarship_tag_name
    ON scholarship_tag (name);

-- 2) 제출서류 다운로드 URL
--    공공데이터 원문에는 파일 URL 이 없다(제출서류가 텍스트로만 온다).
--    크롤링 출처의 첨부파일이나 관리자 보완 입력으로 채운다. 그래서 NULL 허용.
ALTER TABLE scholarship_document
    ADD COLUMN IF NOT EXISTS download_url VARCHAR(1000);
