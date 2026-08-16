-- 회원 정보 개편: 로그인 아이디 추가 / 출생년도 -> 생년월일 / 거주지역 마스터 시드
--
-- 배경(2026-08-16): 회원가입 화면이 아이디를 별도로 받고, 출생년도 대신 생년월일을 받도록 바뀌었다.
-- 거주지역은 이미 user_profile.region_id FK 와 SignupRequest.region 이 있었는데
-- **region 테이블이 비어 있어서**(0건) findByName 이 늘 실패했고, 그래서 region_id 는 전 건 NULL 이었다.
--
-- ⚠️ 배포 전에 먼저 적용할 것. ddl-auto=validate 라 없으면 앱이 기동되지 않는다.

-- 1) 로그인 아이디
--    소셜 가입은 아이디를 입력받지 않아 NULL 이다. Postgres 의 UNIQUE 는 NULL 을 서로 다른 값으로
--    보기 때문에 소셜 계정이 여러 개여도 제약에 걸리지 않는다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_id VARCHAR(30);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_login_id ON users (login_id);

-- 2) 생년월일
--    기존 birth_year 는 연도 4자리뿐이라 월/일을 복원할 수 없다. 1월 1일로 채우면 틀린 값을
--    사실처럼 저장하는 셈이라, 백필하지 않고 NULL 로 둔다(해당 사용자는 프로필에서 다시 입력).
ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS birth_date DATE;

--    birth_year 컬럼은 지금 지우지 않는다. validate 는 엔티티에 없는 여분 컬럼을 문제 삼지 않으므로
--    배포는 통과하고, 혹시 되돌릴 일이 생겨도 값이 남아 있다. 안정화 후 아래를 실행해 정리할 것.
--    ALTER TABLE user_profile DROP COLUMN birth_year;

-- 3) 거주지역 마스터 (시도 17개)
--    공공데이터 API 를 붙이지 않은 이유: 행정구역은 거의 바뀌지 않는 정적 데이터이고,
--    17행을 위해 API 키·동기화 배치·외부 장애점을 늘리는 건 비용 대비 손해다.
--
--    이름을 "서울특별시" 가 아니라 "서울" 로 넣는 이유: 지역 조건 판정이
--    ConditionMatcher.containsRegionName(조건원문, 지역명) 즉 부분 문자열 포함이라,
--    짧은 핵심어여야 "서울시 거주자", "서울특별시 소재" 같은 표기를 모두 잡는다.
--    region 은 BaseEntity 상속이라 created_at·updated_at 이 NOT NULL 이다. 직접 넣어야 한다.
INSERT INTO region (name, created_at, updated_at)
SELECT v.name, now(), now()
FROM (VALUES
    ('서울'), ('부산'), ('대구'), ('인천'), ('광주'), ('대전'), ('울산'), ('세종'),
    ('경기'), ('강원'), ('충북'), ('충남'), ('전북'), ('전남'), ('경북'), ('경남'), ('제주')
) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM region r WHERE r.name = v.name);
