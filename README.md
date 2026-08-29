# WishConnect – API Server

WishConnect 백엔드 API 서버입니다.

**Tech Stack:** Spring Boot 3.3 · Java 17 · PostgreSQL · Redis · Spring Security + JWT

---

## Getting Started

clone 후 로컬에서 서버를 띄우기 위한 가이드입니다.
(원본: [환경설정 가이드 (yml)](https://app.notion.com/p/6a10adbca4ca82328bf181d2b3051f8a))

### Prerequisites

- **JDK 17**
- **PostgreSQL** — 로컬에 `wishconnect` 데이터베이스 필요
  ```bash
  createdb wishconnect        # 없을 경우 생성
  ```
- **Redis** (Refresh Token 저장용)
  ```bash
  brew install redis && brew services start redis   # macOS 예시
  ```

### 1. 설정 파일 구조 (민감정보 분리 원칙)

| 파일 | Git | 설명 |
|------|-----|------|
| `application.yml` | ✅ 커밋됨 | 공통 설정. **비밀정보는 절대 여기 넣지 않기** |
| `application-local.yml` | ❌ 커밋 안 됨 | DB·Redis 접속정보 등 로컬 개인 설정 (`.gitignore` 처리됨) |
| `application-local.yml.example` | ✅ 커밋됨 | 위 파일의 템플릿. 복사해서 사용 |
| `application-prod.yml` | ❌ 커밋 안 됨 | 운영 설정. 배포 환경에서 별도 관리 |

### 2. 최초 세팅 (clone 후 1회)

```bash
cd src/main/resources
cp application-local.yml.example application-local.yml
# 이후 application-local.yml 의 값을 본인 로컬 환경에 맞게 수정
```

### 3. 채워야 하는 값

- **PostgreSQL** — `spring.datasource` 의 `url` / `username` / `password`
- **Redis** — `spring.data.redis` 의 `host` / `port` (기본 `localhost:6379`), 비밀번호 있으면 `password`

### 4. 실행

```bash
./gradlew build      # 빌드 + 테스트
./gradlew bootRun    # 서버 실행 (기본 local 프로파일)
```

### 5. 프로파일 관리 (개발/운영 분리) — 팀 룰

`application.yml` 의 `spring.profiles.active: local` 이 커밋되어 있어 **모든 환경은 기본적으로 `local` 프로파일로 실행**됩니다.
**운영 배포 시**에는 실행 시점에 덮어씁니다:

```bash
# 실행 인자로 지정
java -jar app.jar --spring.profiles.active=prod

# 또는 환경변수로 지정
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```

> 💡 운영 민감정보(DB 비밀번호, 카카오 키, JWT secret 등)는 `application-prod.yml` 또는 배포 환경의 **환경변수**로 주입하고, 코드/Git 에는 절대 포함하지 않습니다.

### ⚠️ 주의사항

- `application-local.yml`, `application-prod.yml`, `.env` 는 **절대 커밋 금지** (이미 `.gitignore` 처리됨).
- 설정 항목(키)이 추가/변경되면 반드시 `application-local.yml.example` 도 같이 업데이트해서 **PR 에 포함** → 팀원들이 빠진 키 없이 따라올 수 있습니다.
- 실제 비밀번호/토큰 시크릿은 `.example` 에 넣지 말고 `<PLACEHOLDER>` 형태로만 표기합니다.

---

## sitemap.xml · robots.txt (검색엔진 색인)

[네이버 서치어드바이저 웹마스터 가이드](https://searchadvisor.naver.com/guide/seo-basic-intro)를 기준으로 맞췄다.
공고는 매일 수집 배치로 갈리므로 정적 파일 대신 **DB 를 읽어 즉석에서 만든다**.

| 엔드포인트 | 내용 |
|---|---|
| `GET /sitemap.xml` | 고정 페이지 + 장학금 상세 URL. 모집 중 `priority 0.8`, 마감분 `0.3` |
| `GET /robots.txt` | 네이버 검색로봇(`Yeti`) 허용, 관리자·API 경로 차단, `Sitemap:` 위치 명시 |

둘 다 인증 없이 열려 있고(크롤러는 토큰이 없다), 결과는 서버에서 1시간 캐시한다.
설정은 `app.sitemap.*` / `app.robots.*` ([application.yml](src/main/resources/application.yml)).
프론트 라우팅이 바뀌면 `SITEMAP_SCHOLARSHIP_PATH` / `SITEMAP_STATIC_PATHS` 환경변수만 고치면 된다.

가이드에서 지킨 것:

- **URL 도메인 일치** — 사이트맵 내 URL 이 소유확인한 호스트와 다르거나 상대경로면 네이버가 통째로 버린다.
  그래서 `app.sitemap.base-url` 은 절대주소만 허용하고, 아니면 기동 시점에 예외로 막는다.
  **www 유무까지 서치어드바이저에 등록한 호스트와 똑같이** 맞춰야 한다.
- **용량·건수 상한** — 10MB 미만, 한 파일당 50,000 URL 미만. 상한에 닿으면 WARN 로그를 남긴다.
- **응답 속도** — 피드가 느리면 제출이 제한되므로 생성 결과를 캐시한다.
- **`lastmod`** — 네이버 예시와 같은 W3C Datetime(`2026-08-18T01:13:19+09:00`).
- **모든 URL 수록 권장** — 마감 공고도 상세 페이지가 살아 있으므로 담되 `priority` 를 낮춘다
  (`SITEMAP_INCLUDE_CLOSED=false` 로 제외 가능).
- **robots.txt 는 `text/plain` + 2xx** — HTML 로 나가면 규칙이 무시되고, 5xx 면 사이트 전체가 수집 차단으로 해석된다.
  Yeti 그룹에도 같은 규칙을 반복해 적는다(표준상 가장 구체적인 그룹 하나만 적용되기 때문).

> ⚠️ **남은 작업은 백엔드 밖에 있다.**
> 1. **프론트/nginx 프록시** — 두 파일 모두 사이트 루트에 있어야 한다.
>    `https://wish-connect.com/sitemap.xml`, `/robots.txt` 요청을 이 엔드포인트로 rewrite 해야 한다.
>    robots.txt 규칙은 호스트별로 적용되므로 API 도메인에만 있으면 프론트 수집에는 아무 영향이 없다.
> 2. **서치어드바이저 등록** — 사이트 등록 → 소유확인(meta 태그 또는 HTML 파일) → "요청-사이트맵 제출".
> 3. **프론트 HTML 마크업** — 페이지별 `title`·`meta description`, 표준 `<a href>` 링크, `noindex`/`nofollow` 미사용.
>    콘텐츠를 자바스크립트로만 그리면 수집되지 않으므로 상세 페이지는 SSR 이어야 한다.
>
> RSS 피드는 만들지 않았다. 가이드도 "본문을 담아야 해서 URL 을 많이 넣기 어려우니 사이트맵을 우선 활용"하라고 권한다.
