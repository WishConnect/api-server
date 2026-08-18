# WishConnect API Server — 프로젝트 컨텍스트

대학생 대상 AI 장학금 탐색·자기소개서 작성 플랫폼(KUIT 7기)의 백엔드.

## 스택 / 인프라
- Spring Boot 3.3.5 · Java 17(sourceCompatibility; 로컬 빌드 JDK는 22) · Gradle 8.10.2
- PostgreSQL · Redis(Refresh Token/인증코드) · JWT(HS256) · Spring Security
- 이메일: AWS SES(SMTP, JavaMailSender) · LLM: Anthropic Codex(anthropic-java SDK)
- 배포(예정): EC2(Nginx→8080, systemd) + RDS + S3 + GitHub Actions CI/CD

## 브랜치 전략 (GitHub Flow)
- `main`(운영, **push 시 자동배포 트리거**) ← `develop`(통합) ← `feature/<이슈>-<kebab>`
- 실제 코드는 develop에 있고 main은 초기 상태 → 첫 배포하려면 develop→main 머지 필요
- 커밋: `feat:` / `fix:` / `chore:` / `ci:` / `docs:` / `test:` 기능 단위
- ⚠️ **커밋에 Co-Authored-By: Codex 넣지 말 것** (팀 정책으로 제거함). PR 본문도 마찬가지.

## 팀 컨벤션 (반드시 준수)
- 패키지: 도메인형 `com.wishconnect.domain.<도메인>.{controller,service,repository,entity,dto,client,config,util}`, 공통은 `com.wishconnect.global.{common,config,exception,jwt}`
- **공통 응답: 모든 컨트롤러 응답을 `ApiResponse<T>`로 감싼다** (`ApiResponse.ok(data)` / `ok()` / `fail(msg)`). 이유: 프론트/에러처리 일관성.
- **경로: 모든 엔드포인트 `/api/v1/...`** (예: `/api/v1/auth/...`)
- DTO: `~Request`/`~Response` 네이밍, Java record, camelCase. 요청은 `@Valid` + Bean Validation.
- 예외: `ErrorCode` enum(상태+메시지, 노션 명세 기준) → `CustomException` → `GlobalExceptionHandler(@RestControllerAdvice)`로 통일 응답. try-catch 남발 금지.
- 로깅: SLF4J `log.info/warn/error`. `System.out.println` 금지. **민감정보(비밀번호/토큰/인증코드/API키) 로그 금지.**
- 엔티티: `BaseEntity`(createdAt+updatedAt) 또는 `BaseCreatedEntity`(createdAt만) 상속. Lombok `@Getter`/`@NoArgsConstructor(PROTECTED)`/`@Builder`, **`@Setter` 금지**. enum은 `@Enumerated(EnumType.STRING)`. PK는 도메인별(User=UUID, 나머지=`Long @GeneratedValue(IDENTITY)`). `@ManyToOne`은 항상 LAZY + `@JoinColumn(name="..._id")`.

## 노션 API 명세 (구현 전 반드시 대조)
인라인 요구사항보다 노션 명세가 더 상세하니 노션 기준을 따른다.
- API 명세서: https://app.notion.com/p/a480adbca4ca824ba1ce810b83a1e6e7
- Auth 도메인: https://app.notion.com/p/38d0adbca4ca818ea7d5c55a8e176a8c
- 각 엔드포인트는 "상세 API" DB 하위 페이지에 Request/Response JSON + 실패 케이스 표로 정리됨.

## 구현 현황
- ✅ **기반(global)**: ApiResponse, ErrorCode/CustomException/GlobalExceptionHandler, SecurityConfig+JWT(JwtProvider/Filter/EntryPoint/Properties), CORS(CorsConfig, allow localhost:3000/운영 프론트), JPA Auditing, actuator(/actuator/health)
- ✅ **Auth 도메인(develop 머지 완료)**: 회원가입(이메일인증 선행+프로필+약관), 로그인, 카카오/구글/네이버 소셜로그인, 이메일 인증(SES+Redis), 비밀번호 찾기(LOCAL), 토큰갱신, 로그아웃. 소셜은 `(loginType, providerId)`로 별개계정, email UNIQUE 제거. 테스트 70개(단위+@WebMvcTest 슬라이스, DB 불필요).
- ✅ **엔티티 29개 전부 생성**(User/UserProfile/Scholarship/Essay/Insight/Notification/공통마스터 등)
- ⚠️ **미착수(엔티티만 존재)**: scholarship/application/insight/notification/archive의 Repository·Service·Controller. (진행 중 PR: feature/scholarship=동기화 파이프라인, feature/llm-client=AI 클라이언트)
- ✅ **CI/CD**: `.github/workflows/deploy.yml`(main push→build/test→SCP→SSH+헬스체크+롤백), `application-prod.yml`(env 주입, ddl-auto=validate), `deploy/wishconnect.service`(systemd)

## 로컬 실행
```bash
# Postgres + Redis 필요 (Docker 예시)
docker run -d --name wc-postgres -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=wishconnect -p 5432:5432 postgres:16-alpine
docker run -d --name wc-redis -p 6379:6379 redis:7-alpine
./gradlew bootRun        # 기본 local 프로파일
./gradlew build          # 테스트 포함(70개, DB 불필요)
```
- 프로파일: `application.yml`(공통, active=local) + `application-local.yml`(**gitignore, 실제 시크릿**) + `*.example`(커밋). 운영은 `--spring.profiles.active=prod` + EnvironmentFile.
- 로컬 시크릿(DB비번/카카오·구글·네이버 키/JWT/SES)은 `application-local.yml`에 있고 커밋 안 됨.

## 주요 함정 (반복 주의)
- **`ddl-auto: update`는 기존 제약을 못 고침**: enum 값 추가/UNIQUE·CHECK 변경 시 DB에 옛 제약이 남아 "코드는 맞는데 500" 발생(로컬에서 겪음: login_type CHECK, email UNIQUE). 로컬은 재생성하거나 `ALTER TABLE ... DROP CONSTRAINT`. **팀 차원 Flyway 도입 검토 권장.** 운영은 validate라 스키마가 엔티티와 정확히 일치해야 부팅됨.
- 소셜로그인 redirect_uri는 **3곳(소셜콘솔/프론트/백엔드 yml)이 완전 일치**해야 토큰교환 성공. 프론트 주도 방식(프론트가 code 전달).
- 테스트는 H2 없이 **Mockito 단위 + @WebMvcTest 슬라이스**로 구성(DB/Redis 불필요). 전체 컨텍스트 @SpringBootTest는 없음.

## 개발 순서(제안)
공통 선행(인증 컨텍스트/페이징/S3업로드/Flyway) → Scholarship 코어(임계경로) + Onboarding/Profile → Application(자소서 AI)/Insight → Notification/Archive → MyPage(집계).
