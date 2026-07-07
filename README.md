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
