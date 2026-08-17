-- 상세페이지·첨부·포스터 자동 보완용 컬럼
--
-- 배경: 공공데이터 원문 22개 필드에 상세 URL·이미지·첨부파일이 아예 없다(엔드포인트 69개 전수 확인).
-- '홈페이지 주소' 하나뿐인데 그 값이 기관 메인이라 사용자가 눌러도 장학금을 볼 수 없다.
-- 검색으로 상세페이지를 찾아 크롤링해 채운다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 없으면 앱이 기동되지 않는다.

-- 검색으로 찾은(또는 사람이 넣은) 장학금 상세페이지. homepage_url(기관 메인)과 별개로 둔다.
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS detail_url VARCHAR(1000);

-- 마지막 보완 시도 시각. 없으면 매 배치가 "못 찾은 건"만 계속 붙잡아 검색 API 쿼터를 태운다.
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMP;

-- 보완 대상 조회(detail_url 없음 + 재시도 주기 경과)를 인덱스로 받친다.
CREATE INDEX IF NOT EXISTS idx_scholarship_enrichment_target
    ON scholarship (enriched_at) WHERE detail_url IS NULL;

-- 자동 수집한 포스터의 출처. 저작권 문의가 오면 어디서 가져왔는지 확인하고 개별 삭제해야 한다.
ALTER TABLE image ADD COLUMN IF NOT EXISTS source_url VARCHAR(1000);
