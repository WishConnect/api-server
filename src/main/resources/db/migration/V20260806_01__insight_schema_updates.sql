-- V20260806_01__insight_schema_updates.sql
--
-- insight 기능(장학금 관련 콘텐츠 자동 수집) 개발 과정에서
-- 배포용 RDS에 반영된 스키마 변경 및 마스터 데이터 기록.
-- 절차 확립 전 작업으로 직접 실행 후 사후 기록합니다.

-- 1. source 컬럼 추가 (크롤링 출처: NAVER_BLOG/TISTORY 등 구분용)
ALTER TABLE insight ADD COLUMN IF NOT EXISTS source VARCHAR(20);

-- 2. 컬럼 길이 확장
-- 사유: 티스토리 URL의 한글 슬러그가 URL 인코딩되며 255자를 초과하는 경우가 많아
--       INSERT 시 "value too long for type character varying(255)" 에러 발생
ALTER TABLE insight ALTER COLUMN original_url TYPE VARCHAR(1000);
ALTER TABLE insight ALTER COLUMN thumbnail_url TYPE VARCHAR(1000);
ALTER TABLE insight ALTER COLUMN title TYPE VARCHAR(500);

-- 3. 카테고리 마스터 데이터 삽입
-- 사유: InsightCollectService.resolveCategory()가 이 데이터를 조회하며,
--       없으면 예외 발생
INSERT INTO insight_category (name, display_order, created_at, updated_at) VALUES
                                                                               ('ACCEPTED', 1, NOW(), NOW()),
                                                                               ('SCHOLARSHIP_INFO', 2, NOW(), NOW()),
                                                                               ('WRITING_TIP', 3, NOW(), NOW()),
                                                                               ('EXPERIENCE', 4, NOW(), NOW())
                                                                                ON CONFLICT (name) DO NOTHING;