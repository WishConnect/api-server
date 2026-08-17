-- 거주지역 마스터에 시군구 228건 추가
--
-- 지금까지 region 에는 시도 17건만 있어 프론트가 "서울"까지만 고를 수 있었다.
-- 행정구역은 자주 바뀌지 않으므로 외부 API 연동 대신 수동 시딩으로 둔다.
--
-- 기준: 2026년 행정구역. 군위군은 대구광역시 편입(2023-07-01) 상태로 넣었고
--       경상북도에서는 제외했다. 강원·전북은 특별자치도 개편이 반영돼 있다.
-- 범위: 시·군·자치구까지. 수원시 장안구 같은 '일반구'는 넣지 않는다(드롭다운이 3단계가 되고,
--       장학금 지역 조건도 그 단위까지 내려가지 않는다).
-- 세종특별자치시는 단층제라 하위 행정구역이 없다. 빈 목록이 정상이다.
--
-- ⚠️ 이름은 시도 안에서만 유일하다. '중구'는 6개 시도, '동구'는 6개 시도에 있다.
--    그래서 UNIQUE 는 (name, parent_id) 조합으로 건다.

-- 재실행해도 중복 적재되지 않도록 조합 UNIQUE 를 먼저 만든다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_region_name_parent
    ON region (name, COALESCE(parent_id, -1));

-- 서울 (25)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('종로구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('중구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('용산구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('성동구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('광진구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('동대문구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('중랑구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('성북구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('강북구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('도봉구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('노원구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('은평구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('서대문구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('마포구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('양천구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('강서구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('구로구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('금천구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('영등포구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('동작구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('관악구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('서초구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('강남구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('송파구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW()),
    ('강동구', (SELECT id FROM region WHERE name='서울' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 부산 (16)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('중구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('서구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('동구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('영도구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('부산진구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('동래구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('남구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('북구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('해운대구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('사하구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('금정구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('강서구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('연제구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('수영구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('사상구', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW()),
    ('기장군', (SELECT id FROM region WHERE name='부산' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 대구 (9)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('중구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('동구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('서구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('남구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('북구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('수성구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('달서구', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('달성군', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW()),
    ('군위군', (SELECT id FROM region WHERE name='대구' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 인천 (10)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('중구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('동구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('미추홀구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('연수구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('남동구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('부평구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('계양구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('서구', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('강화군', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW()),
    ('옹진군', (SELECT id FROM region WHERE name='인천' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 광주 (5)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('동구', (SELECT id FROM region WHERE name='광주' AND parent_id IS NULL), NOW(), NOW()),
    ('서구', (SELECT id FROM region WHERE name='광주' AND parent_id IS NULL), NOW(), NOW()),
    ('남구', (SELECT id FROM region WHERE name='광주' AND parent_id IS NULL), NOW(), NOW()),
    ('북구', (SELECT id FROM region WHERE name='광주' AND parent_id IS NULL), NOW(), NOW()),
    ('광산구', (SELECT id FROM region WHERE name='광주' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 대전 (5)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('동구', (SELECT id FROM region WHERE name='대전' AND parent_id IS NULL), NOW(), NOW()),
    ('중구', (SELECT id FROM region WHERE name='대전' AND parent_id IS NULL), NOW(), NOW()),
    ('서구', (SELECT id FROM region WHERE name='대전' AND parent_id IS NULL), NOW(), NOW()),
    ('유성구', (SELECT id FROM region WHERE name='대전' AND parent_id IS NULL), NOW(), NOW()),
    ('대덕구', (SELECT id FROM region WHERE name='대전' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 울산 (5)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('중구', (SELECT id FROM region WHERE name='울산' AND parent_id IS NULL), NOW(), NOW()),
    ('남구', (SELECT id FROM region WHERE name='울산' AND parent_id IS NULL), NOW(), NOW()),
    ('동구', (SELECT id FROM region WHERE name='울산' AND parent_id IS NULL), NOW(), NOW()),
    ('북구', (SELECT id FROM region WHERE name='울산' AND parent_id IS NULL), NOW(), NOW()),
    ('울주군', (SELECT id FROM region WHERE name='울산' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 세종: 단층제라 하위 행정구역이 없다.
-- 경기 (31)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('수원시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('성남시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('의정부시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('안양시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('부천시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('광명시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('평택시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('동두천시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('안산시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('고양시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('과천시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('구리시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('남양주시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('오산시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('시흥시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('군포시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('의왕시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('하남시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('용인시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('파주시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('이천시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('안성시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('김포시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('화성시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('광주시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('양주시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('포천시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('여주시', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('연천군', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('가평군', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW()),
    ('양평군', (SELECT id FROM region WHERE name='경기' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 강원 (18)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('춘천시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('원주시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('강릉시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('동해시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('태백시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('속초시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('삼척시', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('홍천군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('횡성군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('영월군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('평창군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('정선군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('철원군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('화천군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('양구군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('인제군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('고성군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW()),
    ('양양군', (SELECT id FROM region WHERE name='강원' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 충북 (11)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('청주시', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('충주시', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('제천시', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('보은군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('옥천군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('영동군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('증평군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('진천군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('괴산군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('음성군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW()),
    ('단양군', (SELECT id FROM region WHERE name='충북' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 충남 (15)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('천안시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('공주시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('보령시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('아산시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('서산시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('논산시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('계룡시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('당진시', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('금산군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('부여군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('서천군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('청양군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('홍성군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('예산군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW()),
    ('태안군', (SELECT id FROM region WHERE name='충남' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 전북 (14)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('전주시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('군산시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('익산시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('정읍시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('남원시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('김제시', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('완주군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('진안군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('무주군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('장수군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('임실군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('순창군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('고창군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW()),
    ('부안군', (SELECT id FROM region WHERE name='전북' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 전남 (22)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('목포시', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('여수시', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('순천시', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('나주시', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('광양시', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('담양군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('곡성군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('구례군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('고흥군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('보성군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('화순군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('장흥군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('강진군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('해남군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('영암군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('무안군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('함평군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('영광군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('장성군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('완도군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('진도군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW()),
    ('신안군', (SELECT id FROM region WHERE name='전남' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 경북 (22)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('포항시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('경주시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('김천시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('안동시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('구미시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('영주시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('영천시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('상주시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('문경시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('경산시', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('의성군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('청송군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('영양군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('영덕군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('청도군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('고령군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('성주군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('칠곡군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('예천군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('봉화군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('울진군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW()),
    ('울릉군', (SELECT id FROM region WHERE name='경북' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 경남 (18)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('창원시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('진주시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('통영시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('사천시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('김해시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('밀양시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('거제시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('양산시', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('의령군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('함안군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('창녕군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('고성군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('남해군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('하동군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('산청군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('함양군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('거창군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW()),
    ('합천군', (SELECT id FROM region WHERE name='경남' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 제주 (2)
INSERT INTO region (name, parent_id, created_at, updated_at) VALUES
    ('제주시', (SELECT id FROM region WHERE name='제주' AND parent_id IS NULL), NOW(), NOW()),
    ('서귀포시', (SELECT id FROM region WHERE name='제주' AND parent_id IS NULL), NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 확인용
-- SELECT p.name AS 시도, count(c.id) AS 시군구
-- FROM region p LEFT JOIN region c ON c.parent_id = p.id
-- WHERE p.parent_id IS NULL GROUP BY p.name ORDER BY 2 DESC;
