# DB 마이그레이션 기록

이 디렉터리는 **운영 DB 에 적용해야 하는 스키마 변경을 기록**한다.
현재 Flyway 등 마이그레이션 도구를 도입하지 않았으므로 **자동 적용되지 않는다.**
배포 담당자가 아래 절차대로 직접 실행해야 한다.

## 왜 필요한가

운영은 `spring.jpa.hibernate.ddl-auto: validate` 라서, 엔티티와 실제 테이블이 다르면
**애플리케이션이 기동에 실패한다.** 스키마 변경이 포함된 배포는 SQL 을 먼저 적용해야 한다.

지금까지는 이 기록이 없어 "운영에 어떤 변경이 적용됐는지" 추적할 수 없었다.
앞으로 스키마를 바꾸는 PR 은 반드시 이 디렉터리에 SQL 을 함께 올린다.

## 적용 순서

1. **배포 전에** 아래 파일들을 파일명 순서대로 운영 DB 에 적용한다.
2. 적용 후 `main` 에 머지해 배포한다. (순서가 바뀌면 `validate` 실패로 배포가 깨진다)
3. 적용한 파일은 아래 목록의 체크박스에 표시한다.

```bash
psql -h <RDS_HOST> -U <USER> -d wishconnect -f V20260729_01__add_role_to_users.sql
```

## 파일 네이밍

`V<날짜>_<순번>__<설명>.sql` — Flyway 네이밍 규칙을 따른다.
나중에 Flyway 를 도입하면 이 파일들을 그대로 사용할 수 있다.

## 적용 이력

