# 운영 서버(EC2) 설정

이 디렉터리는 **운영 EC2 설정 파일의 원본**이다. 서버에서 고친 내용은 반드시 여기에도 반영해 두 벌이 어긋나지 않게 한다.

| 파일 | 설치 위치 | 반영 방식 |
|---|---|---|
| `nginx/wishconnect.conf` | `/etc/nginx/sites-available/wishconnect` | **배포 워크플로가 자동 반영** (아래 Nginx 절) |
| `wishconnect.service` | `/etc/systemd/system/wishconnect.service` | 수동 |
| `wishconnect.logrotate` | `/etc/logrotate.d/wishconnect` | 수동 |
| `wishconnect.env.example` | `/etc/wishconnect/wishconnect.env` | 실제 값은 배포 워크플로가 GitHub Secrets 로 기록 (root:600) |

## 설치

```bash
sudo cp wishconnect.service /etc/systemd/system/wishconnect.service
sudo systemd-analyze verify /etc/systemd/system/wishconnect.service
sudo systemctl daemon-reload && sudo systemctl restart wishconnect

sudo cp wishconnect.logrotate /etc/logrotate.d/wishconnect
sudo logrotate -d /etc/logrotate.d/wishconnect   # -d: 시뮬레이션만
```

## 스왑 (파일로 관리되지 않는 설정)

