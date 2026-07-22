# WishConnect 백엔드 — 세션 인수인계 (2026-07-22)

아래를 새 세션에 붙여넣어 이어서 작업하세요. gh는 로그인돼 있어 PR 생성/리뷰/머지 가능.
**먼저 프로젝트 루트 `CLAUDE.md`와 auto-memory(MEMORY.md 인덱스의 4개 파일)를 읽고 시작할 것.**

## 지금까지 배포된 것 (운영 = https://api.wish-connect.com, 라이브)
- 인프라: EC2(15.165.86.126, systemd `wishconnect`, 8080, nginx→api.wish-connect.com), RDS PostgreSQL(`wishconnect` DB), Redis, GitHub Actions(main push 자동배포). ddl-auto=validate.
- Auth 전체, **Scholarship 큐레이팅(추천/매칭)·상세·홈요약**, 스크랩(archive), 마감처리 배치, 룰기반 조건정제기.
- **수집 파이프라인**: KOSAF 공공데이터 sync(매일 23시 KST 배치) + 대학 크롤러 4곳(건국/한림/연세/외대) + **포스터 이미지 S3 파이프라인(presigned URL, wishconnect-images 버킷, EC2 IAM role `wishconnect_ec2`)**.
- 대학 공지 교내/교외 태그 분류(EXTERNAL 재단명 추출).

## 배포/운영 방식 (반드시 준수)
- 브랜치 컨벤션: develop 분기 → PR → develop, 배포는 develop→main PR 머지(자동배포). Co-Authored-By 금지.
- **매 배포마다 확인**: 빌드 전 `find src build -name '* 2.*' -delete` (iCloud 동기화가 `* 2.java/.class` 복제본을 만들어 `resolveMainClassName` 빌드 실패 유발 — 반복됨).
- 신규 엔티티 컬럼/타입변경 시 **main 배포 전 RDS에 ALTER 선행 필수**(validate라 없으면 부팅 실패). develop 머지는 무방.
- 운영 DB 접속: EC2에 스크립트 scp 후 `sudo bash`로 실행(env의 DB_PASSWORD 사용). 인라인 SQL은 따옴표 이스케이프 지옥이라 스크립트 파일 방식 권장.

## 열린 PR (리뷰 완료, 원저자 수정 대기)
- **#30 온보딩 프로필**(안진모): 테스트·매칭 호환 OK. **머지 전 RDS ALTER 5건 필수** — user_profile: birth_year/grade/onboarding_step를 varchar로 타입변경, family_size(bigint)·users.deleted_at(timestamp) 추가. PR 코멘트에 SQL 있음.
- **#35 장학금 검색**(안진모): 🔴 ScholarshipRepository 재작성으로 findAllOpenForRecommendation/closeExpired 삭제됨(추천 컴파일 깨짐) → 리베이스 후 메서드만 추가. 🔴 is_verified=true 필터가 검색 90% 누락(제거 권장). 🟡 ResponseEntity·class DTO·SyncController 위치·와일드카드 import 컨벤션 위반. PR 코멘트 상세.

## 진행 중이던 작업 (이어서 할 것) ★
**대학 수집기 다유형 확장** — 사용자가 요청한 15개 대학 중 **C형까지 5건 구현** 중 중단.
- 현 수집기 `UnivNoticeCollector`는 `artclView.do` 전용. 여러 게시판 패턴 지원하도록 범용화 중이었음.
- 범용화 초안이 `git stash`에 있음(`stash@{0}: univ-collector-generalize-wip` — UnivNoticeProperties에 linkPattern/detailTemplate/listParam 필드 추가본). **collector 본체는 아직 이 helper를 안 씀** → 마저 연결 필요.
- 정찰 완료된 대학 유형(2026-07-21):
  - **B형(쿼리파라미터 `?mode=view&articleNo=`)**: 성균관대(skku.edu/skku/campus/skk_comm/notice06.do), 홍익대(hongik.ac.kr/kr/newscenter/notice.do), 세종대(sejong.ac.kr/kor/intro/notice7.do?...articleNo=). 동국대(dongguk.edu/article/JANGHAKNOTICE/list, detail?seq=).
  - **WP형(WordPress)**: 서울대(student.snu.ac.kr), 숭실대(ssu.ac.kr).
  - **C형(전용/Liferay)**: 한양대·단국대(Liferay portlet), 중앙대·경희대·시립대(게시판 URL 추가 정찰 필요 — 장학안내 페이지만 확보). 시립대는 SSO 로그인 리다이렉트 주의.
  - **D형 보류**: 서강대(Nuxt SPA), 고려대(로그인 포털).
- 각 사이트 추가 시 **포스터 이미지도 수집**(ImageStorageService.storeFromUrl, findPosterUrl 재사용). 제목추출은 스킨별 상이 — extractTitle 폴백체인 참고(연세/외대는 hidden input #artclViewTitle).
- 수동 트리거: `POST /api/v1/scholarships/collect/univ/{code}?pages=N`. yml: `scholarship.collect.univ.sites[]`.

## 기타 대기/미결
- **ERD 수정 필요**(실DB와 불일치): scholarship `hompage_url→homepage_url`, `deadup_key→dedup_key`(+UNIQUE), image `s3_key VARCHAR(500)→s3key VARCHAR(255)`, image entity_type/image_type/content_type 실제 255, scholarship_condition.value_string TEXT, scholarship_timeline.title 255. (문서만 수정)
- **Anthropic 크레딧 없음** → LLM 조건추출 502. 룰정제기가 커버 중. 계정주 충전 대기.
- Flyway 도입 미결(validate 드리프트 근본해결책 — 위 RDS ALTER 수작업의 원인).
- 교내 큐레이션 노이즈: 비장학 공지("전화번호 변경" 등)·파싱잔재 제목 필터 후속 개선거리.
- KOSAF endpoint-limit는 조치 불필요(포털이 현재 스냅샷 1개만 노출).

## 프론트 연동용 테스트 계정 (운영)
- `frontend.test@wish-connect.com` / `WishFe2026!` (로그인 200 확인)
- 기존: `gichanyoon@gmail.com` / `ProdTest12!` (프로필 세팅됨: income_level=4, gpa=3.40, grade=2)

## 뷰어(포스터 확인용): scratchpad/viewer/(server.py 프록시 :8090 + index.html), .claude/launch.json 'poster-viewer'