| 파일 | 내용 | 운영 적용 |
|---|---|---|
| `V20260729_01__add_role_to_users.sql` | `users.role` 컬럼 추가 (관리자 권한) | ✅ 2026-07-29 |
| `V20260731_01__create_scholarship_report.sql` | `scholarship_report` 테이블 추가 (오등록 신고) | ✅ 2026-07-31 |
| `V20260805_01__fix_enum_check_constraints.sql` | 옛 enum 이 남은 CHECK 제약 정정 (`essay.status`, `user_profile.dual_major`) | ✅ 2026-08-05 |
| `V20260806_01__insight_schema_updates.sql` | insight.source 컬럼 추가, 컬럼 길이 확장 | ✅ 2026-08-06 |
| `V20260816_01__seed_sigungu_regions.sql` | 거주지역 시군구 228건 시딩 + `(name, parent_id)` UNIQUE | ✅ 2026-08-17 |
| `V20260815_01__fix_scholarship_type_check.sql` | `scholarship.scholarship_type` CHECK 제약에 `WORK_STUDY` 추가 | ✅ 2026-08-16 확인 |
| `V20260816_01__create_admin_audit_log.sql` | `admin_audit_log` 테이블 추가 (관리자 쓰기 작업 기록) | ✅ 2026-08-16 |
| `V20260816_02__scholarship_tag_and_document_url.sql` | `scholarship_tag` 테이블 + `scholarship_document.download_url` | ✅ 2026-08-16 |
| `V20260816_03__users_login_id_birth_date_region.sql` | `users.login_id`, `user_profile.birth_date`, 지역 마스터 17건 시드 | ✅ 2026-08-16 |
| `V20260816_04__drop_scholarship_tag.sql` | `scholarship_tag` 테이블 제거 (태그 기능 철회) | ✅ 2026-08-17 (배포 후 적용) |
| `V20260817_01__scholarship_enrichment.sql` | `scholarship.detail_url`·`enriched_at`, `image.source_url` (자동 보완) | ✅ 2026-08-17 |
| `V20260817_02__release_withdrawn_user_unique_keys.sql` | 탈퇴 회원이 점유한 `users.login_id`·`kakao_id` 해제 (재가입 차단 해소) | ✅ 2026-08-17 |
| `V20260817_03__scholarship_report_multi_reason.sql` | 신고 사유 다중 선택 (`scholarship_report_reason` 테이블 + `reason` 컬럼 제거) | ✅ 2026-08-17 |
| `V20260817_04__user_family_type_interest_to_profile_fk.sql` | `user_family_type`·`user_interest` 의 `user_id` 를 users(uuid) → user_profile(bigint) 참조로 전환 | ✅ 2026-08-17 |
| `V20260818_01__create_scholarship_merge_candidate.sql` | `scholarship_merge_candidate` 테이블 추가 (중복 병합 승인 큐) | ✅ 2026-08-17 |
| `V20260818_02__add_merge_admin_actions.sql` | `admin_audit_log.action` CHECK 에 병합 액션 3개 추가 | ✅ 2026-08-17 |
| `V20260818_03__create_notice_parse_log.sql` | `notice_parse_log` 테이블 추가 (LLM 파싱 이력·정확도 측정) | ✅ 2026-08-18 |
| `V20260818_04__condition_necessity_and_refs.sql` | 조건에 `necessity`(필수/우대) + `scholarship_condition_ref` 집합 참조 | ✅ 2026-08-18 |
| `V20260818_05__financial_aid_type_preferred.sql` | `FINANCIAL_AID_TYPE` 조건을 `PREFERRED` 로 (지원 성격은 자격이 아니다) | ✅ 2026-08-18 |
| `V20260818_06__create_scholarship_event.sql` | `scholarship_event` 테이블 추가 (추천 노출·클릭 기록) | ✅ 2026-08-18 |
| `V20260818_07__add_condition_ref_backfill_action.sql` | `admin_audit_log.action` CHECK 에 `CONDITION_REF_BACKFILL` 추가 | ✅ 2026-08-18 |
| `V20260818_08__parse_status_image_only.sql` | `ParseStatus` 에 `IMAGE_ONLY` 추가 (raw_scholarship·notice_parse_log 양쪽 CHECK) | ✅ 2026-08-18 |
| `V20260818_09__unify_konkuk_source.sql` | 건국대 출처 `KONKUK_NOTICE` → `UNIV_KONKUK` (재파싱 대상에서 빠져 있던 문제 해결) | ✅ 2026-08-18 |
| `V20260818_10__remove_yonsei_non_scholarship.sql` | 연세대 수집분 중 장학 아닌 공지 18건 목록에서 내림 | ✅ 2026-08-18 |
| `V20260818_11__scholarship_essay_interview_requirement.sql` | 장학금에 자소서·면접 필요 여부(3값+NULL)와 근거 문장 추가 | ✅ 2026-08-18 |
| `V20260818_12__notice_kind_combined_submission.sql` | 공지 종류(모집/결과/안내)·통합 공고 여부·제출 방식·제출 경로 | ✅ 2026-08-18 |
| `V20260818_13__parse_log_body_from_image_alt.sql` | 본문을 이미지 alt 로 대체했는지 파싱 이력에 기록 (OCR 대상 선별용) | ✅ 2026-08-18 |
| `V20260818_14__active_user_email_unique.sql` | `users.email` 전역 UNIQUE를 활성 계정의 `(email, login_type)` 부분 UNIQUE로 교체 | ✅ 2026-08-18 |
| `V20260819_08__create_content_inquiry.sql` | `content_inquiry` 테이블 추가 (콘텐츠 이용 문의) + 상태 조회 인덱스 | ✅ 2026-08-19 |
| `V20260819_09__create_interview_prep_question.sql` | `interview_prep_question` 테이블 추가 (면접 예상 질문 캐시) + `(scholarship_id, display_order)` UNIQUE | ⬜ 미적용 |
| `V20260819_20__essay_question_source.sql` | `essay.question_source` 추가 (맞춤 문항 생성 여부 — 재호출 멱등성) + CHECK 제약 | ⬜ 미적용 |
| `V20260819_10__always_open_reviewed_at.sql` | 상시모집 장학금의 관리자 마지막 원문 확인 시각 추가 | ✅ 2026-08-19 |
| `V20260819_11__create_admin_job_run.sql` | 관리자 배치 실행 이력·부분 실패 알림 테이블 추가 | ✅ 2026-08-19 |
| `V20260819_12__admin_audit_snapshots.sql` | 수기 수정·내리기 변경 전후 스냅샷과 1회 복구 이력 추가 | ✅ 2026-08-19 |
| `V20260819_13__merge_candidate_origin.sql` | 중복 후보 생성 경로(LLM/관리자 수기) 구분 | ✅ 2026-08-19 |
| `V20260819_14__add_admin_console_actions.sql` | 통합 수정·이미지·수기 중복 후보 감사 액션 추가 | ✅ 2026-08-19 |
| `V20260820_03__scholarship_dedup_scanned_at.sql` | `scholarship.dedup_scanned_at` 추가 (중복 탐지를 "최신 30건 다시 보기"에서 "안 본 것부터 한 바퀴"로) | ⬜ 미적용 |
| `V20260820_02__scholarship_school_id.sql` | `scholarship.school_id` 추가 + provider 기준 백필 (교내 공고를 다른 학교 학생에게 보여주지 않기 위함) | ⬜ 미적용 |
| `V20260820_01__interview_prep_answer_guide.sql` | 면접 준비 자료 — `interview_prep_question` 에 `answer_tip`·`sample_answer` 추가, `interview_prep_guide_step`·`interview_prep_sample_answer` 테이블 신규 | ⬜ 미적용 |