t3.small 은 메모리 1.9GB 라 여유가 크지 않다. 스왑이 없으면 메모리가 몰릴 때
커널 OOM killer 가 **가장 큰 프로세스인 java 를 즉시 죽인다.**
실제로 2026-07-28, 2026-08-03 두 번 이렇게 죽었고, 8/3 건은 앱이 아니라
cron 작업이 메모리를 요구하면서 발생했다. 스왑은 "죽는 대신 느려지는" 안전망이다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab          # 재부팅 후 유지
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf # 평소엔 안 쓰고 비상시에만
sudo sysctl -p /etc/sysctl.d/99-swappiness.conf
```

`swappiness` 기본값 60 은 여유가 있어도 적극적으로 스왑을 써서 평상시 응답이 느려진다.
10 으로 낮춰 진짜 몰릴 때만 쓰이게 한다.

## 🌐 Nginx — 레포가 기준

운영 Nginx 의 WishConnect 사이트 설정은 [`nginx/wishconnect.conf`](nginx/wishconnect.conf) 가 원본이다.
**서버에서 직접 고치면 다음 배포 때 덮어써진다.** 고칠 일이 있으면 이 파일을 고쳐 PR 로 올린다.

이 파일이 하는 일:

| 항목 | 값 | 이유 |
|---|---|---|
| `client_max_body_size` | `10m` | 기본값 1m 때문에 1MB 넘는 포스터 업로드가 413(HTML)으로 막혔다. 앱 한도(파일 5MB / 요청 6MB)보다 커야 앱이 한국어 400 문구를 낸다 |
| `server_tokens` | `off` | 응답 헤더의 Nginx 버전(`nginx/1.24.0 (Ubuntu)`) 숨김 |
| 예전 콘솔 차단 | `= /admin/index.html`, `= /admin/layout-preview.html` → 404 | **정확히 일치만** 막는다. `/admin/` 접두사 전체를 막으면 `/admin/console` 까지 막힌다 |
| API 문서 차단 | `^~ /swagger-ui`, `^~ /v3/api-docs` → 404 | 앱에서도 운영은 꺼져 있다(`application-prod.yml`). 이중 차단 |
| `X-Forwarded-For` | `$remote_addr` 로 덮어씀 | 클라이언트가 보낸 값을 이어 붙이면 위조로 관리자 로그인 IP 제한을 우회할 수 있다 |

### 자동 반영 (main 배포 시)

[deploy.yml](../.github/workflows/deploy.yml) 의 **앱 배포·헬스체크가 성공한 뒤**에 별도 단계로 실행된다.

1. 설정 파일을 `/home/ubuntu/app/nginx/` 에 업로드
2. 전제 조건 확인 (비밀번호 없는 sudo, `sites-enabled/wishconnect` 심볼릭 링크) — 실패하면 **아무것도 바꾸지 않고** 실패
3. 서버 파일과 같으면 건너뜀
4. 기존 파일을 `/etc/nginx/wishconnect-backup/` 에 백업 (최근 10개 유지)
5. 교체 → `sudo nginx -t` — 실패하거나 `conflicting server name` 경고가 나면 복구
6. 통과 시에만 `sudo systemctl reload nginx`
7. Nginx 를 거쳐 점검: health UP, `/admin/console` 302, `/admin/login.html` 200, 예전 콘솔·Swagger 404,
   HTTP→HTTPS 301, 2MB 본문 413 아님, `Server: nginx`
8. 5~7 중 하나라도 실패하면 백업으로 되돌리고(되돌린 설정이 `nginx -t` 를 통과할 때만 reload) **워크플로 실패**

앱 배포가 실패(→ 앱 롤백)하면 Nginx 단계는 실행되지 않는다. Nginx 단계가 실패해도 **앱은 롤백되지 않는다**
(앱 단계는 이미 끝났고 롤백 로직은 그 단계 안에만 있다).

### 전제 조건 (서버에서 한 번만)

- `ubuntu` 사용자가 **비밀번호 없이** `sudo` 를 쓸 수 있어야 한다(`sudo -n true` 가 성공). 워크플로가 쓰는 명령:
  `nginx -t`, `systemctl reload nginx`, `cp`, `install`, `mkdir`, `rm`(백업 정리), `cmp`.
  EC2 Ubuntu 기본값(`/etc/sudoers.d/90-cloud-init-users` 의 `NOPASSWD:ALL`)이 남아 있으면 이미 된다.
- 사이트 설정이 `/etc/nginx/sites-available/wishconnect` 하나에 있고, `/etc/nginx/sites-enabled/wishconnect` 가 그 파일을 가리키는 심볼릭 링크여야 한다.
- `api.wish-connect.com` server 블록이 **다른 파일(`sites-enabled/default` 등)에 남아 있으면 안 된다.**
  남아 있으면 nginx 는 한쪽을 경고만 내고 조용히 무시한다 — 워크플로는 이 경고를 실패로 처리한다.
- 인증서 경로가 `/etc/letsencrypt/live/api.wish-connect.com/` 이어야 한다(다르면 이 파일의 경로를 고친다).
- `/etc/nginx/conf.d/ratelimit.conf` 에 로그인 속도 제한 zone 이 있어야 한다(레포 설정의 `/api/v1/auth/login` 이 쓴다).
  `limit_req_zone` 은 http 블록 지시어라 사이트 파일에 두지 않는다. 없으면 `nginx -t` 가
  `zero size shared memory zone "login"` 으로 실패하고 워크플로가 이전 설정으로 복구한다.
  ```nginx
  limit_req_zone $binary_remote_addr zone=login:10m rate=10r/m;
  limit_req_status 429;
  ```

### 최초 이전 (현재 서버 설정 → 레포 파일)

```bash
# 1) 현재 상태 확인: api.wish-connect.com 블록이 어느 파일에 있는지, 인증서 경로·renew 방식
sudo nginx -T 2>/dev/null | grep -nE '^# configuration file|server_name|ssl_certificate|client_max_body_size|location'
ls -l /etc/nginx/sites-enabled/
sudo grep -h authenticator /etc/letsencrypt/renewal/*.conf

# 2) 레포 파일과 비교 — 서버에만 있는 설정이 있으면 레포 파일에 먼저 옮긴다(PR)
diff <(sudo cat /etc/nginx/sites-enabled/<현재파일>) deploy/nginx/wishconnect.conf

# 3) 현재 설정을 wishconnect 이름으로 옮기고 링크 교체 (내용은 그대로 → 동작 변화 없음)
sudo cp -a /etc/nginx/sites-enabled/<현재파일> /root/nginx-before-migration.conf
sudo cp /etc/nginx/sites-available/<현재파일> /etc/nginx/sites-available/wishconnect
sudo ln -s /etc/nginx/sites-available/wishconnect /etc/nginx/sites-enabled/wishconnect
sudo rm /etc/nginx/sites-enabled/<현재파일>          # default 에 다른 사이트도 있었다면 api 블록만 지운다
sudo nginx -t && sudo systemctl reload nginx
```

이후 main 배포 때 레포 파일로 교체된다. 교체 후 `sudo certbot renew --dry-run` 으로 인증서 갱신이 되는지 확인한다.

### 수동 반영·되돌리기

```bash
# 레포 파일 수동 반영
sudo cp wishconnect.conf /etc/nginx/sites-available/wishconnect && sudo nginx -t && sudo systemctl reload nginx

# 직전 백업으로 되돌리기
sudo cp "$(ls -1t /etc/nginx/wishconnect-backup/* | head -1)" /etc/nginx/sites-available/wishconnect
sudo nginx -t && sudo systemctl reload nginx
```

## 🔒 관리자 콘솔 접근

관리자 콘솔은 **도메인으로 접속한다**: `https://api.wish-connect.com/admin/console`
(로그인 전이면 `/admin/login.html` 로 이동, 로그인하면 다시 콘솔로 온다. `/admin`, `/admin/` 도 콘솔로 보낸다.)

- 화면·관리 API 모두 ADMIN 권한 필요: `SecurityConfig` 경로 규칙 + 컨트롤러 `@PreAuthorize("hasRole('ADMIN')")` 이중 차단
- 화면 인증은 HttpOnly 쿠키, 변경 요청(POST/PATCH/PUT/DELETE)은 쿠키만으로 인증하지 않는다
- 로그인 실패 제한(아이디·IP), 관리자 감사 로그가 있다
- 예전 콘솔(`/admin/index.html`, 토큰 붙여넣기 방식)은 삭제했다. 앱은 콘솔로 리다이렉트, Nginx 는 404

**아직 없는 것**: 관리자 **2FA**, 접속 IP 제한. 계정 비밀번호가 새면 바로 뚫리니 접근 범위를 넓히기 전 검토할 것.

### Swagger

운영에서는 꺼져 있다 — `application-prod.yml` 의 기본값 `SWAGGER_ENABLED=false`, 배포 워크플로도 env 에 `false` 를 기록,
Nginx 에서도 404. 운영 스펙을 꼭 봐야 하면 env 를 `true` 로 바꿔 재시작한 뒤 **SSH 터널로만** 본다(Nginx 를 거치지 않음):

```bash
ssh -N -L 18080:localhost:8080 -i <키 파일> ubuntu@<EC2_HOST>
# 브라우저: http://localhost:18080/swagger-ui.html (ADMIN 로그인 필요). 확인 후 env 를 false 로 되돌릴 것
```

## 확인

```bash
sudo systemctl status wishconnect
curl -s localhost:8080/actuator/health          # {"status":"UP"}
sudo -u ubuntu jcmd $(pgrep -f app.jar) VM.flags | tr ' ' '\n' | grep HeapSize
systemctl show wishconnect -p StartLimitBurst -p StartLimitIntervalUSec
free -m
```

## 2026-08-05 적용 이력

출시 전 안정화. 실사용자가 거의 없는데도 OOM 으로 2회 죽어 조치했다.

| 항목 | 변경 |
|---|---|
| 힙 상한 | `-Xms512m -Xmx1g` 명시. 없으면 JVM 이 물리메모리 25% 를 자동 상한으로 잡아 인스턴스 크기에 따라 변한다 |
| 스왑 | 2GB + `vm.swappiness=10` |
| 재시작 폭주 방지 | `StartLimitBurst=5` / `StartLimitIntervalSec=300` |
| 로그 | logrotate 일 단위 7일 보관 (그전까지 app.log 가 무한 증가, 10.7MB) |


## 장학금 파이프라인 비교용 staging

`Deploy pipeline review to staging`은 수동 실행 전용이다. 빌드·테스트한 JAR와 커밋 SHA·SHA-256
매니페스트를 함께 배포하며, 기존 운영 배포 워크플로와 별도의 경로·systemd 서비스를 사용한다.
GitHub 수동 실행은 이 워크플로가 저장소 기본 브랜치에 등록된 뒤 사용할 수 있다.
첫 버전은 **동일한 모델 응답에 대한 기존/신규 검증 결과 비교**다. 수집기 전체의 A/B 실행이나
운영 장학금·일정 자동 수정 기능은 포함하지 않는다.

### 최초 준비

- GitHub `staging` Environment에 `STAGING_HOST`, `STAGING_USER`, `STAGING_SSH_KEY`,
  `STAGING_HOST_FINGERPRINT`, `STAGING_ENV_FILE`을 설정한다. 운영 Secret을 재사용하지 않는다.
- 대상은 Ubuntu의 `ubuntu` 계정, Java 17, Python 3, PostgreSQL 클라이언트, 비밀번호 없는 sudo,
  `/home/ubuntu/wishconnect-staging` 쓰기 권한을 전제로 한다. 별도 서버 또는 충분한 자원을 확보한다.
- `staging/staging.env.example`을 기준으로 독립 DB·Redis·JWT를 준비한다. DB 이름은 `_staging`으로
  끝나고 Redis DB는 1 이상이어야 한다. Redis의 별도 인스턴스 사용을 권장한다.
- DB에는 **기존 애플리케이션 스키마**와 익명화한 검증 데이터가 먼저 있어야 한다. 배포 스크립트는
  비교 이력 테이블 마이그레이션만 적용하며, 운영 데이터 복사·기존 스키마 생성은 수행하지 않는다.
- `STAGING_ENV_FILE`은 `KEY=value` 형식이다. 공백이 있는 값은 따옴표로 감싼다. 실제 값은 커밋하지 않는다.
  LLM 키 없이도 `/replay` 검증은 가능하다. `/compare`는 관리자 수동 실행 시 모델 과금이 발생한다.

스테이징 프로필은 수집·알림 자동 배치를 끄고, 운영 S3·메일·OAuth 자격증명을 사용하지 않는다.
루프백 `127.0.0.1:18081`로만 제공하므로 SSH 터널로 접근한다.

### 비교 API

`scholarship.pipeline.review-enabled`는 기본 false, staging에서 true다. 모든 API는 ADMIN 권한이 필요하다.
기본 경로는 `/api/v1/admin/scholarship-pipeline`이다.

| 메서드·경로 | 동작 |
|---|---|
| `POST /replay` | `{ "rawId": 1, "modelResponse": "{\"timeline\":[]}" }` — 기록한 응답/fixture로 외부 호출 없이 재현 |
| `POST /compare` | `{ "rawIds": [1, 2] }` — 최대 10개 원문, 각각 LLM 1회 호출 후 검증 결과 비교 |
| `POST /runs/{id}/compare` | API 보완 수집 중 캡처한 상세 HTML로 LLM 비교 |
| `GET /runs`, `GET /runs/{id}` | 입력·원본·응답·버전·후보·검증 실패 사유 조회 |

API 상세 HTML은 기존 보완 수집이 성공했을 때 비교 기능이 켜져 있으면 별도 트랜잭션으로 캡처한다.
캡처 실패는 기존 보완 수집을 막지 않는다. 신규 fetch나 자동 LLM 호출은 추가하지 않는다.
대학 HTML/API JSON은 저장된 RawScholarship으로 재현한다. 실제 사이트 재수집이나 선택자 전달 수정은
후속 작업이다. 날짜만 있는 값은 DATE_ONLY, 시각까지 확인되면 DATE_TIME이며 누락 시각을 만들지 않는다.
이 결과는 아직 모집 상태/D-day 계산에 연결되지 않으므로 기존 화면 오류가 즉시 해결되는 배포는 아니다.

### 마이그레이션·검증·롤백

`V20261102_01__scholarship_pipeline_review.sql`은 새 JAR **기동 전에 필수**다.
기능 플래그가 false여도 JPA `validate` 대상 엔티티이므로 테이블이 없으면 앱 기동이 실패한다.
운영 배포 워크플로는 SQL을 자동 적용하지 않으므로, 향후 운영 배포 전 별도로 적용해야 한다.
추가 테이블·인덱스만 생성하며 기존 장학금·일정 테이블과 데이터는 변경하지 않는다.

배포의 `/actuator/health` 통과는 기동 확인이다. 별도로 `/replay`와 비교 조회를 실행해
제목에만 있는 기간, 실제 16:00 마감, 날짜만 있는 API 기간, 서류심사 결과 발표, 잘못 연결된 일정,
관리자 잠금 데이터 보호를 확인한다. 검수된 정답셋으로 누락·오탐을 평가한 뒤 운영 적용을 설계한다.
배포 실패 시 이전 JAR로 돌아가고 비교 이력 테이블은 유지한다. 이 절차는 정제 데이터의 롤백 기능이 아니다.