> ⚠️ **`V20260820_03` 도 배포보다 먼저** 적용해야 한다. `Scholarship.dedupScannedAt` 이 새로 생겨
> 컬럼이 없으면 `validate` 가 실패한다. 기존 행은 전부 NULL(= 아직 검사 안 함)로 두는 것이 의도다 —
> 처음 몇 번의 배치가 밀린 분량을 나눠 처리한다.

> ⚠️ **`V20260820_02` 는 반드시 배포보다 먼저** 적용해야 한다. `Scholarship` 엔티티에 `school`
> 연관이 새로 생기므로, 컬럼이 없으면 `validate` 가 실패해 **애플리케이션이 아예 뜨지 않는다.**
> 백필은 `provider` 를 학교 마스터와 견주는데, 정규화한 이름이 **정확히 한 학교에 걸릴 때만**
> 채운다. 애매한 건 비워 두는 편이 안전하다 — 잘못 지정한 학교는 자격 있는 학생을 조용히
> 떨어뜨리고, 비워 두면 관문이 걸리지 않을 뿐이다. 적용 뒤 아래로 몇 건이 채워졌는지 확인할 것.
>
> ```sql
> SELECT count(*) FILTER (WHERE school_id IS NOT NULL) AS 학교지정, count(*) AS 전체 FROM scholarship;
> ```

> `V20260817_04` 는 **사후에 만든 마이그레이션**이다. 커밋 `f24ed62`("household 매핑을 user profile
> 기준으로 저장")가 엔티티를 `@ManyToOne User`(uuid) → `@ManyToOne UserProfile`(bigint) 로 바꾸면서
> 컬럼 타입을 바꾸는 SQL 을 함께 올리지 않았고, 그 사실이 **2026-08-17 배포가 실패하고 나서야**
> 드러났다(`wrong column type ... found [uuid], but expecting [bigint]`).
> 엔티티의 연관 대상을 바꾸는 변경은 컬럼 타입이 따라 바뀐다는 점을 기억할 것.

> ⚠️ **`V20260818_06` 도 반드시 배포보다 먼저** 적용해야 한다. `ScholarshipEvent` 엔티티가 새로
> 생겨 테이블이 없으면 `validate` 가 실패해 앱이 기동되지 않는다.

> `V20260818_05` 는 기동을 막지 않는다(값만 바꾼다). 다만 **`V20260818_04` 다음에** 돌려야 한다 —
> 04 가 만든 `necessity` 컬럼을 갱신하기 때문이다. 적용 전까지는 "생활비 지원" 같은 지원 성격이
> 자격요건으로 남아, 참조가 채워지는 순간 관심분야를 안 고른 학생을 탈락시킨다.

> ⚠️ **`V20260818_04` 도 배포보다 먼저** 적용해야 한다. `necessity` 는 **기존 행을 `REQUIRED` 로
> 채운 뒤** NOT NULL 을 건다 — NULL 로 두면 지금 작동 중인 소득·성적·학년 게이트가 통째로 풀려
> "조건 미충족" 섹션이 비어버린다.

> ⚠️ **`V20260818_03` 은 반드시 배포보다 먼저** 적용해야 한다. `NoticeParseLog` 엔티티가 새로
> 생기므로 테이블이 없으면 `validate` 가 실패해 **애플리케이션이 뜨지 않는다**(= 배포 실패·롤백).

> ⚠️ **`V20260818_01` 은 반드시 배포보다 먼저** 적용해야 한다. `ScholarshipMergeCandidate` 엔티티가
> 새로 생겼으므로, 테이블이 없으면 `validate` 가 실패해 **애플리케이션이 아예 뜨지 않는다**
> (= 배포 실패·롤백). 이 목록에서 유일하게 기동을 막는 항목이다.

> `V20260818_02` 는 기동을 막지는 않는다(`validate` 는 CHECK 를 보지 않는다). 대신 적용 전까지
> 병합 승인·거절의 **감사 로그 INSERT 가 23514 로 실패**한다. 병합은 스크랩·자소서를 다른
> 장학금으로 옮기는 파괴적 작업이라 기록이 남지 않으면 곤란하므로 함께 적용한다.

> ⚠️ `V20260816_01` 이 **두 개**다(`create_admin_audit_log`, `seed_sigungu_regions`). 같은 버전
> 접두사라 나중에 Flyway 를 도입하면 충돌한다. 도입 시점에 한쪽 번호를 바꿔야 한다.

> `V20260817_03` 은 **반드시 배포보다 먼저** 적용해야 한다. 엔티티에서 `reason` 필드가
> 사라지므로, 적용 전에 새 코드가 뜨면 NOT NULL 인 `scholarship_report.reason` 에 값을 못 넣어
> **신고 접수가 전부 실패**한다. 반대로 SQL 만 먼저 적용하고 옛 코드가 떠 있는 동안에도
> 같은 이유로 신고가 실패하므로, 이 둘 사이 간격을 짧게 가져가는 편이 좋다.

> `V20260817_02` 는 **스키마 변경이 아니라 데이터 정정**이다. `validate` 와 무관하므로
> 배포 순서를 지키지 않아도 기동은 깨지지 않는다. 다만 적용 전까지는 수정 이전에 탈퇴한
> 회원이 같은 아이디·카카오 계정으로 재가입할 수 없으니, 배포와 함께 적용하는 편이 좋다.

> `V20260815_01` 은 2026-08-16 점검에서 **운영 제약에 `WORK_STUDY` 가 이미 들어 있음을 확인**했다.
> (`pg_get_constraintdef` 로 `ARRAY['INTERNAL','EXTERNAL','WORK_STUDY']` 확인)
> 배경: `WORK_STUDY` 가 2026-07-23(`2ae8a8c`)에 추가됐으나 마이그레이션이 누락돼,
> 적용 전까지 근로장학 공고 수집이 트랜잭션째 롤백되고 있었다. `validate` 는 CHECK 제약을
> 검사하지 않아 부팅은 정상이고 INSERT 때만 터지는 유형이다.

> `V20260731_01` 은 2026-08-05 점검에서 **운영에 이미 반영되어 있음을 확인**했다(컬럼·인덱스 모두 일치).
> 표기만 `미적용` 으로 남아 있던 것이라 정정한다. 앞으로는 적용 직후 이 표를 함께 갱신할 것.

## 제약이 어긋나는 이유 (반복 주의)

Hibernate `ddl-auto: update` 는 **컬럼·테이블만 추가할 뿐 기존 CHECK 제약을 고치지 않는다.**
코드에서 enum 값을 바꿔도 DB 제약은 옛 값 그대로 남아, 실행 시점에
`23514 violates check constraint` 로 500 이 난다. `validate` 는 제약 내용까지는 보지 않으므로
**기동은 성공하는데 특정 API 만 계속 실패하는** 형태로 나타난다.

enum 값을 추가·변경하는 PR 은 반드시 이 디렉터리에 `ALTER TABLE ... DROP/ADD CONSTRAINT` SQL 을 함께 올린다.
현재 DB 제약을 확인하는 방법:

```sql
SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype = 'c' ORDER BY 1;
```
